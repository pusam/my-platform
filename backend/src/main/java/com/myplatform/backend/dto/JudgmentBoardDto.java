package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 종합 판단 보드 (B안, 2026-06-30) — 매수후보를 신뢰도 3계층 신호로 나란히 비교.
 *
 * <p><b>원칙</b>: 종합점수 산식은 건드리지 않고 <b>조립·표시 전용</b>. 컬럼은 신뢰도 3계층으로 분리한다:
 * <ul>
 *   <li>① 점수(검증/게이트): totalScore·technical·earnings·sectorMomentum — 종합점수에 이미 반영된 것.
 *   <li>② 참고(미검증): timingScore·sectorStrengthRel·(헤더)overnight — <b>점수 미편입</b>, unverified.
 *   <li>③ 경고(역상관 의심): supplyDemand — P1-6 진단상 고점일수록 적중률↓(단 n=88, <b>의심·표본작음</b>이지 확정 아님).
 * </ul>
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class JudgmentBoardDto {

    private Market market;          // 전 행 공통 컨텍스트(헤더)
    private List<Row> rows;
    private boolean timingAvailable;          // 차트타이밍 python 가용(빈 결과가 '신호없음'인지 '다운'인지 구분)
    private boolean sectorStrengthAvailable;
    private String scope;           // "momentum"(기본) | "union"(발굴 트랙 포함)
    private UnionStats unionStats;  // union scope 일 때 "—"(미채점) 비율 가시화
    private String note;            // 미검증/역상관 의심 안내 문구

    /** union 4-cat 채움 현황 — 순수 발굴주(momentum scoreMap 밖)가 얼마나 "—"로 뜨는지. */
    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class UnionStats {
        private int totalRows;
        private int scoredRows;      // 4-cat 있음(momentum scoreMap 포함)
        private int unscoredRows;    // "—"(순수 발굴주, momentum 신호 없음)
    }

    /** 시장 컨텍스트 — regime(KOSPI MA60) + 간밤 미국장 tilt. 둘 다 점수 미편입(맥락 표시). */
    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Market {
        private String regime;                 // BULL/BEAR/SIDEWAYS 또는 null(미수집)
        private String overnightTilt;          // BULL/NEUTRAL/BEAR 또는 null
        private List<String> overnightDrivers; // S&P/나스닥/SOX/VIX 요약
    }

    @Getter
    @Setter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Row {
        private String stockCode, stockName;
        private BigDecimal currentPrice, changeRate;
        private List<String> sources;          // 출처 태그 — momentum/value/growth/oversold/earnings/smartmoney(dedup 후 전부).
        private boolean scored;                // true=4-cat 있음(momentum scoreMap). false="—"(순수 발굴주 — 출처 태그로 맥락).

        // ① 점수(검증/게이트)
        private int totalScore;
        private int technical;                 // ✅ 예측력 검증(P1-6: 강세 57%>약세 43%)
        private int earnings;                  // 게이트 + 약변별
        private int sectorMomentum;            // 테마(AI) 주도 — 고점(14) 예측력

        // ③ 경고(역상관 의심)
        private int supplyDemand;
        private boolean supplyInverseSuspect;  // 고점=혼잡 의심(표본 작음, 확정 아님)

        // ② 참고(미검증 — 점수 미편입)
        private Integer timingScore;           // null=신호없음/미산출
        private String sector;                 // 섹터명
        private BigDecimal sectorStrengthRel;  // 섹터 상대강도(null=미가용)
        // 연속 순매수일(② 참고): streak5 백테스트 약한 양(+) 신호 — 참고 톤, 산식 미편입 unverified.
        // null=데이터 5거래일 미만(§4c) / 0=최신일 순매수 아님. investor_daily_trade top-N IN절 일괄.
        private Integer foreignBuyStreak;
        private Integer institutionBuyStreak;

        // 매매 맥락(표시 전용 — 산식 미편입)
        // 재료(§4b): 최근 N일 중 최신 read(신규 분류 안 함). null/NONE=생략.
        private String catalystType;           // ORDER_WIN/EARNINGS/... 또는 null
        private String catalystLabel;          // 수주/실적/M&A/... 또는 null
        private String catalystDirection;      // POSITIVE/NEGATIVE/NEUTRAL 또는 null
        private Integer catalystAgeDays;       // 0=오늘 / 1=어제 (§4c: 낡음 위장 방지 — 프론트가 경과 표기)
        // 거래대금(§4c): 실측 누적 or 현재가×거래량 폴백, 둘 다 없으면 null(임시값 생성 금지).
        private BigDecimal tradingValue;
        // RVOL(V41, ② 참고 계층): 당일 거래대금 ÷ 직전 20거래일 평균. null=미산출(20일 미만·캐시 미스, §4c).
        // 미검증·랭킹/산식 미편입 — 배지 표시 전용.
        private BigDecimal rvol;
        // 신호 이력 실적(② 참고 계층): signal_outcome 최근 90일 평가 완료분 집계 — 표시 전용, 산식 미편입.
        // null=이력 없음(§4c). n<3 은 프론트가 "—" muted(표본부족 톤) + 정렬 하단.
        private Integer trackCount;        // 평가 완료 표본 수
        private Integer trackHitCount;
        private BigDecimal trackAvgAlpha;  // alpha 있는 행 평균. null=미산출

        private List<String> tags;
    }
}
