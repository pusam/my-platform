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

    /**
     * 추세 채널 상태별 적중률 (V49) — 방향(UP/DOWN/FLAT) × 위치 밴드(하단/중단/상단) 9칸.
     * "상승 채널 하단(눌림목) 매수가 실제로 먹히나 / 상단(추격)이 부진한가" 검증용.
     * 미수집(NULL) 행은 제외. 표본 유의 전 산식 편입 금지(P2-12 교훈).
     */
    private List<ChannelStat> channels;

    /**
     * KOSPI 지수(0001) 채널 상태별 적중률 (V50) — 방향 × 위치 밴드 9칸. "지수가 상승 채널 하단일 때(=조정 눌림)
     * 매수가 실제로 먹히나 / 상단(과열)이 부진한가" = regime v1(BULL/BEAR 이분법)이 못 보는 지수 '위치' 검증.
     * <b>⚠ 지수 축이라 같은 날 전 시그널이 동일값(서로 독립 아님)</b> — 유효 표본 판정은 {@code totalSignals}(행 수)
     * 가 아니라 {@code distinctDays}(고유 signal_date 수) 기준이다({@link IndexChannelStat#insufficientSample}).
     * 미수집(NULL) 행은 제외. 표본 유의 전 산식 편입 금지(P2-12 · P3-10).
     */
    private List<IndexChannelStat> indexChannels;

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
    public static class ChannelStat {
        /** UP / DOWN / FLAT. */
        private String direction;
        /** LOW(≤33) / MID(34~66) / HIGH(≥67). */
        private String positionBand;
        /** "상승채널 하단(≤33%)" 등 표시 라벨. */
        private String label;
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
    public static class IndexChannelStat {
        /** UP / DOWN / FLAT (KOSPI 지수 채널 방향). */
        private String direction;
        /** LOW(≤33) / MID(34~66) / HIGH(≥67). */
        private String positionBand;
        /** "지수 상승채널 하단(≤33%)" 등 표시 라벨. */
        private String label;
        private long totalSignals;
        private long hitCount;
        private BigDecimal hitRate;
        private BigDecimal avgPctChange;
        private BigDecimal avgAlpha;
        /**
         * 고유 signal_date 수 — <b>진짜 독립 표본 수</b>. 지수 축은 같은 날 전 시그널이 동일값이라
         * totalSignals(행 수)로 세면 표본을 과대평가한다(§4c). 유의 판정은 이 값으로 한다.
         */
        private long distinctDays;
        /** 유효 표본 부족 = {@code distinctDays < MIN_DISTINCT_DAYS}(10). 행 수가 아무리 많아도 며칠 안이면 true. */
        private boolean insufficientSample;
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
        /** 강세 표본 평균 alpha_3d (vs KOSPI). 주간 측정(P1-6 상설화) 지표. alpha 미수집 행은 제외. */
        private BigDecimal avgAlpha;
    }
}
