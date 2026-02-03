package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 연금복권 720+ 당첨 회차 정보 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PensionLotteryDrawDto {

    private Integer drawNo;           // 회차
    private LocalDate drawDate;       // 추첨일

    // 1등 당첨번호
    private Integer firstGroup;       // 1등 조 번호 (1~5)
    private String firstNumber;       // 1등 6자리 번호

    // 보너스 당첨번호 (각 자리)
    private Integer bonusGroup;       // 보너스 조 번호
    private String bonusNumber;       // 보너스 6자리 번호
}
