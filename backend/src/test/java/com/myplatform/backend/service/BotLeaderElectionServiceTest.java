package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 봇 리더 선출(fail-CLOSED) — 멀티 인스턴스 중복 주문 방지.
 * 시나리오: 인스턴스 2개 / Redis 다운 / 단일 인스턴스 / 비활성 bypass.
 */
class BotLeaderElectionServiceTest {

    /** 공유 Map 으로 동작하는 fake Redis 템플릿(= 같은 Redis 를 보는 여러 인스턴스 시뮬레이션). */
    @SuppressWarnings("unchecked")
    private static StringRedisTemplate sharedFakeRedis(Map<String, String> backing) {
        StringRedisTemplate t = mock(StringRedisTemplate.class);
        ValueOperations<String, String> v = mock(ValueOperations.class);
        when(t.opsForValue()).thenReturn(v);
        when(v.setIfAbsent(eq(BotLeaderElectionService.LEADER_KEY), anyString(), any(Duration.class)))
                .thenAnswer(inv -> backing.putIfAbsent(BotLeaderElectionService.LEADER_KEY, inv.getArgument(1)) == null);
        when(v.get(BotLeaderElectionService.LEADER_KEY)).thenAnswer(inv -> backing.get(BotLeaderElectionService.LEADER_KEY));
        return t;
    }

    // ── 순수 판정 헬퍼 ──────────────────────────────────────────
    @Test
    @DisplayName("decideLeadership — 획득/자기보유=리더, 타인보유/null=비리더")
    void decide() {
        assertThat(BotLeaderElectionService.decideLeadership(true, "other", "me")).isTrue();   // 막 획득
        assertThat(BotLeaderElectionService.decideLeadership(false, "me", "me")).isTrue();      // 자기 보유
        assertThat(BotLeaderElectionService.decideLeadership(false, "other", "me")).isFalse();  // 타인 보유
        assertThat(BotLeaderElectionService.decideLeadership(false, null, "me")).isFalse();     // 보유자 없음(경합)
        assertThat(BotLeaderElectionService.decideLeadership(false, "me", null)).isFalse();
    }

    // ── 인스턴스 2개 → 정확히 1개만 리더 ────────────────────────
    @Test
    @DisplayName("인스턴스 2개 공유 Redis — 정확히 1개만 리더, 나머지는 봇 주문 차단")
    void twoInstancesSingleLeader() {
        Map<String, String> redis = new HashMap<>();
        StringRedisTemplate shared = sharedFakeRedis(redis);
        var a = new BotLeaderElectionService(shared, true, 30L, "A");
        var b = new BotLeaderElectionService(shared, true, 30L, "B");

        a.heartbeat();   // A 먼저 → 획득
        b.heartbeat();   // B → 이미 A 보유 → 비리더

        assertThat(a.isLeaderForBot()).isTrue();
        assertThat(b.isLeaderForBot()).isFalse();
    }

    @Test
    @DisplayName("리더 유지 — 자기 보유면 하트비트마다 갱신")
    void leaderRenews() {
        Map<String, String> redis = new HashMap<>();
        var a = new BotLeaderElectionService(sharedFakeRedis(redis), true, 30L, "A");
        a.heartbeat();
        a.heartbeat();   // 두 번째: setIfAbsent false → get==self → 갱신, 리더 유지
        assertThat(a.isLeaderForBot()).isTrue();
    }

    // ── Redis 다운 → fail-CLOSED ────────────────────────────────
    @Test
    @DisplayName("Redis 예외 → 리더 자격 포기(fail-CLOSED), 봇 주문 차단")
    @SuppressWarnings("unchecked")
    void redisDownFailClosed() {
        StringRedisTemplate t = mock(StringRedisTemplate.class);
        ValueOperations<String, String> v = mock(ValueOperations.class);
        when(t.opsForValue()).thenReturn(v);
        when(v.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));
        var s = new BotLeaderElectionService(t, true, 30L, "A");
        s.heartbeat();
        assertThat(s.isLeaderForBot()).isFalse();
    }

    @Test
    @DisplayName("Redis 미연결(template=null) + enabled → fail-CLOSED")
    void redisAbsentFailClosed() {
        var s = new BotLeaderElectionService((StringRedisTemplate) null, true, 30L, "A");
        s.heartbeat();
        assertThat(s.isLeaderForBot()).isFalse();
    }

    // ── 비활성 bypass(단일 인스턴스 탈출구) ─────────────────────
    @Test
    @DisplayName("enabled=false → 항상 통과(단일 인스턴스 운영)")
    void disabledBypass() {
        var s = new BotLeaderElectionService((StringRedisTemplate) null, false, 30L, "A");
        s.heartbeat();                       // no-op
        assertThat(s.isLeaderForBot()).isTrue();
    }

    @Test
    @DisplayName("단일 인스턴스 정상 — 리스 획득 후 리더")
    void singleInstanceAcquires() {
        var s = new BotLeaderElectionService(sharedFakeRedis(new HashMap<>()), true, 30L, "solo");
        assertThat(s.isLeaderForBot()).isFalse();   // 하트비트 전엔 비리더
        s.heartbeat();
        assertThat(s.isLeaderForBot()).isTrue();
    }
}
