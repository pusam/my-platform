package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * stock_master KRX 시드 실행 결과
 * — 관리자 디버그용 (legacy raw-Map 패턴 유지: success/message 포함)
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockMasterSeedResponse {

    private boolean success;
    private Integer seeded;
    private Integer cachedBefore;
    private Integer cachedAfter;
    private String message;
}
