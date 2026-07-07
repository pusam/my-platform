package com.myplatform.backend.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 수동 매매 저널 통계 (Phase 2) — 봇/시그널 적중률과 나란히 비교하는 "내 매매 성적표".
 *
 * <p>§4c: 표본 0건인 비율/평균은 null(0% 위장 금지). 전체·breakdown 각각
 * 평가 표본 n &lt; 10 이면 {@code insufficientSample=true} — 수치는 주되 신뢰 낮음을 명시.
 */
@Getter
@Builder
public class ManualJournalStatsDto {

    /** 전체 기록 수. */
    private long totalTrades;
    /** 보유 중(매도 전) 건수. */
    private long openTrades;
    /** 매도 확정 건수. */
    private long closedTrades;

    // ===== 3거래일 자동 평가 (signal_outcome 동일 잣대) =====
    /** 평가 완료 건수(evaluatedAt 채워진 것만 — 대기 건 제외). */
    private long evaluatedTrades;
    private long hitCount;
    /** 적중률 % — 평가 표본 0건이면 null(§4c). */
    private BigDecimal hitRate;
    /** 평균 alpha_3d % — alpha 수집분만, 0건이면 null. */
    private BigDecimal avgAlpha3d;
    /** 평균 pct_change_3d % — 0건이면 null. */
    private BigDecimal avgPctChange3d;

    // ===== 실현 손익 (매도 확정분) =====
    private long realizedTrades;
    private long realizedWinCount;
    /** 실현 승률 %(realizedPct &gt; 0 비율) — 표본 0건이면 null. */
    private BigDecimal realizedWinRate;
    /** 평균 실현 수익률 % — 표본 0건이면 null. */
    private BigDecimal avgRealizedPct;

    /** 평가 표본 n &lt; 10 — 통계 신뢰 낮음(§4c 구조만 제공 단계). */
    private boolean insufficientSample;

    /** 스냅샷 조건별 breakdown(RSI 과열 여부·재료 유무) — 구조 제공, n 작으면 flag. */
    private List<Breakdown> breakdowns;

    @Getter
    @Builder
    public static class Breakdown {
        /** 식별 키 (rsiOverbought / rsiNormal / catalystPresent / catalystAbsent). */
        private String key;
        private String label;
        /** 이 조건에 해당하는 전체 기록 수(평가 대기 포함). */
        private long totalTrades;
        /** 평가 완료 표본 수. */
        private long evaluatedTrades;
        private long hitCount;
        /** 적중률 % — 평가 표본 0건이면 null(§4c). */
        private BigDecimal hitRate;
        /** 평균 alpha_3d % — 0건이면 null. */
        private BigDecimal avgAlpha3d;
        /** 평가 표본 n &lt; 10. */
        private boolean insufficientSample;
    }
}
