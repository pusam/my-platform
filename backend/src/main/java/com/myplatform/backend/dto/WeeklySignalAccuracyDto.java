package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 시그널 예측력 주간 측정 리포트 (P1-6 측정 상설화) — 조회/텔레그램/영속화 공용 구조.
 *
 * <p>측정 축 = <b>카테고리(4) × regime(BULL/BEAR/SIDEWAYS/UNKNOWN) × 점수밴드</b>, 2D 파티션 방식:
 * regime 버킷별로 카테고리 stats + 밴드 stats 를 산출({@link RegimeGroup}). regime_at_signal NULL 은
 * <b>UNKNOWN 버킷으로 정직 분리</b>(pykrx 깨졌던 구간). 지표 = 적중률/평균 alpha_3d/표본수.
 *
 * <p><b>표본부족(§4c)</b>: 셀 n &lt; {@code INSUFFICIENT_SAMPLE_THRESHOLD}(10) 는 {@code insufficientSample=true}
 * 로 명시 — 숨기지 않고 n 을 함께 표기(그럴듯한 수치로 위장 금지).
 *
 * <p>추세 = {@link CategoryTrend} (이번 주 vs 누적 전체 델타, 악화 감지). {@link #warnings} 에 누적 경고
 * ("수급 역상관 지속 N주째" 등).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklySignalAccuracyDto {

    private LocalDate weekStart;
    private LocalDate weekEnd;
    private LocalDateTime generatedAt;

    /** 이번 주 평가완료 board(STRONG_BUY/BUY) 시그널 수. */
    private int weeklyN;
    /** phase-38 컷오프 이후 누적 board 시그널 수. */
    private int cumulativeN;

    /** 이번 주 grid — regime 파티션별 카테고리/밴드 stats. */
    private List<RegimeGroup> regimeGroups;

    /** 이번 주 grid — 변동성 국면(V46 NORMAL/HIGH_VOL) 파티션별 카테고리/밴드 stats. */
    private List<RegimeGroup> volRegimeGroups;

    /** 카테고리별 추세 — 이번 주 vs 누적 전체 (악화 감지). */
    private List<CategoryTrend> categoryTrends;

    /** 누적 경고 (수급 역상관 지속 주차·악화 감지·표본부족 안내 등). */
    private List<String> warnings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegimeGroup {
        /** BULL / BEAR / SIDEWAYS / UNKNOWN. */
        private String regime;
        /** 상승장 / 하락장 / 횡보장 / 국면 미수집. */
        private String label;
        /** 이 regime 버킷의 board 시그널 수. */
        private long totalSignals;
        /** 버킷 전체 표본부족(n<10) 여부. */
        private boolean insufficientSample;
        private List<CategoryCell> categories;
        private List<BandCell> bands;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryCell {
        private String key;
        private String label;
        private int strongThreshold;
        private long totalSignals;
        private long hitCount;
        private BigDecimal hitRate;
        private BigDecimal avgAlpha;
        private BigDecimal avgPctChange;
        /** n<10 표본부족(§4c) — hitRate/alpha 는 계산하되 신뢰 낮음 명시. */
        private boolean insufficientSample;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BandCell {
        private String band;
        private int scoreFrom;
        private int scoreTo;
        private long totalSignals;
        private long hitCount;
        private BigDecimal hitRate;
        private BigDecimal avgAlpha;
        private BigDecimal avgPctChange;
        private boolean insufficientSample;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryTrend {
        private String key;
        private String label;
        private long weeklyN;
        private long cumulativeN;
        private BigDecimal weeklyHitRate;
        private BigDecimal cumulativeHitRate;
        /** weeklyHitRate − cumulativeHitRate (양쪽 표본 충분할 때만 의미). null=계산 불가. */
        private BigDecimal hitRateDelta;
        private BigDecimal weeklyAvgAlpha;
        private BigDecimal cumulativeAvgAlpha;
        private BigDecimal avgAlphaDelta;
        private boolean weeklyInsufficient;
        private boolean cumulativeInsufficient;
        /** 악화 = 델타 음수 AND 양쪽 표본 충분. 표본 부족 구간은 false(위장 방지). */
        private boolean worsening;
    }
}
