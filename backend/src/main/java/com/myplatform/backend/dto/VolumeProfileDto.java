package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Volume Profile — 가격대별 누적 거래량.
 *
 * 트레이더가 "어느 가격대에서 거래가 많이 일어났는가" 를 시각적으로 보고
 * 향후 지지/저항 가능성을 판단하는 데 사용.
 *
 * - POC (Point of Control): 가장 거래량이 많은 가격대 (가장 강한 가격)
 * - VAH (Value Area High) / VAL (Value Area Low): 누적 70% 거래가 일어난 구간
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VolumeProfileDto {

    private BigDecimal priceMin;
    private BigDecimal priceMax;
    /** 가격 낮은 → 높은 순 정렬된 bin 리스트 */
    private List<Bin> bins;
    /** Point of Control — 최다 거래 가격대 (bin 중간 가격) */
    private BigDecimal poc;
    /** Value Area High — 누적 70% 거래 영역의 상한 */
    private BigDecimal vah;
    /** Value Area Low — 하한 */
    private BigDecimal val;
    private long totalVolume;
    /** 분석 일수 (예: 90일) */
    private int periodDays;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Bin {
        private BigDecimal priceLow;
        private BigDecimal priceHigh;
        private long volume;
        /** 전체 대비 % (0~100) */
        private double volumePct;
    }
}
