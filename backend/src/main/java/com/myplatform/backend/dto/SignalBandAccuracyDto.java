package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 조건부 적중률 — 점수 구간별 + 카테고리 강세별.
 *
 * 기존 /accuracy 가 시그널 타입(STRONG_BUY/BUY) 전체 평균만 주는 것을 보완:
 *  - bands: signalScore 구간별 (55~64 / 65~74 / 75~84 / 85~100) 적중률.
 *    "75점과 90점의 적중률이 실제로 다른가" 검증용.
 *  - categories: 시그널 시점 카테고리 점수가 강세(≥15)였던 표본의 적중률.
 *    "수급 주도 추천 vs 기술 주도 추천 중 뭐가 먹혔나" 검증용. V30 컬럼 누적분만 집계.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalBandAccuracyDto {

    private int daysWindow;
    private List<BandStat> bands;
    private List<CategoryStat> categories;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BandStat {
        /** 표시 라벨 — "75~84" 등. */
        private String band;
        private int scoreFrom;
        private int scoreTo;
        private long totalSignals;
        private long hitCount;
        private BigDecimal hitRate;
        private BigDecimal avgPctChange;
        private BigDecimal avgAlpha;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryStat {
        /** earnings / supplyDemand / technical / sectorMomentum. */
        private String key;
        private String label;
        /** 강세 판정 임계 (점수 ≥ threshold 표본만 집계). */
        private int strongThreshold;
        private long totalSignals;
        private long hitCount;
        private BigDecimal hitRate;
        private BigDecimal avgPctChange;
    }
}
