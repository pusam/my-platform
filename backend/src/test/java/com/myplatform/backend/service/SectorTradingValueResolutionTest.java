package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockPriceDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 섹터 거래대금 스냅샷 값 결정 (점검 수정 2026-06-11) — 핵심 계약:
 *
 * <p>거래대금은 실측만 저장한다. 과거엔 휴장일에 실측이 없으면 시가총액×0.1% 또는
 * 현재가×10000 을 "임시 거래대금"으로 저장해 휴장일 섹터 랭킹이 사실상 시총 랭킹이
 * 되면서 실데이터처럼 보이는 문제가 있었음. 실측 없으면 null — 해당 종목은 스냅샷 제외.
 */
class SectorTradingValueResolutionTest {

    private StockPriceDto price(String accumulated, String current, String volume, String marketCap) {
        StockPriceDto dto = new StockPriceDto();
        if (accumulated != null) dto.setAccumulatedTradingValue(new BigDecimal(accumulated));
        if (current != null) dto.setCurrentPrice(new BigDecimal(current));
        if (volume != null) dto.setVolume(new BigDecimal(volume));
        if (marketCap != null) dto.setMarketCap(new BigDecimal(marketCap));
        return dto;
    }

    @Test
    @DisplayName("누적 거래대금 실측 있으면 그대로 사용")
    void resolve_usesAccumulatedValue() {
        BigDecimal v = SectorTradingService.resolveAccumulatedValue(
                price("5000000000", "70000", "100000", "400000000000"));

        assertThat(v).isEqualByComparingTo("5000000000");
    }

    @Test
    @DisplayName("누적 거래대금 없으면 현재가×거래량으로 계산 (실측 기반 폴백)")
    void resolve_fallsBackToPriceTimesVolume() {
        BigDecimal v = SectorTradingService.resolveAccumulatedValue(
                price(null, "70000", "100000", "400000000000"));

        assertThat(v).isEqualByComparingTo("7000000000");
    }

    @Test
    @DisplayName("실측 전무 — 시가총액이 있어도 거래대금으로 위장하지 않는다 (null)")
    void resolve_neverFabricatesFromMarketCap() {
        BigDecimal v = SectorTradingService.resolveAccumulatedValue(
                price(null, "70000", null, "400000000000"));

        assertThat(v).isNull(); // 과거 버그: 시총×0.001 또는 현재가×10000 반환
    }

    @Test
    @DisplayName("누적 0 + 거래량 0 → null (0 거래를 위장 금지)")
    void resolve_zeroVolumeIsNull() {
        BigDecimal v = SectorTradingService.resolveAccumulatedValue(
                price("0", "70000", "0", null));

        assertThat(v).isNull();
    }

    @Test
    @DisplayName("price null → null")
    void resolve_nullPrice() {
        assertThat(SectorTradingService.resolveAccumulatedValue(null)).isNull();
    }
}
