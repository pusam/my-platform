package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.core.util.DateTimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * KIS 접근토큰 L2 공유(Redis) — 프로세스 재시작을 넘겨 토큰을 잇는다.
 *
 * <p>고치려는 결함(2026-09-11 실측): KIS 는 토큰 발급을 <b>분당 1회</b>로 자른다(초과 시 HTTP 403
 * {@code EGW00133} "접근토큰 발급 잠시 후 다시 시도하세요"). 토큰이 프로세스 메모리에만 있어서, 커밋 2개를
 * 연달아 밀어 컨테이너가 8분 간격으로 두 번 재생성되자 두 번째 컨테이너가 빈 캐시로 다시 요청했고 →
 * 403 → <b>08:20:21~08:21:51 90초간 모든 KIS 호출 불가</b>(ERROR 11줄, 65초 쿨다운이 재시도해 자가 회복).
 *
 * <p>가장 중요한 케이스는 {@link #invalidationAlsoEvictsRedis()} 다 — 무효화가 Redis 까지 지우지 않으면
 * 죽은 토큰이 재시작을 넘어 되살아나 9/8 의 20분 전멸이 <b>영구화</b>된다. 이 기능이 사고로 바뀌는 지점이다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KisTokenRedisSharingTest {

    private static final String REDIS_KEY = "kis:access-token";
    private static final String EXPIRED_BODY =
            "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\",\"msg1\":\"기간이 만료된 token 입니다.\"}";

    @Mock private RestTemplate restTemplate;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"access_token\":\"FRESH-ISSUED\",\"expires_in\":86400}"));
    }

    /** 새로 뜬 프로세스(빈 로컬 캐시). */
    private KisTokenManager freshManager(StringRedisTemplate redis) {
        KisTokenManager m = new KisTokenManager(restTemplate, mapper, redis);
        ReflectionTestUtils.setField(m, "appKey", "k");
        ReflectionTestUtils.setField(m, "appSecret", "s");
        ReflectionTestUtils.setField(m, "baseUrl", "https://mock.kis");
        return m;
    }

    private String payload(String token, LocalDateTime expireAt) {
        try {
            return mapper.writeValueAsString(
                    java.util.Map.of("token", token, "expireAt", expireAt.toString()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubRedisHas(String token, LocalDateTime expireAt) {
        when(valueOps.get(REDIS_KEY)).thenReturn(payload(token, expireAt));
    }

    // ==================== 재현: 재생성 직후 발급하지 않는다 ====================

    @Test
    @DisplayName("새 프로세스가 Redis 의 유효 토큰을 채택 — 발급 호출 0회 (수정 전엔 여기서 403)")
    void freshProcessAdoptsSharedTokenWithoutIssuing() {
        stubRedisHas("SHARED-VALID", DateTimeUtil.kstNow().plusHours(20));

        String token = freshManager(redisTemplate).getAccessToken();

        assertThat(token).isEqualTo("SHARED-VALID");
        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    @DisplayName("403 쿨다운 중이어도 Redis 유효 토큰이면 즉시 회복 — 65초 기다리지 않는다")
    void cooldownDoesNotBlockSharedToken() {
        stubRedisHas("SHARED-VALID", DateTimeUtil.kstNow().plusHours(20));
        KisTokenManager m = freshManager(redisTemplate);
        ReflectionTestUtils.setField(m, "tokenCooldownUntil", DateTimeUtil.kstNow().plusSeconds(65));

        assertThat(m.getAccessToken()).as("쿨다운은 '발급'을 막는 것이지 유효 토큰 사용을 막는 게 아니다")
                .isEqualTo("SHARED-VALID");
        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(String.class));
    }

    // ==================== 유효 창 판정은 로컬과 같아야 한다 ====================

    @Test
    @DisplayName("만료 1시간 안 남은 Redis 토큰은 채택하지 않고 발급한다 — 로컬과 같은 규칙")
    void nearlyExpiredSharedTokenIsNotAdopted() {
        stubRedisHas("ALMOST-DEAD", DateTimeUtil.kstNow().plusMinutes(30));

        assertThat(freshManager(redisTemplate).getAccessToken()).isEqualTo("FRESH-ISSUED");
        verify(restTemplate).postForEntity(anyString(), any(), eq(String.class));
    }

    // ==================== 발급분을 공유한다 ====================

    @Test
    @DisplayName("발급 성공분을 Redis 에 게시 — 남은 유효기간이 TTL")
    void issuedTokenIsPublishedWithTtl() {
        when(valueOps.get(REDIS_KEY)).thenReturn(null);

        assertThat(freshManager(redisTemplate).getAccessToken()).isEqualTo("FRESH-ISSUED");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq(REDIS_KEY), body.capture(), ttl.capture());
        assertThat(body.getValue()).contains("FRESH-ISSUED").contains("expireAt");
        assertThat(ttl.getValue()).isGreaterThan(Duration.ofHours(23))
                .isLessThanOrEqualTo(Duration.ofHours(24));
    }

    // ==================== 가장 중요: 무효화가 Redis 도 지운다 ====================

    @Test
    @DisplayName("만료 토큰 무효화가 Redis 키도 지운다 — 안 지우면 죽은 토큰이 재시작을 넘어 되살아난다")
    void invalidationAlsoEvictsRedis() {
        KisTokenManager m = freshManager(redisTemplate);
        ReflectionTestUtils.setField(m, "accessToken", "DEAD");
        ReflectionTestUtils.setField(m, "tokenExpireTime", DateTimeUtil.kstNow().plusHours(20));

        m.invalidateOnAuthFailure(expiredToken500(), "DEAD");

        assertThat(ReflectionTestUtils.getField(m, "accessToken")).isNull();
        verify(redisTemplate).delete(REDIS_KEY);
    }

    @Test
    @DisplayName("CAS 불일치(이미 재발급됨)면 Redis 도 건드리지 않는다 — stale 401 이 새 토큰을 죽이지 않게")
    void staleInvalidationTouchesNothing() {
        KisTokenManager m = freshManager(redisTemplate);
        ReflectionTestUtils.setField(m, "accessToken", "NEW-GENERATION");
        ReflectionTestUtils.setField(m, "tokenExpireTime", DateTimeUtil.kstNow().plusHours(20));

        m.invalidateOnAuthFailure(expiredToken500(), "OLD-GENERATION");

        assertThat(ReflectionTestUtils.getField(m, "accessToken")).isEqualTo("NEW-GENERATION");
        verify(redisTemplate, never()).delete(anyString());
    }

    // ==================== Redis 가 없거나 죽어도 종전 동작 ====================

    @Test
    @DisplayName("Redis 미구성(null)이면 종전과 동일 — 메모리 전용으로 발급")
    void withoutRedisBehavesAsBefore() {
        assertThat(freshManager(null).getAccessToken()).isEqualTo("FRESH-ISSUED");
        verify(restTemplate).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    @DisplayName("Redis 장애(조회 예외)면 발급으로 진행 — 인증이 Redis 에 인질로 잡히지 않는다")
    void redisFailureFailsOpen() {
        when(valueOps.get(REDIS_KEY)).thenThrow(new IllegalStateException("redis down"));

        assertThat(freshManager(redisTemplate).getAccessToken()).isEqualTo("FRESH-ISSUED");
        verify(restTemplate).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    @DisplayName("Redis 값이 깨져 있어도(JSON 아님) 발급으로 진행")
    void corruptRedisValueFailsOpen() {
        when(valueOps.get(REDIS_KEY)).thenReturn("not-json");

        assertThat(freshManager(redisTemplate).getAccessToken()).isEqualTo("FRESH-ISSUED");
    }

    private static HttpServerErrorException expiredToken500() {
        return HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                null, EXPIRED_BODY.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
