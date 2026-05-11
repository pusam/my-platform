package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 기간별 시장 데이터 수집 결과
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MarketDataCollectionResult {

    private String startDate;
    private String endDate;
    private int successCount;
    private int failCount;
    private int skipCount;
    private long totalDays;
}
