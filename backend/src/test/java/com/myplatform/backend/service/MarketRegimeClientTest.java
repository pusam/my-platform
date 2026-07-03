package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시장 국면 응답 파싱 (V32) — python-backend /api/v2/regime/current 응답 순수 함수 테스트.
 * 유효 어휘(BULL/BEAR/SIDEWAYS) 밖이면 null — 미수집 처리 (§4c 위장 금지).
 */
class MarketRegimeClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    @DisplayName("정상 응답 → BULL/BEAR/SIDEWAYS 파싱")
    void parse_validRegimes() throws Exception {
        for (String regime : new String[]{"BULL", "BEAR", "SIDEWAYS"}) {
            JsonNode resp = json("{\"success\":true,\"data\":{\"regime\":\"" + regime + "\",\"kospiClose\":2712.14}}");
            assertThat(MarketRegimeClient.parseRegime(resp)).isEqualTo(regime);
        }
    }

    @Test
    @DisplayName("success=false / 어휘 밖 / data 없음 / null → null (미수집)")
    void parse_invalidResponses() throws Exception {
        assertThat(MarketRegimeClient.parseRegime(
                json("{\"success\":false,\"message\":\"계산 불가\",\"data\":null}"))).isNull();
        assertThat(MarketRegimeClient.parseRegime(
                json("{\"success\":true,\"data\":{\"regime\":\"MOON\"}}"))).isNull();
        assertThat(MarketRegimeClient.parseRegime(
                json("{\"success\":true,\"data\":{}}"))).isNull();
        assertThat(MarketRegimeClient.parseRegime(null)).isNull();
    }

    // ==================== stale 폴백 금지 (§4c) — python 다운 시 만료 캐시 반환 금지 ====================

    private MarketRegimeClient unreachableClient() {
        // 127.0.0.1:1 — 즉시 connection refused (python 다운 시뮬레이션)
        PythonBackendHealthTracker health = org.mockito.Mockito.mock(PythonBackendHealthTracker.class);
        return new MarketRegimeClient("http://127.0.0.1:1", health);
    }

    private void seedCache(MarketRegimeClient client, String regime, java.time.Instant at) {
        org.springframework.test.util.ReflectionTestUtils.setField(client, "cachedRegime", regime);
        org.springframework.test.util.ReflectionTestUtils.setField(client, "cachedAt", at);
    }

    @Test
    @DisplayName("캐시 만료 + python 다운 → null (수일 전 국면을 무기한 반환하지 않음 — regime_at_signal 오염 방지)")
    void expiredCacheAndDown_returnsNull() {
        MarketRegimeClient client = unreachableClient();
        seedCache(client, "BULL", java.time.Instant.now().minus(java.time.Duration.ofHours(2)));  // TTL(1h) 초과

        assertThat(client.getCurrentRegimeQuiet()).isNull();   // 수정 전엔 "BULL"(stale) 반환이 버그
    }

    @Test
    @DisplayName("캐시 TTL 내 → HTTP 없이 캐시 반환 (python 다운이어도 정상)")
    void freshCache_returnsWithoutHttp() {
        MarketRegimeClient client = unreachableClient();
        seedCache(client, "SIDEWAYS", java.time.Instant.now().minus(java.time.Duration.ofMinutes(10)));

        assertThat(client.getCurrentRegimeQuiet()).isEqualTo("SIDEWAYS");
    }
}
