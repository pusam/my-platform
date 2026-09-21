package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forward 지표의 EPS 성장률은 <b>지어내지 않는다</b> — 순수 함수(2026-09-21 데이터 점검).
 *
 * <p><b>무엇이 틀렸나</b>: {@code enrichWithForwardMetrics} 가 EPS 성장률을 <b>PER 구간으로 만들어냈다</b> —
 * PER&lt;8 → 25% · &lt;12 → 18% · &lt;20 → 12% · 그 외 → 8% · PER 없으면 <b>15%</b>.
 * 그 값이 "EPS 성장률"로 화면에 뜨고 Forward EPS·Forward PER 까지 그 위에 계산돼 <b>예측치처럼</b> 보였다.
 *
 * <p>prod 실측으로 드러났다 — 삼성전자·SK하이닉스·카카오 <b>셋 다 정확히 8%</b>였고,
 * <b>존재하지도 않는 종목코드(999999)</b>가 15%·외국인 10% 를 받아왔다. 데이터가 아니라 상수다.
 *
 * <p><b>진짜 데이터는 같은 테이블에 있다</b>: {@code stock_financial_data.eps_growth} 가
 * 당일 2,660행 중 <b>2,587행(97%)</b> 채워져 있다. 그걸 쓰고, 없으면 <b>null</b> 이다(§4c).
 *
 * <p><b>불변식</b>: 성장률을 모르면 Forward EPS·PER 도 계산하지 않는다 — 입력을 지어낸 파생값은
 * 그 자체가 위장이다. "보수적 기본값"도 기본값이 아니라 발명이다.
 */
class ForwardMetricsHonestyTest {

    @Test
    @DisplayName("실측 EPS 성장률이 있으면 그대로 쓴다")
    void realGrowthIsUsed() {
        assertThat(StockDetailService.resolveEpsGrowthRate(new BigDecimal("23.4"), new BigDecimal("31.8")))
                .isEqualByComparingTo("23.4");
        // PER 이 얼마든 실측값을 이긴다 — PER 추정 로직은 사라졌다
        assertThat(StockDetailService.resolveEpsGrowthRate(new BigDecimal("-12.0"), new BigDecimal("5")))
                .isEqualByComparingTo("-12.0");
    }

    @Test
    @DisplayName("실측이 없으면 null — PER 로 지어내지 않는다")
    void missingGrowthIsNullNotInvented() {
        assertThat(StockDetailService.resolveEpsGrowthRate(null, new BigDecimal("31.8"))).isNull();
        assertThat(StockDetailService.resolveEpsGrowthRate(null, new BigDecimal("7"))).isNull();   // 옛 코드는 25%
        assertThat(StockDetailService.resolveEpsGrowthRate(null, null)).isNull();                  // 옛 코드는 15%
    }

    @Test
    @DisplayName("Forward EPS 는 성장률을 알 때만 나온다 — 모르면 계산 자체를 하지 않는다")
    void forwardEpsOnlyWithKnownGrowth() {
        assertThat(StockDetailService.forwardEps(new BigDecimal("6564"), new BigDecimal("8")))
                .isEqualByComparingTo("7089");     // 6564 × 1.08
        assertThat(StockDetailService.forwardEps(new BigDecimal("6564"), null)).isNull();
        assertThat(StockDetailService.forwardEps(null, new BigDecimal("8"))).isNull();
        assertThat(StockDetailService.forwardEps(BigDecimal.ZERO, new BigDecimal("8"))).isNull();
    }

    @Test
    @DisplayName("적자(EPS<=0)면 Forward EPS 를 만들지 않는다 — 성장률을 곱해도 의미가 없다")
    void negativeEpsProducesNothing() {
        assertThat(StockDetailService.forwardEps(new BigDecimal("-500"), new BigDecimal("8"))).isNull();
    }
}
