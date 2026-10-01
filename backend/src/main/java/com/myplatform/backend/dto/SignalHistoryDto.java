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
 *
 * <p><b>2026-10-01</b>: ① 추천 신호(STRONG_BUY/BUY)만 — 무작위 대조군·급등 감지·AI 행은 이 종목의 추천 성적이 아니다.
 * ② 결과는 교정 D+3 값(기록 시점 가격 → D+3 KRX 종가). ③ 요약은 <b>현재 산식 표본</b>(경계 이후)만 —
 * 경계 이전 행은 목록에 "이전 산식"으로만 남는다. 경계가 미정이면 요약은 비고 화면은 '검증 중'.
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
        /** 현재 산식 표본 시작일(경계). 미정이면 null — 요약은 비어 있다. */
        private LocalDate sampleSince;
        /** 경계 상태 — UNSET · PROVISIONAL · CONFIRMED. */
        private String sampleStatus;
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
        /** 교정 D+3 미평가(3거래일 미도래·평가 전) = 평가 대기. */
        private boolean pending;
        /** 교정 D+3 알파·수익률 — 평가 완료(OK) 행만. */
        private BigDecimal alpha3d;
        private BigDecimal pctChange3d;
        /** 교정 평가에서 제외된 사유(거래정지·봉 결측 등 d3 상태 코드). OK·대기 행은 null. */
        private String excludedReason;
        /** 현재 산식 표본에 드는가(경계 이후). false 면 "이전 산식" — 요약에 안 들어간다. */
        private boolean inCurrentSample;
    }
}
