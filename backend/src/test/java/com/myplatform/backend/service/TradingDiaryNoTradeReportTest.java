package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 거래 없는 주의 주간 리포트 — {@code TradingDiaryService.noTradeReport}(2026-10-02).
 *
 * <p>재현: 9/21~9/27 실전 리포트는 매매 0건인데 AI 가 "다음 주엔 반드시 1회 이상 매매", "소액 실제 투자 병행" 같은 조언을
 * 지어냈다. 분석할 거래가 없으면 AI 를 부르지 않고 그 사실만 적는다(§4c).
 */
class TradingDiaryNoTradeReportTest {

    @Test
    @DisplayName("매매 0건이면 AI 를 부르지 않는 고정 문구 — 조언을 만들지 않는다고 밝힌다")
    void zeroTradesSkipAi() {
        String text = TradingDiaryService.noTradeReport(0, 0, 0);

        assertThat(text).contains("체결된 매매가 없습니다").contains("AI 분석을 생략");
        assertThat(text).doesNotContain("차단");
    }

    @Test
    @DisplayName("차단만 있었던 주는 차단 건수를 같이 적는다")
    void blockedOnlyWeekSaysSo() {
        assertThat(TradingDiaryService.noTradeReport(0, 0, 3)).contains("안전장치 차단 3건");
    }

    @Test
    @DisplayName("매매가 있으면 null — AI 분석을 그대로 진행한다")
    void tradesProceedToAi() {
        assertThat(TradingDiaryService.noTradeReport(1, 0, 0)).isNull();
        assertThat(TradingDiaryService.noTradeReport(0, 2, 0)).isNull();
    }
}
