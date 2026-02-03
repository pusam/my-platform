package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 연금복권 720+ 분석 결과 종합 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PensionLotteryAnalysisDto {

    // 기본 정보
    private Integer latestDrawNo;           // 최신 회차
    private Integer analyzedDrawCount;      // 분석된 회차 수
    private LocalDateTime analysisTime;     // 분석 시간

    // 추천 번호 (5게임)
    private List<PensionLotteryRecommendationDto> recommendations;

    // 조 번호 통계 (1~5)
    private Map<Integer, GroupStatDto> groupStats;

    // 자리별 숫자 통계 (0~9)
    private List<DigitStatDto> digitStats;

    // Hot/Cold 숫자
    private List<DigitStatDto> hotDigits;
    private List<DigitStatDto> coldDigits;

    // 통계 정보
    private StatisticsSummaryDto statistics;

    /**
     * 조 번호 통계 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupStatDto {
        private Integer group;              // 조 번호 (1~5)
        private Integer frequency;          // 출현 횟수
        private Double percentage;          // 출현 비율
        private Integer lastAppearance;     // 마지막 출현 (N회차 전)
    }

    /**
     * 자리별 숫자 통계 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DigitStatDto {
        private Integer position;           // 자리 위치 (1~6)
        private Integer digit;              // 숫자 (0~9)
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
        private Map<Integer, Integer> groupDistribution;     // 조 번호 분포
        private Map<String, Integer> oddEvenDistribution;    // 홀짝 패턴 분포
        private Double avgDigitSum;                          // 평균 자리수 합계
        private Map<Integer, Map<Integer, Integer>> positionDigitFrequency;  // 자리별 숫자 빈도
    }
}
