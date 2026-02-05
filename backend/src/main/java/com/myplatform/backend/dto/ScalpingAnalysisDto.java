package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 단타 분석 응답 DTO
 * 체결강도, 프로그램 매매, 외국인/기관 순매수 정보 포함
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScalpingAnalysisDto {

    private String stockCode;
    private String stockName;

    // 현재가 정보
    private BigDecimal currentPrice;    // 현재가
    private BigDecimal changePrice;     // 전일 대비
    private BigDecimal changeRate;      // 등락률 (%)
    private Long tradingVolume;         // 거래량

    // 체결강도 (vol_tnrt)
    private BigDecimal volumePower;     // 체결강도 (100% 기준)
    private String volumeSignal;        // STRONG_BUY, BUY, NEUTRAL, SELL, STRONG_SELL

    // 프로그램 매매 시계열
    private List<ProgramTradingPoint> programTradingSeries;
    private BigDecimal programNetBuy;   // 당일 누적 순매수 (억원)
    private String programTrend;        // UP, DOWN, FLAT

    // 외국인/기관 순매수
    private BigDecimal foreignNetBuy;   // 외국인 순매수 (억원)
    private BigDecimal instNetBuy;      // 기관 순매수 (억원)

    private LocalDateTime fetchedAt;

    /**
     * 체결강도 신호 계산
     */
    public static String calculateVolumeSignal(BigDecimal volumePower) {
        if (volumePower == null) {
            return "NEUTRAL";
        }
        double power = volumePower.doubleValue();
        if (power >= 120) {
            return "STRONG_BUY";
        } else if (power >= 100) {
            return "BUY";
        } else if (power >= 80) {
            return "NEUTRAL";
        } else if (power >= 60) {
            return "SELL";
        } else {
            return "STRONG_SELL";
        }
    }

    /**
     * 프로그램 매매 추세 계산
     */
    public static String calculateProgramTrend(BigDecimal programNetBuy) {
        if (programNetBuy == null) {
            return "FLAT";
        }
        double netBuy = programNetBuy.doubleValue();
        if (netBuy > 10) {
            return "UP";
        } else if (netBuy < -10) {
            return "DOWN";
        } else {
            return "FLAT";
        }
    }

    /**
     * 프로그램 매매 시계열 데이터
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgramTradingPoint {
        private String time;            // HH:mm 형식
        private BigDecimal netBuyAmount; // 누적 순매수 (억원)
    }
}
