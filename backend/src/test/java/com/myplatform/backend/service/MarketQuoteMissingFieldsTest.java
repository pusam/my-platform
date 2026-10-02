package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 해외 시세·원유·추천 스냅샷 — 응답에 없는 값을 그럴듯한 값으로 채우지 않는다(2026-10-02 화면 재점검).
 */
class MarketQuoteMissingFieldsTest {

    @Test
    @DisplayName("재현: Yahoo 가 VIX·미국채 금리에 고가/저가 0 을 줘 시세표에 '0.00' 이 찍혔다 — 0 은 모름(null)")
    void zeroHighLowIsMissing() {
        assertThat(GlobalFuturesService.zeroAsMissing(BigDecimal.ZERO)).isNull();
        assertThat(GlobalFuturesService.zeroAsMissing(new BigDecimal("0.00"))).isNull();
        assertThat(GlobalFuturesService.zeroAsMissing(null)).isNull();
        assertThat(GlobalFuturesService.zeroAsMissing(new BigDecimal("16.82"))).isEqualByComparingTo("16.82");
        // 음수 가격은 실재할 수 있다(2020 WTI) — 0 만 결측
        assertThat(GlobalFuturesService.zeroAsMissing(new BigDecimal("-37.63"))).isEqualByComparingTo("-37.63");
    }

    @Test
    @DisplayName("재현: 원유 원화 환산이 고정 1,350원이었다 — 실시간 USD/KRW 로, 모르면 환산하지 않는다")
    void oilKrwUsesLiveRateOrNothing() {
        // 10/2 15:47 실측: WTI $91.89 · USD/KRW 1,351.48
        assertThat(OilPriceService.toKrw(new BigDecimal("91.89"), new BigDecimal("1351.48"))).isEqualByComparingTo("124187");   // 91.89 × 1,351.48 = 124,187.497
        assertThat(OilPriceService.toKrw(new BigDecimal("91.89"), null)).isNull();
        assertThat(OilPriceService.toKrw(new BigDecimal("91.89"), BigDecimal.ZERO)).isNull();
        assertThat(OilPriceService.toKrw(null, new BigDecimal("1351.48"))).isNull();
    }

    @Test
    @DisplayName("재현: DB 스냅샷 폴백 라벨이 장중 14:00 스냅샷에도 '(종가)'였다 — 스냅샷 시각만 적는다")
    void snapshotLabelDoesNotClaimClose() {
        String label = RecommendationService.snapshotTimeLabel(LocalDateTime.of(2026, 10, 2, 14, 0));

        assertThat(label).isEqualTo("10/02 14:00 스냅샷 기준");
        assertThat(label).doesNotContain("종가");
        assertThat(RecommendationService.snapshotTimeLabel(null)).isEqualTo("이전 데이터");
    }
}
