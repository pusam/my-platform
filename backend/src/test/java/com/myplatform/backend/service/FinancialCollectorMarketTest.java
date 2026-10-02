package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재무 수집기의 <b>시장 구분</b> — 순수 함수(2026-10-02 화면 점검).
 *
 * <p><b>무엇이 틀렸나</b>: KIS 대표시장명에 '코스닥'·'KOSDAQ' 이 없으면 <b>코드 첫 글자</b>(3·4·9 → KOSDAQ)로 짐작했다.
 * 10/2 운영 최신 행 2,666 중 <b>264종목</b>이 마스터(KIND 상장법인목록)와 달랐다 — 스크리너가 SK스퀘어·LG에너지솔루션을
 * KOSDAQ, 알테오젠·에코프로를 KOSPI 로 보여줬다.
 */
class FinancialCollectorMarketTest {

    private static Function<String, String> master(Map<String, String> markets) {
        return markets::get;
    }

    @Test
    @DisplayName("재현: 코드가 3·4 로 시작하는 KOSPI 대형주가 KOSDAQ 이 되던 것 — 마스터가 먼저")
    void masterDecidesKospiLargeCapsWithThreeOrFourPrefix() {
        Function<String, String> m = master(Map.of("373220", "KOSPI", "402340", "KOSPI"));
        assertThat(StockFinancialDataCollector.resolveMarket("373220", m, "KOSPI200")).isEqualTo("KOSPI");
        assertThat(StockFinancialDataCollector.resolveMarket("402340", m, "")).isEqualTo("KOSPI");
    }

    @Test
    @DisplayName("재현: KOSDAQ150 종목(대표시장명 'KSQ150')이 KOSPI 가 되던 것")
    void kosdaq150IsKosdaq() {
        Function<String, String> m = master(Map.of("196170", "KOSDAQ"));
        assertThat(StockFinancialDataCollector.resolveMarket("196170", m, "KSQ150")).isEqualTo("KOSDAQ");
        // 마스터가 몰라도 대표시장명의 KSQ 로 KOSDAQ
        assertThat(StockFinancialDataCollector.resolveMarket("196170", master(Map.of()), "KSQ150")).isEqualTo("KOSDAQ");
    }

    @Test
    @DisplayName("마스터와 KIS 가 다르면 마스터(시장별 목록) — 이전상장 직후 KIS 대표시장명이 늦을 수 있다")
    void masterWinsOverKis() {
        assertThat(StockFinancialDataCollector.resolveMarket("000660", master(Map.of("000660", "KOSPI")), "코스닥"))
                .isEqualTo("KOSPI");
    }

    @Test
    @DisplayName("우선주는 KIND 목록에 없다 — 같은 회사 보통주의 마스터 시장")
    void preferredShareFollowsCommonShare() {
        Function<String, String> m = master(Map.of("005930", "KOSPI"));
        assertThat(StockFinancialDataCollector.resolveMarket("005935", m, "")).isEqualTo("KOSPI");
    }

    @Test
    @DisplayName("끝까지 모르면 UNKNOWN — 코드 첫 글자로 짐작하지 않는다(§4c)")
    void unknownWhenNothingTells() {
        assertThat(StockFinancialDataCollector.resolveMarket("373220", master(Map.of()), "")).isEqualTo("UNKNOWN");
        assertThat(StockFinancialDataCollector.resolveMarket("005930", master(Map.of()), "KRX300")).isEqualTo("UNKNOWN");
        assertThat(StockFinancialDataCollector.resolveMarket("005930", null, null)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("마스터 조회가 예외를 던져도 수집은 계속된다 — KIS 대표시장명으로")
    void masterLookupFailureFallsThrough() {
        Function<String, String> broken = code -> { throw new IllegalStateException("boom"); };
        assertThat(StockFinancialDataCollector.resolveMarket("005930", broken, "KOSPI200")).isEqualTo("KOSPI");
    }
}
