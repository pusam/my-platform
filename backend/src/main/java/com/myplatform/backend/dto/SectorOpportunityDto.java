package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 섹터 기회 발굴 DTO
 *
 * 주도 섹터를 먼저 고르고, 그 안에서 유망 종목을 추려 묶은 결과.
 * 대시보드 "수급 들어오는 섹터의 유망 종목" 위젯 용도.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SectorOpportunityDto {

    private String sectorCode;
    private String sectorName;
    private BigDecimal sectorAverageChangeRate;   // 섹터 평균 등락률 (%)
    private int sectorStockCount;                  // 섹터 내 종목 수
    private String leadingStockCode;               // 섹터 대장주
    private String leadingStockName;
    private List<StockPick> picks;                 // 섹터 내 유망 종목 TOP N
    private LocalDateTime calculatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StockPick {
        private int rank;
        private String stockCode;
        private String stockName;
        private BigDecimal currentPrice;
        private BigDecimal changeRate;         // 등락률 (%)
        private BigDecimal foreignNetBuy;      // 외국인 순매수 (억) — 없으면 null
        private BigDecimal institutionNetBuy;  // 기관 순매수 (억) — 없으면 null
        private int opportunityScore;          // 종합 매력도 (0~100)
        private List<String> reasons;          // 점수 근거 — "외인 +12억", "RSI 양호" 등
    }
}
