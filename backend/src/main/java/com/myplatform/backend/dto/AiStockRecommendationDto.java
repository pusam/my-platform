package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiStockRecommendationDto {

    private String stockCode;
    private String stockName;
    private BigDecimal currentPrice;
    private BigDecimal changeRate;
    private BigDecimal changePrice;

    // AI 점수 (0-100)
    private Integer shortTermScore;      // 단기 예측 점수
    private Integer longTermScore;       // 중장기 예측 점수
    private Integer totalScore;          // 종합 점수

    // AI 의견
    private String opinion;              // 매수, 풀매수, 관망, 매도 등
    private String opinionClass;         // buy, strong-buy, hold, sell (CSS 클래스용)

    // AI 코멘트
    private String aiSummary;            // AI가 생성한 분석 요약
    private List<String> buyReasons;     // 매수 근거
    private List<String> riskFactors;    // 리스크 요인

    // 세부 지표
    private ScoreDetails scoreDetails;

    // 타입 (단기/중장기)
    private String type;                 // short, long

    private LocalDateTime analyzedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoreDetails {
        // 기술적 분석 점수
        private Integer rsiScore;           // RSI 점수 (과매도 시 높음)
        private Integer macdScore;          // MACD 신호 점수
        private Integer vwapScore;          // VWAP 대비 위치 점수
        private Integer bollingerScore;     // 볼린저밴드 점수

        // 수급 분석 점수
        private Integer foreignerScore;     // 외국인 순매수 점수
        private Integer institutionScore;   // 기관 순매수 점수
        private Integer consecutiveBuyScore;// 연속 매수일 점수

        // 기본적 분석 점수
        private Integer valuationScore;     // 밸류에이션 점수 (PER, PBR)
        private Integer profitabilityScore; // 수익성 점수 (영업이익률)
        private Integer growthScore;        // 성장성 점수

        // 원본 데이터
        private Double rsi;
        private Double vwapDiff;            // VWAP 대비 %
        private Integer foreignerConsecutiveDays;
        private Integer institutionConsecutiveDays;
        private Long foreignerNetBuy;       // 외국인 순매수 금액
        private Long institutionNetBuy;     // 기관 순매수 금액
        private Double operatingMargin;     // 영업이익률
        private Double per;
        private Double pbr;
    }
}
