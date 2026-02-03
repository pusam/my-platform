package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 연금복권 720+ 추천 번호 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PensionLotteryRecommendationDto {

    private Integer gameNo;              // 게임 번호 (1~5)
    private Integer group;               // 조 번호 (1~5)
    private String number;               // 6자리 번호
    private List<Integer> digits;        // 각 자리 숫자 리스트
    private String strategy;             // 적용된 전략 설명
    private Double confidence;           // 신뢰도 점수 (0~100)

    // 분석 정보
    private String oddEvenPattern;       // 홀짝 패턴 (예: "홀짝홀짝홀짝")
    private String highLowPattern;       // 고저 패턴 (0-4: 저, 5-9: 고)
    private Integer digitSum;            // 각 자리 합계
}
