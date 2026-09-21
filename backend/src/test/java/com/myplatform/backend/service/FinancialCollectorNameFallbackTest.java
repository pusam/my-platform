package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재무 수집기의 <b>종목명 폴백</b> — 순수 함수(2026-09-21 데이터 점검 후속).
 *
 * <p><b>무엇이 틀렸나</b>: 수집기는 KIS 응답의 {@code hts_kor_isnm} 을 읽고, 비어 있으면
 * <b>조용히 종목코드를 이름 자리에 넣었다</b>. prod 실측 당일 수집분 <b>2,660행 중 1,869행(70%)</b>이
 * 그렇게 코드로 채워졌고, 그 값을 읽는 발굴 저평가·성장 트랙이 "금호석유화학" 대신 "011780" 을 보여줬다.
 *
 * <p><b>두 가지가 겹쳤다</b>:
 * <ul>
 *   <li>마스터(`stock_master`)에는 2,668종목 전부 정상 이름이 있는데 <b>물어보지 않았다</b>
 *   <li>70%가 비어 오는데 <b>로그가 한 줄도 없었다</b> — 침묵하면 아무도 모른다(§4c)
 * </ul>
 *
 * <p>⚠ 코드 폴백 자체는 유지한다 — 마스터에도 없으면 코드라도 남겨야 어떤 종목인지 안다.
 * 순서만 바꾼다: <b>KIS 응답 → 마스터 → 코드</b>.
 */
class FinancialCollectorNameFallbackTest {

    /** 마스터 조회 스텁 — 실제로는 StockMasterService.getNameOrDefault. */
    private static Function<String, String> master(Map<String, String> names) {
        return code -> names.get(code);
    }

    @Test
    @DisplayName("KIS 가 이름을 주면 그대로 쓴다 — 마스터를 묻지 않는다")
    void kisNameWins() {
        assertThat(StockFinancialDataCollector.resolveCollectedName(
                "005930", "삼성전자", master(Map.of("005930", "삼성전자우")))).isEqualTo("삼성전자");
    }

    @Test
    @DisplayName("KIS 이름이 비면 마스터에서 가져온다 — prod 70% 가 이 경우였다")
    void emptyKisNameFallsBackToMaster() {
        assertThat(StockFinancialDataCollector.resolveCollectedName(
                "011780", "", master(Map.of("011780", "금호석유화학")))).isEqualTo("금호석유화학");
        assertThat(StockFinancialDataCollector.resolveCollectedName(
                "011780", null, master(Map.of("011780", "금호석유화학")))).isEqualTo("금호석유화학");
        assertThat(StockFinancialDataCollector.resolveCollectedName(
                "011780", "   ", master(Map.of("011780", "금호석유화학")))).isEqualTo("금호석유화학");
    }

    @Test
    @DisplayName("마스터에도 없으면 코드를 남긴다 — 빈칸으로 두지 않는다(§4c)")
    void masterMissKeepsCode() {
        assertThat(StockFinancialDataCollector.resolveCollectedName(
                "999999", "", master(Map.of()))).isEqualTo("999999");
        assertThat(StockFinancialDataCollector.resolveCollectedName(
                "999999", "", master(Map.of("999999", "  ")))).isEqualTo("999999");
    }

    @Test
    @DisplayName("마스터 조회가 터져도 수집이 멈추지 않는다 — 코드로 진행(fail-open)")
    void masterFailureIsNotFatal() {
        Function<String, String> boom = code -> { throw new IllegalStateException("master down"); };
        assertThat(StockFinancialDataCollector.resolveCollectedName("011780", "", boom)).isEqualTo("011780");
    }

    @Test
    @DisplayName("마스터가 코드를 그대로 돌려줘도 코드로 본다 — 이름이 아니다")
    void masterReturningCodeIsNotAName() {
        assertThat(StockFinancialDataCollector.resolveCollectedName(
                "011780", "", master(Map.of("011780", "011780")))).isEqualTo("011780");
    }
}
