package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 트레이딩 핵심 지표 DTO
 * - VWAP, 나스닥 선물, 주도 섹터, RSI 다이버전스
 */
public class TradingIndicatorDto {

    // ========== 1. VWAP (거래량 가중 평균 가격) ==========

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VwapResult {
        private String stockCode;
        private String stockName;
        private BigDecimal vwap;              // VWAP 가격
        private BigDecimal currentPrice;       // 현재가
        private BigDecimal deviation;          // 괴리율 (%)
        private VwapSignal signal;             // 매수/매도 우위 신호
        private String interpretation;         // 해석
        private int dataPointCount;            // 데이터 수 (분봉 개수)
        private boolean isReliable;            // 신뢰 가능 여부 (09:10 이후)
        private LocalDateTime calculatedAt;
    }

    public enum VwapSignal {
        STRONG_BUY("강한 매수 우위", "현재가가 VWAP 대비 2% 이상 상회"),
        BUY("매수 우위", "현재가 > VWAP"),
        NEUTRAL("중립", "VWAP 근처"),
        SELL("매도 우위", "현재가 < VWAP"),
        STRONG_SELL("강한 매도 우위", "현재가가 VWAP 대비 2% 이상 하회");

        private final String label;
        private final String description;

        VwapSignal(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() { return label; }
        public String getDescription() { return description; }
    }

    // ========== 2. 나스닥 선물 (Global Market) ==========

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NasdaqFuturesResult {
        private String symbol;                 // NQ=F (나스닥 100 선물)
        private String name;                   // "Nasdaq 100 Futures"
        private BigDecimal price;              // 현재가
        private BigDecimal change;             // 등락폭
        private BigDecimal changeRate;         // 등락률 (%)
        private GlobalMarketSignal signal;     // 글로벌 악재 필터 신호
        private String interpretation;         // 해석
        private boolean isTradingHalt;         // 매수 보류 여부 (-0.5% 이하 시 true)
        private LocalDateTime fetchedAt;
    }

    public enum GlobalMarketSignal {
        POSITIVE("긍정적", "+0.5% 이상 상승"),
        NEUTRAL("중립", "-0.5% ~ +0.5%"),
        CAUTION("주의", "-0.5% ~ -1.0%"),
        NEGATIVE("부정적", "-1.0% 이하, 매수 보류 권장");

        private final String label;
        private final String description;

        GlobalMarketSignal(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() { return label; }
        public String getDescription() { return description; }
    }

    // ========== 3. 주도 섹터 랭킹 ==========

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LeadingSectorResult {
        private List<SectorRanking> topSectors;        // 상위 섹터 리스트
        private List<SectorRanking> bottomSectors;     // 하위 섹터 리스트
        private String marketLeader;                    // 시장 주도 섹터
        private String interpretation;                  // 해석
        private LocalDateTime calculatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SectorRanking {
        private int rank;                      // 순위
        private String sectorCode;             // 섹터 코드
        private String sectorName;             // 섹터명
        private BigDecimal averageChangeRate;  // 평균 등락률 (%)
        private String leadingStockCode;       // 대장주 종목코드
        private String leadingStockName;       // 대장주 종목명
        private BigDecimal leadingStockChange; // 대장주 등락률 (%)
        private int stockCount;                // 섹터 내 종목 수
    }

    // ========== 4. RSI 다이버전스 ==========

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DivergenceResult {
        private String stockCode;
        private String stockName;
        private DivergenceType type;           // 다이버전스 타입
        private DivergenceSignal signal;       // 매수/매도 신호
        private BigDecimal currentPrice;       // 현재가
        private BigDecimal currentRsi;         // 현재 RSI
        private BigDecimal prevPeakPrice;      // 이전 고점/저점 가격
        private BigDecimal prevPeakRsi;        // 이전 고점/저점 RSI
        private int peakIndex;                 // 이전 피크 봉 위치 (몇 봉 전)
        private int strength;                  // 신호 강도 (1~5)
        private String interpretation;         // 해석
        private boolean isValid;               // 유효한 다이버전스 여부
        private LocalDateTime detectedAt;
    }

    public enum DivergenceType {
        BEARISH_REGULAR("하락 다이버전스 (일반)", "주가 신고가, RSI 전고점 하회 → 추세 약화"),
        BEARISH_HIDDEN("하락 다이버전스 (히든)", "주가 저고점, RSI 고점 갱신 → 하락 지속"),
        BULLISH_REGULAR("상승 다이버전스 (일반)", "주가 신저가, RSI 전저점 상회 → 추세 반전"),
        BULLISH_HIDDEN("상승 다이버전스 (히든)", "주가 고저점, RSI 저점 갱신 → 상승 지속"),
        NONE("다이버전스 없음", "특이 신호 없음");

        private final String label;
        private final String description;

        DivergenceType(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() { return label; }
        public String getDescription() { return description; }
    }

    public enum DivergenceSignal {
        STRONG_BUY("강한 매수 신호", 5),
        BUY("매수 신호", 3),
        NEUTRAL("중립", 0),
        SELL("매도 신호", -3),
        STRONG_SELL("강한 매도 신호", -5);

        private final String label;
        private final int weight;

        DivergenceSignal(String label, int weight) {
            this.label = label;
            this.weight = weight;
        }

        public String getLabel() { return label; }
        public int getWeight() { return weight; }
    }

    // ========== 종합 분석 결과 ==========

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComprehensiveAnalysis {
        private VwapResult vwap;
        private NasdaqFuturesResult globalMarket;
        private LeadingSectorResult sectorRanking;
        private DivergenceResult divergence;
        private int overallScore;              // 종합 점수 (-100 ~ +100)
        private String recommendation;         // 종합 추천
        private LocalDateTime analysisTime;
    }
}
