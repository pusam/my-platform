package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 외국인/기관 수급 급증 종목 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestorSurgeDto {

    private String stockCode;
    private String stockName;
    private String investorType;
    private String investorTypeName;

    private LocalTime snapshotTime;        // 스냅샷 시간

    private BigDecimal netBuyAmount;       // 현재 순매수 금액 (억원)
    private BigDecimal amountChange;       // 변화량 (억원)
    private Double changePercent;          // 변화율 (%)

    private Integer currentRank;           // 현재 순위
    private Integer rankChange;            // 순위 변화 (음수: 상승)

    private BigDecimal currentPrice;       // 현재가
    private BigDecimal changeRate;         // 등락률

    private String surgeLevel;             // 급등 레벨: HOT, WARM, NORMAL

    /**
     * 추세 상태 (누적금액 + 변화량 조합)
     * - ACCUMULATING: 누적 양수 + 변화 양수 → 초록색 '매수 집중' (★최고의 매수 타이밍)
     * - PROFIT_TAKING: 누적 양수 + 변화 음수 → 주황색 '차익 실현'
     * - TURNAROUND: 누적 음수 + 변화 양수 → 회색 '수급 유입' (반등 시도)
     * - NORMAL: 그 외 케이스
     */
    private String trendStatus;
    private String trendStatusName;        // 추세 상태 한글명

    // 공통 종목용 필드
    private BigDecimal foreignNetBuy;      // 외국인 순매수 금액 (억원)
    private BigDecimal institutionNetBuy;  // 기관 순매수 금액 (억원)
}
