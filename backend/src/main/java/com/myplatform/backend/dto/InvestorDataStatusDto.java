package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 투자자 매매 데이터 수집 상태
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InvestorDataStatusDto {

    private int foreignTradeDays;
    private LocalDate foreignLatestDate;
    private LocalDate foreignOldestDate;

    private int institutionTradeDays;
    private LocalDate institutionLatestDate;

    private LocalDate latestTradeDate;

    private boolean hasEnoughData;
    private String message;
}
