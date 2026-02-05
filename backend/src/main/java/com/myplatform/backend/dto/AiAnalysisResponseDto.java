package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAnalysisResponseDto {

    // 단기 TOP PICK
    private List<AiStockRecommendationDto> shortTermPicks;

    // 중장기 TOP PICK
    private List<AiStockRecommendationDto> longTermPicks;

    // 시장 지표
    private MarketIndicators marketIndicators;

    // 분석 시간
    private LocalDateTime analyzedAt;

    // AI 4대장 앙상블 정보
    private AiEnsembleInfo ensembleInfo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MarketIndicators {
        private Double kospiIndex;
        private Double kospiChange;
        private Double kosdaqIndex;
        private Double kosdaqChange;
        private Double adr;                 // ADR (등락 비율)
        private String marketSentiment;     // 과열, 정상, 침체
        private Double fearGreedIndex;      // 공포/탐욕 지수 (0-100)
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiEnsembleInfo {
        private Integer gptScore;           // GPT 예측 점수
        private Integer claudeScore;        // Claude 예측 점수
        private Integer geminiScore;        // Gemini 예측 점수
        private Integer deepseekScore;      // Deepseek 예측 점수
        private Integer consensusScore;     // 합의 점수
        private String consensusOpinion;    // 합의 의견
    }
}
