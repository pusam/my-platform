package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 환율 정보 DTO
 * - USD/KRW 환율
 * - 환율 급등 → 외국인 매도 압력
 * - 환율 급락 → 외국인 매수 압력
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRateDto {

    // 기본 정보
    private BigDecimal rate;           // 현재 환율 (USD/KRW)
    private BigDecimal change;         // 전일 대비 변동액
    private BigDecimal changeRate;     // 전일 대비 변동률 (%)

    // 분석 정보
    private String trend;              // UP, DOWN, FLAT
    private String signal;             // FOREIGN_SELL, FOREIGN_BUY, NEUTRAL
    private String interpretation;     // 해석 텍스트

    private LocalDateTime fetchedAt;   // 조회 시각

    /**
     * 환율 변동에 따른 외국인 수급 신호 결정
     * - 환율 상승 → 외국인 매도 압력 (원화 약세)
     * - 환율 하락 → 외국인 매수 압력 (원화 강세)
     */
    public static String determineSignal(BigDecimal changeRate) {
        if (changeRate == null) return "NEUTRAL";

        double rate = changeRate.doubleValue();
        if (rate >= 0.5) {
            return "FOREIGN_SELL";  // 환율 급등 → 외국인 매도
        } else if (rate <= -0.5) {
            return "FOREIGN_BUY";   // 환율 급락 → 외국인 매수
        }
        return "NEUTRAL";
    }

    /**
     * 추세 결정
     */
    public static String determineTrend(BigDecimal change) {
        if (change == null) return "FLAT";

        int cmp = change.compareTo(BigDecimal.ZERO);
        if (cmp > 0) return "UP";
        if (cmp < 0) return "DOWN";
        return "FLAT";
    }

    /**
     * 해석 텍스트 생성
     */
    public static String generateInterpretation(BigDecimal changeRate, String signal) {
        if (changeRate == null) return "환율 정보 없음";

        double rate = changeRate.doubleValue();
        String direction = rate >= 0 ? "상승" : "하락";
        String absRate = String.format("%.2f", Math.abs(rate));

        switch (signal) {
            case "FOREIGN_SELL":
                return String.format("환율 %s%% %s - 외국인 매도 압력 주의", absRate, direction);
            case "FOREIGN_BUY":
                return String.format("환율 %s%% %s - 외국인 매수 유입 기대", absRate, direction);
            default:
                return String.format("환율 %s%% %s - 중립", absRate, direction);
        }
    }
}
