package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 종목 상세 진단 DTO
 * 마법의 공식 등에서 선택한 종목의 '더블 체크' 결과를 담는 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockDiagnosisDto {

    // ========== 기본 정보 ==========
    private String stockCode;
    private String stockName;
    private String market;
    private BigDecimal currentPrice;
    private LocalDate diagnosisDate;

    // ========== 1. 재무 건전성 분석 ==========
    private FinancialHealthDto financialHealth;

    // ========== 2. 수급 현황 분석 ==========
    private SupplyDemandDto supplyDemand;

    // ========== 3. 기술적 분석 ==========
    private TechnicalAnalysisDto technicalAnalysis;

    // ========== 종합 의견 (Verdict) ==========
    private String verdict;           // "매수 적기", "주의 요망", "관망 권고" 등
    private VerdictLevel verdictLevel;
    private int overallScore;         // 0~100 종합 점수
    private List<String> warnings;    // 주의 사항 목록
    private List<String> positives;   // 긍정적 요소 목록

    /**
     * 재무 건전성 분석 결과
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FinancialHealthDto {
        // 영업이익 vs 순이익 비교
        private BigDecimal operatingProfit;     // 영업이익 (억원)
        private BigDecimal netIncome;           // 당기순이익 (억원)
        private BigDecimal profitGap;           // 순이익 - 영업이익 (억원)
        private BigDecimal profitGapRatio;      // (순이익 - 영업이익) / |영업이익| * 100 (%)

        // 일회성 이익 경고
        private boolean hasOneTimeGainWarning;  // 일회성 이익 의심 여부
        private String oneTimeGainReason;       // 경고 사유

        // 기타 재무 지표
        private BigDecimal operatingMargin;     // 영업이익률 (%)
        private BigDecimal netMargin;           // 순이익률 (%)
        private BigDecimal roe;                 // ROE (%)
        private BigDecimal debtRatio;           // 부채비율 (%)

        // 점수
        private int score;                      // 재무 건전성 점수 (0~100)
        private String assessment;              // "양호", "보통", "주의"
    }

    /**
     * 수급 현황 분석 결과
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupplyDemandDto {
        // 최근 5일 외국인 수급
        private BigDecimal foreignNet5Days;     // 외국인 5일 순매수 합계 (백만원)
        private int foreignBuyDays;             // 외국인 순매수 일수 (5일 중)
        private boolean isForeignBuying;        // 외국인 매수 우위 여부

        // 최근 5일 기관 수급
        private BigDecimal institutionNet5Days; // 기관 5일 순매수 합계 (백만원)
        private int institutionBuyDays;         // 기관 순매수 일수 (5일 중)
        private boolean isInstitutionBuying;    // 기관 매수 우위 여부

        // 수급 일치 여부
        private boolean isBothBuying;           // 외국인 & 기관 동시 매수
        private boolean isBothSelling;          // 외국인 & 기관 동시 매도

        // 점수
        private int score;                      // 수급 점수 (0~100)
        private String assessment;              // "매수 우위", "매도 우위", "혼조"
    }

    /**
     * 기술적 분석 결과
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TechnicalAnalysisDto {
        // 이동평균선
        private Boolean isArrangedUp;           // 이평선 정배열 여부
        private Boolean isAboveMa20;            // 20일선 위에 있는지
        private Boolean isAboveMa60;            // 60일선 위에 있는지
        private Boolean isGoldenCross;          // 골든크로스 발생
        private Boolean isDeadCross;            // 데드크로스 발생

        // RSI
        private BigDecimal rsi14;               // RSI (14일)
        private String rsiStatus;               // "과열", "침체", "중립"
        private boolean isRsiOversold;          // RSI 30 이하 (매수 기회)
        private boolean isRsiOverbought;        // RSI 70 이상 (매도 신호)

        // 종합 기술적 신호
        private String overallSignal;           // "강력 매수", "매수", "중립", "매도", "강력 매도"
        private Integer buySignalStrength;      // 매수 신호 강도 (0~100)
        private String signalDescription;       // 신호 설명

        // 점수
        private int score;                      // 기술적 점수 (0~100)
        private String assessment;              // "매수 신호", "중립", "매도 신호"
    }

    /**
     * 종합 의견 레벨
     */
    public enum VerdictLevel {
        STRONG_BUY("매수 적기", "종합 분석 결과 매수하기 좋은 시점입니다."),
        BUY("매수 고려", "긍정적 요소가 많으나 일부 주의 필요합니다."),
        NEUTRAL("관망 권고", "현재 시점에서는 관망이 좋겠습니다."),
        CAUTION("주의 요망", "부정적 신호가 감지되었습니다."),
        AVOID("매수 비추천", "현재 시점에서 매수를 권하지 않습니다.");

        private final String label;
        private final String description;

        VerdictLevel(String label, String description) {
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
}
