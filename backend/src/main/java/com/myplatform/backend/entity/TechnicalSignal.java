package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 기술적 지표 매매 신호 엔티티
 * - RSI, 이동평균선, MACD 등의 기술적 지표 계산 결과
 * - 매매 신호 발생 시 저장
 */
@Entity
@Table(name = "technical_signals", indexes = {
    @Index(name = "idx_stock_code_signal", columnList = "stockCode, signalType"),
    @Index(name = "idx_created_at", columnList = "createdAt"),
    @Index(name = "idx_signal_strength", columnList = "signalStrength")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TechnicalSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String stockCode;

    @Column(nullable = false, length = 100)
    private String stockName;

    @Column(precision = 15, scale = 2)
    private BigDecimal currentPrice;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private SignalType signalType; // RSI_OVERSOLD, RSI_OVERBOUGHT, GOLDEN_CROSS, DEAD_CROSS, MACD_BULLISH, MACD_BEARISH

    @Column(nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private SignalDirection direction; // BUY, SELL, NEUTRAL

    @Column(precision = 10, scale = 2)
    private BigDecimal signalStrength; // 신호 강도 (0-100)

    // 기술적 지표 값
    @Column(precision = 10, scale = 2)
    private BigDecimal rsi; // RSI 값

    @Column(precision = 15, scale = 2)
    private BigDecimal sma5; // 5일 이동평균선

    @Column(precision = 15, scale = 2)
    private BigDecimal sma20; // 20일 이동평균선

    @Column(precision = 15, scale = 2)
    private BigDecimal sma60; // 60일 이동평균선

    @Column(precision = 10, scale = 2)
    private BigDecimal macd; // MACD 값

    @Column(precision = 10, scale = 2)
    private BigDecimal macdSignal; // MACD Signal 값

    @Column(precision = 10, scale = 2)
    private BigDecimal macdHistogram; // MACD Histogram

    @Column(precision = 15, scale = 2)
    private BigDecimal volume; // 거래량

    @Column(precision = 10, scale = 2)
    private BigDecimal volumeRatio; // 거래량 비율 (평균 대비)

    @Column(length = 500)
    private String signalDescription; // 신호 설명

    @Column
    private Boolean isActive; // 활성 신호 여부

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    public enum SignalType {
        RSI_OVERSOLD,      // RSI 과매도 (< 30)
        RSI_OVERBOUGHT,    // RSI 과매수 (> 70)
        GOLDEN_CROSS,      // 골든크로스 (단기 이평선이 장기 이평선 돌파)
        DEAD_CROSS,        // 데드크로스 (단기 이평선이 장기 이평선 하향 돌파)
        MACD_BULLISH,      // MACD 상승 신호
        MACD_BEARISH,      // MACD 하락 신호
        BOLLINGER_LOWER,   // 볼린저밴드 하단 터치
        BOLLINGER_UPPER,   // 볼린저밴드 상단 터치
        VOLUME_SPIKE       // 거래량 급증
    }

    public enum SignalDirection {
        BUY,      // 매수 신호
        SELL,     // 매도 신호
        NEUTRAL   // 중립
    }
}

