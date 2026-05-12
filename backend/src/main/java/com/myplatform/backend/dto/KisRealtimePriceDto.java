package com.myplatform.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * KIS WebSocket 실시간 시세 (TR=H0STCNT0) 한 틱.
 *
 * KIS 응답 raw 포맷 (^ 구분):
 *   체결시간|현재가|전일대비|등락률|전일대비기호|전일종가|시가|고가|저가|매도호가|매수호가|
 *   체결량|누적거래량|누적거래대금|... (총 40+ 필드)
 *
 * 매매 트리거에 가장 자주 쓰는 핵심 필드만 모델링. 필요 시 KisWebSocketService 디코딩 매핑 확장.
 */
@Data
@Builder
public class KisRealtimePriceDto {
    private String stockCode;
    private String tradeTime;           // HHmmss
    private BigDecimal currentPrice;
    private BigDecimal changeAmount;    // 전일 대비
    private BigDecimal changeRate;      // 등락률 %
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal askPrice1;       // 최우선 매도호가
    private BigDecimal bidPrice1;       // 최우선 매수호가
    private long tradeVolume;           // 체결량
    private long accVolume;             // 누적 거래량
    private BigDecimal accTradeValue;   // 누적 거래대금 (원)
    private LocalDateTime receivedAt;   // 서버 수신 시각
}
