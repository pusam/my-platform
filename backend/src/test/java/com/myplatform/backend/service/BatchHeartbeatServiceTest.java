package com.myplatform.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 크론 dead-man switch(BatchHeartbeatService) 테스트 — 순수 judge 경계 + §4c(결측/조회실패 오탐 금지)
 * + 잡별 일 1회 스로틀.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BatchHeartbeatServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-07-06T00:05:00Z");   // 월 09:05 KST

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private SchedulerLockService lockService;
    @Mock private ObjectProvider<TelegramNotificationService> telegramProvider;
    @Mock private TelegramNotificationService telegram;

    private BatchHeartbeatService service;

    @BeforeEach
    void setup() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(lockService.tryLock(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(telegramProvider.getIfAvailable()).thenReturn(telegram);
        service = new BatchHeartbeatService(redis, telegramProvider, lockService,
                Clock.fixed(NOW, KST));
    }

    // ==================== judge (순수) ====================

    @Test
    @DisplayName("judge: 마지막 성공 없음 → MISSING (콜드스타트 — 경고 아님, §4c)")
    void judgeMissing() {
        assertThat(BatchHeartbeatService.judge(null, NOW, Duration.ofDays(2)))
                .isEqualTo(BatchHeartbeatService.Verdict.MISSING);
    }

    @Test
    @DisplayName("judge: 경과 == 임계는 OK, 초과만 STALE (경계값)")
    void judgeBoundary() {
        Duration max = Duration.ofDays(2);
        assertThat(BatchHeartbeatService.judge(NOW.minus(Duration.ofDays(2)), NOW, max))
                .isEqualTo(BatchHeartbeatService.Verdict.OK);
        assertThat(BatchHeartbeatService.judge(NOW.minus(Duration.ofDays(2)).minusSeconds(1), NOW, max))
                .isEqualTo(BatchHeartbeatService.Verdict.STALE);
        assertThat(BatchHeartbeatService.judge(NOW.minusSeconds(60), NOW, max))
                .isEqualTo(BatchHeartbeatService.Verdict.OK);
    }

    @Test
    @DisplayName("parseInstant: null/빈값/손상값 → null (MISSING 처리 — 위장값 생성 금지)")
    void parseRobust() {
        assertThat(BatchHeartbeatService.parseInstant("job", null)).isNull();
        assertThat(BatchHeartbeatService.parseInstant("job", " ")).isNull();
        assertThat(BatchHeartbeatService.parseInstant("job", "garbage")).isNull();
        assertThat(BatchHeartbeatService.parseInstant("job", "2026-07-06T00:00:00Z"))
                .isEqualTo(Instant.parse("2026-07-06T00:00:00Z"));
    }

    // ==================== recordSuccess ====================

    @Test
    @DisplayName("recordSuccess: 현재 시각을 TTL 없이 기록")
    void recordWritesNow() {
        service.recordSuccess(BatchHeartbeatService.JOB_SIGNAL_EVALUATION);
        verify(valueOps).set("java:batch-heartbeat:signal-evaluation", NOW.toString());
    }

    @Test
    @DisplayName("recordSuccess: Redis 예외를 삼킨다 — 배치 본체 무영향(best-effort)")
    void recordSwallowsRedisFailure() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString());
        service.recordSuccess(BatchHeartbeatService.JOB_DISCOVERY_RESET);   // 예외 전파 없으면 통과
    }

    // ==================== checkHeartbeats ====================

    private void stubHeartbeat(String jobKey, String value) {
        when(valueOps.get("java:batch-heartbeat:" + jobKey)).thenReturn(value);
    }

    @Test
    @DisplayName("전 잡 신선 → 경고 없음")
    void allFreshNoAlert() {
        stubHeartbeat(BatchHeartbeatService.JOB_SIGNAL_EVALUATION, NOW.minus(Duration.ofHours(14)).toString());
        stubHeartbeat(BatchHeartbeatService.JOB_DISCOVERY_RESET, NOW.minus(Duration.ofHours(1)).toString());
        stubHeartbeat(BatchHeartbeatService.JOB_WEEKLY_REPORT, NOW.minus(Duration.ofDays(1)).toString());
        service.checkHeartbeats();
        verify(telegram, never()).sendRisk(anyString());
    }

    @Test
    @DisplayName("심박 키 없음(콜드스타트) → INFO 만, 텔레그램 경고 없음(§4c)")
    void missingKeyIsInfoNotAlert() {
        stubHeartbeat(BatchHeartbeatService.JOB_SIGNAL_EVALUATION, null);
        stubHeartbeat(BatchHeartbeatService.JOB_DISCOVERY_RESET, null);
        stubHeartbeat(BatchHeartbeatService.JOB_WEEKLY_REPORT, null);
        service.checkHeartbeats();
        verify(telegram, never()).sendRisk(anyString());
    }

    @Test
    @DisplayName("임계 초과 잡만 리스크 채널 경고 — 같은 날 재체크 시 잡별 일 1회 스로틀")
    void staleAlertsOncePerDay() {
        stubHeartbeat(BatchHeartbeatService.JOB_SIGNAL_EVALUATION, NOW.minus(Duration.ofDays(5)).toString());   // 임계 4일 초과
        stubHeartbeat(BatchHeartbeatService.JOB_DISCOVERY_RESET, NOW.minus(Duration.ofHours(1)).toString());    // 신선
        stubHeartbeat(BatchHeartbeatService.JOB_WEEKLY_REPORT, NOW.minus(Duration.ofDays(1)).toString());       // 신선

        service.checkHeartbeats();
        service.checkHeartbeats();   // 같은 날 2번째 — 스로틀

        verify(telegram, times(1)).sendRisk(contains("시그널 평가"));
        verify(telegram, never()).sendRisk(contains("발굴"));
    }

    @Test
    @DisplayName("Redis 조회 실패(UNKNOWN) → 경고 없음 — 감시 시스템 장애를 배치 사망으로 위장 금지(§4c)")
    void redisFailureNoAlert() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        service.checkHeartbeats();
        verify(telegram, never()).sendRisk(anyString());
    }

    @Test
    @DisplayName("Redis 미구성(dev) → recordSuccess/checkHeartbeats 전부 no-op")
    void noRedisNoOp() {
        BatchHeartbeatService noRedis = new BatchHeartbeatService(null, telegramProvider, lockService,
                Clock.fixed(NOW, KST));
        noRedis.recordSuccess(BatchHeartbeatService.JOB_SIGNAL_EVALUATION);
        noRedis.checkHeartbeats();
        verify(telegram, never()).sendRisk(anyString());
    }
}
