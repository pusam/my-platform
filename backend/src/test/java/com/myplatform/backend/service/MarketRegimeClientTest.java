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
}
