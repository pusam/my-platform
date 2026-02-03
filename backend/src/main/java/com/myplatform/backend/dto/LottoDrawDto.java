package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 로또 당첨 회차 정보 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LottoDrawDto {

    private Integer drawNo;         // 회차
    private LocalDate drawDate;     // 추첨일
    private List<Integer> numbers;  // 당첨 번호 (6개)
    private Integer bonusNo;        // 보너스 번호
    private Long totalPrize;        // 1등 총 당첨금
    private Long firstWinnerCount;  // 1등 당첨자 수
    private Long firstWinAmount;    // 1등 1인당 당첨금
}
