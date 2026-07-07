package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 종목별 신호 이력 실적 (signal_outcome 최근 90일 재사용) — <b>표시 전용, 산식 미편입</b>.
 *
 * <p>종목 상세 "📜 신호 이력" 섹션 입력. 이 종목에 과거 STRONG_BUY/BUY 시그널이 언제 떴고
 * 실제로 맞았는지(3거래일 hit/alpha)를 타임라인으로 보여준다.
 *
 * <p><b>§4c</b>: 평가 전(3거래일 미도래) 행은 pending=true 로 구분 — 미평가를 미스(hit=false)로
 * 위장하지 않는다. 요약(evaluatedCount/hitCount/avgAlpha)은 평가 완료 행만 집계.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SignalHistoryDto {

    private String stockCode;
    /** 집계 윈도우(일) — 90. */
    private int windowDays;
    private Summary summary;
    /** 최신순 이력 (pending 포함). 비어 있으면 프론트는 섹션 자체 미렌더. */
    private List<Item> items;

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Summary {
        /** 평가 완료 표본 수. */
        private int evaluatedCount;
        private int hitCount;
        /** 평가 완료 행 중 alpha 있는 것의 평균(scale 2). 표본 없으면 null(§4c). */
        private BigDecimal avgAlpha;
        /** 평가 대기(3거래일 미도래) 건수 — 요약 집계 밖, 별도 표기. */
        private int pendingCount;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Item {
        private LocalDate signalDate;
        private String signalType;
        private Integer signalScore;
        /** true=적중 / false=미적중 / null=평가 대기(pending). */
        private Boolean hit;
        /** evaluated_at 미기록 = 평가 대기. */
        private boolean pending;
        private BigDecimal alpha3d;
        private BigDecimal pctChange3d;
    }
}
