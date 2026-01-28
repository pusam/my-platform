package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 숏스퀴즈 후보 종목 DTO
 * - 공매도 세력이 숏커버링(되사기)을 할 가능성이 높은 종목 정보
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortSqueezeDto {

    private String stockCode;
    private String stockName;

    // ========== 현재 주가 정보 ==========
    private BigDecimal currentPrice;
    private BigDecimal changeRate;           // 당일 등락률 (%)
    private BigDecimal priceChange5Days;     // 최근 5일 주가 변화율 (%)

    // ========== 대차잔고 분석 ==========
    private BigDecimal currentLoanBalance;       // 현재 대차잔고 (주)
    private BigDecimal avgLoanBalance20Days;     // 20일 평균 대차잔고
    private BigDecimal loanBalanceChange5Days;   // 최근 5일 대차잔고 변화율 (%) - 음수면 숏커버링
    private BigDecimal loanBalanceRatio;         // 상장주식수 대비 대차잔고 비율 (%)

    // ========== 공매도 분석 ==========
    private BigDecimal shortRatio;               // 공매도 비중 (%)
    private BigDecimal shortBalanceRatio;        // 공매도 잔고 비율 (%)

    // ========== 외국인 수급 ==========
    private BigDecimal foreignNetBuy3Days;       // 최근 3일 외국인 순매수 (억원)
    private Boolean isForeignBuying;             // 외국인 순매수 여부

    // ========== 기술적 분석 ==========
    private BigDecimal ma20;                     // 20일 이동평균
    private Boolean isAboveMa20;                 // 20일선 위에 있는지
    private Boolean isTrendReversal;             // 추세 전환 여부

    // ========== 스퀴즈 점수 ==========
    private Integer squeezeScore;                // 종합 스퀴즈 점수 (0~100)
    private String squeezeLevel;                 // CRITICAL, HIGH, MEDIUM, LOW
    private String signalDescription;            // 시그널 설명

    private LocalDate analysisDate;              // 분석 기준일

    /**
     * 스퀴즈 레벨 계산
     */
    public void calculateSqueezeLevel() {
        if (squeezeScore == null) return;

        if (squeezeScore >= 80) {
            this.squeezeLevel = "CRITICAL";
            this.signalDescription = "숏스퀴즈 임박! 대차잔고 급감 + 외국인 매수 + 주가 상승";
        } else if (squeezeScore >= 60) {
            this.squeezeLevel = "HIGH";
            this.signalDescription = "숏커버링 진행 중. 추가 상승 가능성 높음";
        } else if (squeezeScore >= 40) {
            this.squeezeLevel = "MEDIUM";
            this.signalDescription = "숏커버링 초기 신호. 관찰 필요";
        } else {
            this.squeezeLevel = "LOW";
            this.signalDescription = "숏커버링 가능성 낮음";
        }
    }
}
