package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * stock_master 캐시 현황
 * — 관리자 디버그용 (legacy raw-Map 패턴 유지: success 포함)
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockMasterStatusResponse {

    private boolean success;
    private int cachedCount;
    private long lastSeedEpochSeconds;
}
