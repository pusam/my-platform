package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * '일회성 이익 의심' 문구 — 영업이익이 음수면 비율을 말하지 않는다(2026-10-07 화면 점검).
 *
 * <p>재현: 삼성SDI 상세가 '일회성 이익 의심: 순이익이 영업이익 대비 128.8% 높음'이라고 했다. 실제는 TTM 영업손실 −11,297억에
 * 순이익 +3,255억 — (순이익 − 영업이익) ÷ |영업이익| 이 128.8% 였을 뿐, 순이익이 영업이익의 128.8% 라는 뜻이 아니다(3,255 ÷
 * −11,297 ≈ −29%). 음수를 기준으로 한 '대비 %'는 읽을 수 없는 숫자다 — 사실(영업손실인데 순이익 흑자)을 그대로 말하고 비율은 비운다.
 * 경고 여부(진단 점수에 들어간다)는 그대로다.
 */
class StockAnalysisOneTimeGainTest {

    private static StockAnalysisService.OneTimeGain judge(String op, String net) {
        return StockAnalysisService.judgeOneTimeGain(new BigDecimal(op), new BigDecimal(net));
    }

    @Test
    @DisplayName("재현: 영업손실인데 순이익 흑자 — 경고는 그대로, '대비 128.8%' 대신 사실을 말하고 비율은 비운다")
    void operatingLossNetProfit() {
        StockAnalysisService.OneTimeGain g = judge("-11297", "3255");
        assertThat(g.warning()).isTrue();
        assertThat(g.reason()).contains("영업손실").contains("순이익 흑자").doesNotContain("%");
        assertThat(g.gapRatio()).isNull();
    }

    @Test
    @DisplayName("영업손실보다 순손실이 크게 작으면(영업외 이익) 경고 — 역시 비율 없이")
    void lossShrunkByNonOperatingGain() {
        StockAnalysisService.OneTimeGain g = judge("-100", "-40");
        assertThat(g.warning()).isTrue();
        assertThat(g.reason()).contains("영업손실").doesNotContain("%");
        assertThat(g.gapRatio()).isNull();
    }

    @Test
    @DisplayName("영업손실과 순손실이 비슷하면 경고 없음 — 비율도 비운다(음수 기준)")
    void similarLosses() {
        StockAnalysisService.OneTimeGain g = judge("-100", "-90");
        assertThat(g.warning()).isFalse();
        assertThat(g.gapRatio()).isNull();
    }

    @Test
    @DisplayName("영업이익 흑자면 종전 그대로 — 순이익이 50% 넘게 많으면 비율과 함께 경고")
    void positiveBaseUnchanged() {
        StockAnalysisService.OneTimeGain g = judge("100", "180");
        assertThat(g.warning()).isTrue();
        assertThat(g.reason()).startsWith("순이익이 영업이익 대비 80.0% 높음");
        assertThat(g.gapRatio()).isEqualByComparingTo("80");

        StockAnalysisService.OneTimeGain small = judge("100", "120");
        assertThat(small.warning()).isFalse();
        assertThat(small.gapRatio()).isEqualByComparingTo("20");

        StockAnalysisService.OneTimeGain flipped = judge("100", "-10");
        assertThat(flipped.warning()).isTrue();
        assertThat(flipped.reason()).isEqualTo("영업이익 흑자, 순이익 적자 (영업외비용 확인 필요)");
    }

    @Test
    @DisplayName("영업이익 0·결측이면 판정하지 않는다")
    void noBase() {
        assertThat(StockAnalysisService.judgeOneTimeGain(BigDecimal.ZERO, new BigDecimal("10")).warning()).isFalse();
        assertThat(StockAnalysisService.judgeOneTimeGain(null, new BigDecimal("10")).warning()).isFalse();
        assertThat(StockAnalysisService.judgeOneTimeGain(new BigDecimal("10"), null).warning()).isFalse();
    }
}
