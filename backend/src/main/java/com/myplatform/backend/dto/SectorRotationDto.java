package com.myplatform.backend.dto;

import lombok.*;

import java.math.BigDecimal;

/**
 * 섹터 로테이션 감지 DTO - 전일 대비 자금 흐름 변화
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SectorRotationDto {
    private String sectorCode;              // 섹터 코드
    private String sectorName;              // 섹터 이름
    private BigDecimal todayTradingValue;    // 오늘 거래대금 (억)
    private BigDecimal yesterdayTradingValue; // 어제 거래대금 (억)
    private BigDecimal changeRate;           // 변화율 (%)
    private BigDecimal changeAmount;         // 변화액 (억)
    private String flowDirection;            // INFLOW, OUTFLOW, NEUTRAL
    private BigDecimal avgChangeRate;        // 섹터 평균 등락률 (⚠ 상위 5개 종목 평균 — 주도주 표시용, 상방 편향)
    // 섹터 전 종목 raw 평균 등락률 — regime(BULL/BEAR) 판정용. avgChangeRate(상위 5개)는 하락장에서도
    // 양수가 나와 regime 을 BULL 로 끌어올리는 상방 편향이 있어(2026-07-28) 판정 입력을 분리.
    private BigDecimal rawAvgChangeRate;
}
