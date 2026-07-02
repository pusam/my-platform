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
    /**
     * 실제 집계 시작일 — 요청 창과 phase-38 컷오프(2026-06-25, anti-추격 튜닝 완료) 중 늦은 쪽.
     * 보드 종합점수(STRONG_BUY/BUY)만 격리해 "현재 산식 점수" 예측력만 측정. UI 라벨용("6/25부터, 보드 기준").
     */
    private java.time.LocalDate since;
    private List<BandStat> bands;
    private List<CategoryStat> categories;
    /**
     * 재료 방향별 적중률 (V31) — 호재/악재/중립/재료없음 표본별.
     * "재료 있는 추천이 실제로 더 먹히는지" 검증용. 미수집(NULL) 행은 제외.
     */
    private List<CatalystStat> catalysts;

    /**
     * 시장 국면별 적중률 (V32) — 상승장/하락장/횡보장 표본별.
     * "하락장에서도 이 추천이 먹히는지" 검증용. 미수집(NULL) 행은 제외.
     */
    private List<RegimeStat> regimes;

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
    public static class RegimeStat {
        /** BULL / BEAR / SIDEWAYS. */
        private String regime;
        /** 상승장 / 하락장 / 횡보장. */
        private String label;
        private long totalSignals;
        private long hitCount;
        private BigDecimal hitRate;
        private BigDecimal avgPctChange;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CatalystStat {
        /** POSITIVE / NEGATIVE / NEUTRAL / NONE. */
        private String direction;
        /** 호재 / 악재 / 중립 / 재료없음. */
        private String label;
        private long totalSignals;
        private long hitCount;
        private BigDecimal hitRate;
        private BigDecimal avgPctChange;
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
