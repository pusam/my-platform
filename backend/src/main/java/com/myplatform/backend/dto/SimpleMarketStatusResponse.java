package com.myplatform.backend.dto;

import com.myplatform.backend.dto.MarketTimingDto.MarketCondition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 간단 시장 상태 응답 DTO (대시보드 위젯용)
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SimpleMarketStatusResponse {

    private LocalDate analysisDate;
    private BigDecimal combinedAdr;
    private MarketCondition condition;
    private String conditionEmoji;
    private String diagnosis;

    private BigDecimal kospiIndex;
    private BigDecimal kospiChange;
    private BigDecimal kosdaqIndex;
    private BigDecimal kosdaqChange;
}
