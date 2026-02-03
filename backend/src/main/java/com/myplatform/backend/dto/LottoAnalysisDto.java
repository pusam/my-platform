package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 로또 분석 결과 종합 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LottoAnalysisDto {

    // 기본 정보
    private Integer latestDrawNo;           // 최신 회차
    private Integer analyzedDrawCount;      // 분석된 회차 수
    private LocalDateTime analysisTime;     // 분석 시간

    // 추천 번호 (5게임)
    private List<LottoRecommendationDto> recommendations;

    // 번호별 출현 빈도 (1~45)
    private Map<Integer, NumberStatDto> numberStats;

    // Hot Numbers (최근 자주 나온 번호)
    private List<NumberStatDto> hotNumbers;

    // Cold Numbers (오랫동안 안 나온 번호)
    private List<NumberStatDto> coldNumbers;

    // 통계 정보
    private StatisticsSummaryDto statistics;

    /**
     * 번호별 통계 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NumberStatDto {
        private Integer number;             // 번호
        private Integer frequency10;        // 최근 10회 출현 횟수
        private Integer frequency50;        // 최근 50회 출현 횟수
        private Integer frequency100;       // 최근 100회 출현 횟수
        private Integer lastAppearance;     // 마지막 출현 (N회차 전)
        private Double weight;              // 가중치 점수
        private String category;            // HOT / WARM / COLD
    }

    /**
     * 통계 요약 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatisticsSummaryDto {
        private Double avgSum;              // 평균 합계
        private Integer minSum;             // 최소 합계
        private Integer maxSum;             // 최대 합계
        private Map<String, Integer> oddEvenDistribution;   // 홀짝 분포
        private Map<String, Integer> highLowDistribution;   // 고저 분포
        private Double avgConsecutive;      // 평균 연속 번호 개수
    }
}
