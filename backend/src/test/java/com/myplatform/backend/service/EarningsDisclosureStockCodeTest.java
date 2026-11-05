package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실적공시 → 종목 연결. 손으로 적은 회사코드 표 25개 중 13개가 틀렸다(11/5 운영 DART 코드 대조): 삼성SDI 칸의 값이 삼성에스디에스 코드라
 * 삼성SDS 공시 11건이 삼성SDI 로, 삼성전기 칸의 값이 HMM 코드라 HMM 공시 4건이 삼성전기로 붙었고, 저장 행 18,878건 중 18,665건은 종목이 비어
 * 있었다. DART 공시 목록 응답에 종목코드가 이미 온다.
 */
class EarningsDisclosureStockCodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode item(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    @DisplayName("재현: 삼성에스디에스 공시가 삼성SDI 로 붙었다 — 응답의 종목코드를 그대로 쓴다")
    void usesStockCodeFromResponse() throws Exception {
        assertThat(EarningsDisclosureService.stockCodeOf(
                item("{\"corp_code\":\"00126186\",\"corp_name\":\"삼성에스디에스\",\"stock_code\":\"018260\"}")))
                .isEqualTo("018260");
        assertThat(EarningsDisclosureService.stockCodeOf(
                item("{\"corp_code\":\"00164645\",\"corp_name\":\"HMM\",\"stock_code\":\"011200\"}")))
                .isEqualTo("011200");
    }

    @Test
    @DisplayName("비상장은 종목코드가 비어 온다 — 지어내지 않는다")
    void blankStockCodeIsNull() throws Exception {
        assertThat(EarningsDisclosureService.stockCodeOf(item("{\"corp_code\":\"00999999\",\"stock_code\":\" \"}"))).isNull();
        assertThat(EarningsDisclosureService.stockCodeOf(item("{\"corp_code\":\"00999999\"}"))).isNull();
    }

    @Test
    @DisplayName("재현: 관심종목 '카카오' 에 카카오게임즈 공시가 붙었다 — 종목코드가 있으면 코드로만 고른다")
    void matchesByCodeWhenKnown() {
        Set<String> names = Set.of("카카오");
        Set<String> codes = Set.of("035720");
        assertThat(EarningsDisclosureService.isWatched("293490", "카카오게임즈", names, codes)).isFalse();
        assertThat(EarningsDisclosureService.isWatched("035720", "카카오", names, codes)).isTrue();
    }

    @Test
    @DisplayName("종목코드가 없는 옛 행은 종전처럼 이름으로 고른다")
    void fallsBackToNameWithoutCode() {
        assertThat(EarningsDisclosureService.isWatched(null, "카카오게임즈", Set.of("카카오"), Set.of())).isTrue();
        assertThat(EarningsDisclosureService.isWatched(null, "HMM", Set.of("카카오"), Set.of())).isFalse();
    }
}
