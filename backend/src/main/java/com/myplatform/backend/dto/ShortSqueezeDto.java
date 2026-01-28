package com.myplatform.backend.dto;

import com.myplatform.backend.dto.TechnicalIndicatorsDto.RsiStatus;
import com.myplatform.backend.dto.TechnicalIndicatorsDto.TechnicalSignal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 숏스퀴즈 후보 종목 DTO
 * - 공매도 세력이 숏커버링(되사기)을 할 가능성이 높은 종목 정보
 * - 기술적 분석 지표 포함
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

    // ========== 이동평균선 (기술적 분석) ==========
    private BigDecimal ma5;                      // 5일 이동평균
    private BigDecimal ma20;                     // 20일 이동평균
    private BigDecimal ma60;                     // 60일 이동평균
    private BigDecimal ma120;                    // 120일 이동평균

    // ========== 이동평균 위치 ==========
    private Boolean isAboveMa5;                  // 5일선 위에 있는지
    private Boolean isAboveMa20;                 // 20일선 위에 있는지
    private Boolean isAboveMa60;                 // 60일선 위에 있는지

    // ========== RSI (상대강도지수) ==========
    private BigDecimal rsi14;                    // RSI 14일 (0~100)
    private RsiStatus rsiStatus;                 // OVERBOUGHT, OVERSOLD, NEUTRAL

    // ========== 기술적 시그널 ==========
    private Boolean isGoldenCross;               // 골든크로스 여부 (5일선 > 20일선 상향 돌파)
    private Boolean isDeadCross;                 // 데드크로스 여부
    private Boolean isArrangedUp;                // 정배열 여부 (5일 > 20일 > 60일)
    private Boolean isArrangedDown;              // 역배열 여부
    private Boolean isTrendReversal;             // 추세 전환 여부

    // ========== 종합 기술적 신호 ==========
    private Integer technicalScore;              // 기술적 매수 신호 강도 (0~100)
    private TechnicalSignal technicalSignal;     // STRONG_BUY, BUY, NEUTRAL, SELL, STRONG_SELL
    private String technicalDescription;         // 기술적 신호 설명

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

        // 기술적 신호와 결합하여 설명 보강
        if (Boolean.TRUE.equals(isGoldenCross)) {
            this.signalDescription += " + 골든크로스";
        }
        if (Boolean.TRUE.equals(isArrangedUp)) {
            this.signalDescription += " + 정배열";
        }
        if (rsiStatus == RsiStatus.OVERSOLD) {
            this.signalDescription += " + RSI 침체(반등 가능)";
        }
    }

    /**
     * 기술적 지표 DTO로부터 값 설정
     */
    public void applyTechnicalIndicators(TechnicalIndicatorsDto indicators) {
        if (indicators == null) return;

        this.ma5 = indicators.getMa5();
        this.ma20 = indicators.getMa20();
        this.ma60 = indicators.getMa60();
        this.ma120 = indicators.getMa120();

        this.isAboveMa5 = indicators.getIsAboveMa5();
        this.isAboveMa20 = indicators.getIsAboveMa20();
        this.isAboveMa60 = indicators.getIsAboveMa60();

        this.rsi14 = indicators.getRsi14();
        this.rsiStatus = indicators.getRsiStatus();

        this.isGoldenCross = indicators.getIsGoldenCross();
        this.isDeadCross = indicators.getIsDeadCross();
        this.isArrangedUp = indicators.getIsArrangedUp();
        this.isArrangedDown = indicators.getIsArrangedDown();

        this.technicalScore = indicators.getBuySignalStrength();
        this.technicalSignal = indicators.getOverallSignal();
        this.technicalDescription = indicators.getSignalDescription();

        // 추세 전환 판단: 20일선 돌파 또는 골든크로스
        this.isTrendReversal = Boolean.TRUE.equals(this.isAboveMa20) ||
                               Boolean.TRUE.equals(this.isGoldenCross);
    }

    /**
     * RSI 상태 한글 라벨 반환
     */
    public String getRsiStatusLabel() {
        if (rsiStatus == null) return "-";
        return rsiStatus.getLabel();
    }

    /**
     * 기술적 신호 한글 라벨 반환
     */
    public String getTechnicalSignalLabel() {
        if (technicalSignal == null) return "-";
        return technicalSignal.getLabel();
    }
}
