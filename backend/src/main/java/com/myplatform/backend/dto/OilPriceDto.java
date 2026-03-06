package com.myplatform.backend.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OilPriceDto {
    private BigDecimal pricePerBarrel;    // 1배럴 가격 (USD)
    private BigDecimal priceKrw;          // 원화 환산 (KRW)
    private BigDecimal openPrice;         // 시가
    private BigDecimal highPrice;         // 고가
    private BigDecimal lowPrice;          // 저가
    private BigDecimal closePrice;        // 종가
    private BigDecimal changePrice;       // 전일 대비
    private BigDecimal changeRate;        // 등락률 (%)
    private Long volume;                  // 거래량
    private String baseDate;              // 기준일
    private LocalDateTime baseDateTime;
    private LocalDateTime fetchedAt;
}
