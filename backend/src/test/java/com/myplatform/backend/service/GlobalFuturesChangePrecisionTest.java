package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 해외 시세 등락 — 반올림한 차액으로 등락률을 내지 않는다(2026-10-06 화면 점검).
 *
 * <p>재현: 차액을 소수 둘째 자리로 먼저 반올림한 뒤 등락률을 계산해, 가격이 1.12 인 유로/달러는 실제 −0.0005(−0.04%)가
 * '0.00 (0.00%)'로 나왔다(Yahoo 실측 regularMarketPrice 1.1218 · chartPreviousClose 1.1223). 가격도 1.12 로 잘렸다.
 * 전일 종가가 없을 때 등락을 0 으로 채우던 것도 함께 — 모르는 등락은 '보합'이 아니다(§4c).
 */
class GlobalFuturesChangePrecisionTest {

    @Test
    @DisplayName("재현: 유로/달러 1.1223 → 1.1218 은 −0.0005 · −0.04% — '0.00 (0.00%)'가 아니다")
    void lowPricedPairKeepsItsChange() {
        GlobalFuturesService.Change c = GlobalFuturesService.changeOf(new BigDecimal("1.1218"), new BigDecimal("1.1223"));

        assertThat(c.rate()).isEqualByComparingTo("-0.04");
        assertThat(c.price()).isEqualByComparingTo("-0.0005");
    }

    @Test
    @DisplayName("10 미만 가격은 소수 넷째 자리까지 — 1.1218 을 1.12 로 자르지 않는다")
    void priceScaleFollowsMagnitude() {
        assertThat(GlobalFuturesService.priceScale(new BigDecimal("1.1218"))).isEqualTo(4);
        assertThat(GlobalFuturesService.priceScale(new BigDecimal("6.6505"))).isEqualTo(4);
        assertThat(GlobalFuturesService.priceScale(new BigDecimal("15.52"))).isEqualTo(2);
        assertThat(GlobalFuturesService.priceScale(new BigDecimal("7834.50"))).isEqualTo(2);
    }

    @Test
    @DisplayName("큰 가격은 종전과 같다 — S&P500 7,826.25 → 7,834.50 은 +8.25 · +0.11%")
    void largePriceUnchanged() {
        GlobalFuturesService.Change c = GlobalFuturesService.changeOf(new BigDecimal("7834.50"), new BigDecimal("7826.25"));

        assertThat(c.price()).isEqualByComparingTo("8.25");
        assertThat(c.rate()).isEqualByComparingTo("0.11");
    }

    @Test
    @DisplayName("전일 종가가 없으면 등락은 모름(null) — 0 으로 채우지 않는다")
    void missingPreviousCloseIsUnknown() {
        GlobalFuturesService.Change c = GlobalFuturesService.changeOf(new BigDecimal("102.16"), null);

        assertThat(c.price()).isNull();
        assertThat(c.rate()).isNull();
    }
}
