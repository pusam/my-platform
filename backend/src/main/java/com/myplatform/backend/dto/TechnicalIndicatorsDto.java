package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 기술적 분석 지표 DTO
 *
 * 이동평균선, RSI, 시그널 등 기술적 분석 결과를 담는 DTO
 * 공매도, 퀀트, 수급 분석 등 다양한 도메인에서 공통으로 사용
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TechnicalIndicatorsDto {

    // ========== 이동평균선 (Moving Average) ==========
    // 단위: 원 (종가 기준)

    /** 5일 이동평균선 */
    private BigDecimal ma5;

    /** 20일 이동평균선 */
    private BigDecimal ma20;

    /** 60일 이동평균선 */
    private BigDecimal ma60;

    /** 120일 이동평균선 */
    private BigDecimal ma120;

    // ========== 이동평균 대비 이격도 ==========
    // 현재가와 이동평균선의 괴리율 (%)

    /** 5일선 이격도: (현재가 - MA5) / MA5 * 100 */
    private BigDecimal disparity5;

    /** 20일선 이격도 */
    private BigDecimal disparity20;

    /** 60일선 이격도 */
    private BigDecimal disparity60;

    // ========== RSI (Relative Strength Index) ==========

    /** RSI 14일 (0~100) */
    private BigDecimal rsi14;

    /** RSI 상태: OVERBOUGHT(과열), OVERSOLD(침체), NEUTRAL(중립) */
    private RsiStatus rsiStatus;

    // ========== 이동평균 시그널 ==========

    /** 골든크로스 여부: 5일선이 20일선을 상향 돌파 */
    private Boolean isGoldenCross;

    /** 데드크로스 여부: 5일선이 20일선을 하향 돌파 */
    private Boolean isDeadCross;

    /** 정배열 여부: 5일 > 20일 > 60일 (상승 추세) */
    private Boolean isArrangedUp;

    /** 역배열 여부: 5일 < 20일 < 60일 (하락 추세) */
    private Boolean isArrangedDown;

    /** 현재가 위치: 5일선 위에 있는지 */
    private Boolean isAboveMa5;

    /** 현재가 위치: 20일선 위에 있는지 */
    private Boolean isAboveMa20;

    /** 현재가 위치: 60일선 위에 있는지 */
    private Boolean isAboveMa60;

    // ========== 종합 시그널 ==========

    /** 매수 신호 강도: 0 ~ 100 */
    private Integer buySignalStrength;

    /** 종합 신호: STRONG_BUY, BUY, NEUTRAL, SELL, STRONG_SELL */
    private TechnicalSignal overallSignal;

    /** 신호 설명 */
    private String signalDescription;

    // ========== 볼린저 밴드 (Bollinger Bands) ==========

    /** 볼린저 밴드 상단 (20일 SMA + 2 * 표준편차) */
    private BigDecimal upperBand;

    /** 볼린저 밴드 중단 (20일 SMA) */
    private BigDecimal middleBand;

    /** 볼린저 밴드 하단 (20일 SMA - 2 * 표준편차) */
    private BigDecimal lowerBand;

    /** 밴드폭 = (상단 - 하단) / 중단 * 100 (%) */
    private BigDecimal bandWidth;

    /** 스퀴즈 여부: 밴드폭이 최근 20일 평균 대비 0.7배 이하 (에너지 응축) */
    private Boolean isSqueeze;

    /** 돌파 여부: 종가가 상단 밴드를 상향 돌파 */
    private Boolean isBreakout;

    // ========== MFI (Money Flow Index) ==========

    /** MFI 점수 (0~100) - 거래량 가중 RSI */
    private BigDecimal mfiScore;

    /** MFI 상태: OVERBOUGHT(과열 80+), OVERSOLD(침체 20-), NEUTRAL(중립) */
    private MfiStatus mfiStatus;

    // ========== 데이터 충분성 ==========

    /** 분석에 사용된 데이터 일수 */
    private Integer dataCount;

    /** 120일 이평선 계산 가능 여부 (데이터 충분성) */
    private Boolean hasEnoughDataFor120Ma;

    /**
     * MFI 상태 열거형
     */
    public enum MfiStatus {
        OVERBOUGHT("과열", "MFI 80 이상 - 매도 압력 증가 가능성"),
        OVERSOLD("침체", "MFI 20 이하 - 매수 기회 가능성"),
        NEUTRAL("중립", "MFI 20~80 - 정상 범위");

        private final String label;
        private final String description;

        MfiStatus(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() {
            return label;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * RSI 상태 열거형
     */
    public enum RsiStatus {
        OVERBOUGHT("과열", "RSI 70 이상 - 단기 조정 가능성"),
        OVERSOLD("침체", "RSI 30 이하 - 반등 가능성"),
        NEUTRAL("중립", "RSI 30~70 - 추세 지속");

        private final String label;
        private final String description;

        RsiStatus(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() {
            return label;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 종합 기술적 신호 열거형
     */
    public enum TechnicalSignal {
        STRONG_BUY("강력 매수", 5),
        BUY("매수", 4),
        NEUTRAL("중립", 3),
        SELL("매도", 2),
        STRONG_SELL("강력 매도", 1);

        private final String label;
        private final int strength;

        TechnicalSignal(String label, int strength) {
            this.label = label;
            this.strength = strength;
        }

        public String getLabel() {
            return label;
        }

        public int getStrength() {
            return strength;
        }
    }
}
