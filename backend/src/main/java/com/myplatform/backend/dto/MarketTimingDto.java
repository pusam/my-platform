package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 시장 타이밍 분석 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketTimingDto {

    /** 분석 기준일 */
    private LocalDate analysisDate;

    /** 코스피 시장 현황 */
    private MarketStatusDto kospi;

    /** 코스닥 시장 현황 */
    private MarketStatusDto kosdaq;

    /** 종합 시장 상태 */
    private MarketCondition overallCondition;

    /** 종합 ADR (코스피 + 코스닥 합산) */
    private BigDecimal combinedAdr;

    /** 시장 진단 메시지 */
    private String diagnosis;

    /** 투자 전략 제안 */
    private String strategy;

    /**
     * 개별 시장 현황
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MarketStatusDto {
        /** 시장 구분 */
        private String marketType;

        /** 거래일 */
        private LocalDate tradeDate;

        /** 상승 종목 수 */
        private Integer advancingCount;

        /** 하락 종목 수 */
        private Integer decliningCount;

        /** 보합 종목 수 */
        private Integer unchangedCount;

        /** 상한가 종목 수 */
        private Integer upperLimitCount;

        /** 하한가 종목 수 */
        private Integer lowerLimitCount;

        /** 당일 등락비 (상승/하락 * 100) */
        private BigDecimal dailyRatio;

        /** 20일 ADR */
        private BigDecimal adr20;

        /** 시장 상태 */
        private MarketCondition condition;

        /** 지수 종가 */
        private BigDecimal indexClose;

        /** 지수 등락률 */
        private BigDecimal indexChangeRate;

        /** 거래대금 (억원) */
        private BigDecimal tradingValue;
    }

    /**
     * 시장 상태 열거형
     */
    public enum MarketCondition {
        OVERHEATED("과열", "🔥 과열 (현금 확보 필요)",
                   "시장이 과열 상태입니다. 신규 매수보다 현금 비중 확대를 고려하세요."),
        NORMAL("보통", "☁️ 보통",
               "시장이 정상 범위입니다. 개별 종목 분석에 집중하세요."),
        OVERSOLD("침체", "💧 침체 (저점 매수 기회)",
                 "시장이 과매도 구간입니다. 우량주 분할 매수를 고려하세요."),
        EXTREME_FEAR("공포", "🥶 극심한 공포 (적극 매수 검토)",
                     "극심한 공포 구간입니다. 역발상 투자의 적기일 수 있습니다."),
        CRASH("폭락", "🚨 폭락/패닉 (관망 필수)",
              "시장이 폭락 중입니다. 신규 매수를 중단하고 하방 리스크 관리에 집중하세요.");

        private final String label;
        private final String emoji;
        private final String suggestion;

        MarketCondition(String label, String emoji, String suggestion) {
            this.label = label;
            this.emoji = emoji;
            this.suggestion = suggestion;
        }

        public String getLabel() {
            return label;
        }

        public String getEmoji() {
            return emoji;
        }

        public String getSuggestion() {
            return suggestion;
        }
    }

    /**
     * ADR 히스토리 (차트용)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdrHistoryDto {
        private LocalDate date;
        private BigDecimal kospiAdr;
        private BigDecimal kosdaqAdr;
        private BigDecimal combinedAdr;
    }
}
