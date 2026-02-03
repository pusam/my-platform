package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 로또 추천 번호 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LottoRecommendationDto {

    private Integer gameNo;              // 게임 번호 (1~5)
    private List<Integer> numbers;       // 추천 번호 6개
    private Integer sum;                 // 번호 합계
    private String oddEvenRatio;         // 홀짝 비율 (예: "3:3")
    private String highLowRatio;         // 고저 비율 (예: "3:3")
    private Integer consecutiveCount;    // 연속 번호 개수
    private String strategy;             // 적용된 전략 설명
    private Double confidence;           // 신뢰도 점수 (0~100)
}
