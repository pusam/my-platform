package com.myplatform.backend.dto;

import com.myplatform.backend.entity.TechnicalSignal;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "기술적 지표 매매 신호")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TechnicalSignalDto {

    @Schema(description = "신호 ID")
    private Long id;

    @Schema(description = "종목 코드")
    private String stockCode;

    @Schema(description = "종목명")
    private String stockName;

    @Schema(description = "현재가")
    private BigDecimal currentPrice;

    @Schema(description = "신호 타입")
    private TechnicalSignal.SignalType signalType;

    @Schema(description = "신호 방향 (BUY/SELL/NEUTRAL)")
    private TechnicalSignal.SignalDirection direction;

    @Schema(description = "신호 강도 (0-100)")
    private BigDecimal signalStrength;

    @Schema(description = "RSI")
    private BigDecimal rsi;

    @Schema(description = "5일 이동평균선")
    private BigDecimal sma5;

    @Schema(description = "20일 이동평균선")
    private BigDecimal sma20;

    @Schema(description = "60일 이동평균선")
    private BigDecimal sma60;

    @Schema(description = "MACD")
    private BigDecimal macd;

    @Schema(description = "MACD Signal")
    private BigDecimal macdSignal;

    @Schema(description = "MACD Histogram")
    private BigDecimal macdHistogram;

    @Schema(description = "거래량")
    private BigDecimal volume;

    @Schema(description = "거래량 비율")
    private BigDecimal volumeRatio;

    @Schema(description = "신호 설명")
    private String signalDescription;

    @Schema(description = "활성 여부")
    private Boolean isActive;

    @Schema(description = "신호 발생 시간")
    private LocalDateTime createdAt;
}

