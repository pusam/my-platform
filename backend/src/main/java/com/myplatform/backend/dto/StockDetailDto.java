package com.myplatform.backend.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 종목 종합 상세 DTO
 *
 * 모든 데이터를 하나의 API로 통합하여 제공:
 * - 현재가/등락률
 * - 수급 정보 (외인/기관/프로그램)
 * - 재무 지표 (ROE, PER, 영업이익률 등)
 * - 리스크 분석 (공시, 뉴스, AI 점수)
 * - AI 매매 전략
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockDetailDto {

    // ========== 기본 정보 ==========
    private String stockCode;
    private String stockName;
    private LocalDateTime fetchedAt;

    // ========== 가격 정보 ==========
    private PriceInfo price;

    // ========== 수급 정보 ==========
    private SupplyDemand supplyDemand;

    // ========== 재무 정보 ==========
    private FinancialInfo financial;

    // ========== 리스크 분석 ==========
    private RiskInfo risk;

    // ========== AI 종합 분석 ==========
    private AiAnalysis aiAnalysis;

    // ========== 차트 데이터 ==========
    private ChartData chartData;

    // ========== Peer Group 비교 ==========
    private List<PeerComparison> peerComparisons;
    private BigDecimal sectorAvgPbr;          // 업종 평균 PBR
    private String sectorName;                // 섹터명

    /**
     * 가격 정보
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceInfo {
        private BigDecimal currentPrice;      // 현재가
        private BigDecimal changePrice;       // 전일 대비
        private BigDecimal changeRate;        // 등락률 (%)
        private Long tradingVolume;           // 거래량
        private BigDecimal tradingValue;      // 거래대금 (억원)
        private BigDecimal high;              // 고가
        private BigDecimal low;               // 저가
        private BigDecimal open;              // 시가
        private BigDecimal prevClose;         // 전일종가
    }

    /**
     * 수급 정보
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupplyDemand {
        // 체결강도
        private BigDecimal volumePower;       // 체결강도 (%)
        private String volumeSignal;          // STRONG_BUY, BUY, NEUTRAL, SELL, STRONG_SELL

        // 외국인/기관
        private BigDecimal foreignNetBuy;     // 외국인 순매수 (억원)
        private BigDecimal instNetBuy;        // 기관 순매수 (억원)
        private Integer foreignConsecDays;    // 외국인 연속 순매수 일수
        private Integer instConsecDays;       // 기관 연속 순매수 일수

        // 프로그램 매매
        private BigDecimal programNetBuy;     // 프로그램 누적 순매수 (억원)
        private String programTrend;          // UP, DOWN, FLAT
        private List<ScalpingAnalysisDto.ProgramTradingPoint> programSeries;

        // 데이터 출처 표시
        private String dataSource;            // "실시간", "일별(DB)", "장전(초기화)"
    }

    /**
     * 재무 정보
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FinancialInfo {
        private BigDecimal per;               // PER (배) - Trailing
        private BigDecimal pbr;               // PBR (배)
        private BigDecimal peg;               // PEG
        private BigDecimal roe;               // ROE (%)
        private BigDecimal eps;               // EPS (원) - Trailing
        private BigDecimal bps;               // BPS (원)
        private BigDecimal operatingMargin;   // 영업이익률 (%)
        private BigDecimal netMargin;         // 순이익률 (%)
        private BigDecimal debtRatio;         // 부채비율 (%)
        private Long marketCap;               // 시가총액 (억원)
        private BigDecimal dividendYield;     // 배당수익률 (%)

        // ========== Forward (12M 선행) 지표 ==========
        private BigDecimal forwardPer;        // Forward PER (12M 선행, 배)
        private BigDecimal forwardPbr;        // Forward PBR (12M 선행, 배)
        private BigDecimal forwardEps;        // Forward EPS (12M 선행, 원)
        private BigDecimal forwardBps;        // Forward BPS (12M 선행, 원)
        private BigDecimal epsGrowthRate;     // EPS 성장률 (%)

        // ========== 수급 보조 지표 ==========
        private BigDecimal foreignOwnership;  // 외국인 지분율 (%)

        // ========== 주주환원 ==========
        private BigDecimal totalShareholderReturn; // TSR (총주주환원율, %)
        private String buybackInfo;           // 자사주 매입/소각 정보

        // ========== 핵심 투자 포인트 태그 ==========
        private java.util.List<String> investmentTags; // 예: ["#TSR 50%", "#밸류업 대장주"]
    }

    /**
     * 섹터 Peer Group 비교
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PeerComparison {
        private String stockName;             // 종목명
        private String stockCode;             // 종목코드
        private BigDecimal pbr;               // PBR
        private BigDecimal per;               // PER
        private BigDecimal roe;               // ROE
        private BigDecimal dividendYield;     // 배당수익률
        private boolean isCurrent;            // 현재 분석 종목 여부
    }

    /**
     * 리스크 정보
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskInfo {
        private Integer riskScore;            // 리스크 점수 (0~100)
        private String riskStatus;            // SAFE, WARNING, DANGER
        private String riskReason;            // 리스크 사유
        private Integer dangerDisclosureCount; // 위험 공시 수
        private Integer newsCount;            // 관련 뉴스 수
        private List<RiskAnalysisDto.DartDisclosure> disclosures;
        private List<RiskAnalysisDto.NewsItem> news;
        private List<String> riskTags;        // 리스크 키워드 태그
    }

    /**
     * AI 종합 분석
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiAnalysis {
        private Integer overallScore;         // AI 종합 점수 (0~100)
        private String recommendation;        // BUY, HOLD, SELL, TRADING_BUY, WAIT_AND_BUY
        private String strategy;              // AI 매매 전략 텍스트
        private String technicalSignal;       // 기술적 신호 (눌림목, 돌파, 과열 등)
        private List<String> buyReasons;      // 매수 근거
        private List<String> sellReasons;     // 매도 근거
        private String conflictAnalysis;      // 단기/장기 점수 충돌 분석 텍스트
        private String priceGuide;            // 동적 가격 가이드 (예: "160,000원대 진입 시 강력 매수")

        // ========== 목표주가 컨센서스 ==========
        private BigDecimal consensusTargetPrice;  // 증권사 평균 목표주가
        private BigDecimal targetUpside;          // 현재가 대비 상승여력 (%)
        private String consensusSource;           // 데이터 출처
    }

    /**
     * 차트 데이터
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChartData {
        private List<CandlePoint> candles;    // 일봉 데이터
        private List<VolumePoint> volumes;    // 거래량 데이터
        private BigDecimal ma5;               // 5일 이동평균
        private BigDecimal ma20;              // 20일 이동평균
        private BigDecimal ma60;              // 60일 이동평균
        private BigDecimal vwap;              // VWAP
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandlePoint {
        private String date;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VolumePoint {
        private String date;
        private Long volume;
    }
}
