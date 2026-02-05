package com.myplatform.backend.service;

import com.myplatform.backend.dto.AiAnalysisResponseDto;
import com.myplatform.backend.dto.AiStockRecommendationDto;
import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 주식 분석 서비스
 * - 종합 점수 계산 (기술적 분석 + 수급 분석 + 기본적 분석)
 * - AI 4대장 앙상블 시뮬레이션
 * - 단기/중장기 TOP PICK 추천
 * - 텔레그램 알림 (점수 90 이상)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiStockAnalysisService {

    private final InvestorDailyTradeRepository investorDailyTradeRepository;
    private final StockFinancialDataRepository stockFinancialDataRepository;
    private final InvestorTradeService investorTradeService;
    private final StockPriceService stockPriceService;
    private final TelegramNotificationService telegramService;
    private final MarketTimingService marketTimingService;
    private final GeminiService geminiService;

    // 분석 결과 캐시
    private volatile AiAnalysisResponseDto cachedAnalysis;
    private volatile LocalDateTime lastAnalysisTime;

    // 점수 가중치
    private static final double TECHNICAL_WEIGHT = 0.3;   // 기술적 분석 30%
    private static final double SUPPLY_WEIGHT = 0.4;      // 수급 분석 40%
    private static final double FUNDAMENTAL_WEIGHT = 0.3; // 기본적 분석 30%

    // 텔레그램 알림 임계값
    private static final int ALERT_SCORE_THRESHOLD = 90;

    /**
     * AI 분석 결과 조회 (캐시된 데이터 반환)
     */
    public AiAnalysisResponseDto getAnalysis() {
        // 캐시가 없거나 1시간 이상 경과하면 새로 분석
        if (cachedAnalysis == null || lastAnalysisTime == null ||
            lastAnalysisTime.isBefore(LocalDateTime.now().minusHours(1))) {
            runAnalysis();
        }
        return cachedAnalysis;
    }

    /**
     * 강제 새로고침
     */
    public AiAnalysisResponseDto refreshAnalysis() {
        runAnalysis();
        return cachedAnalysis;
    }

    /**
     * 정기 분석 스케줄 (장 시작 전, 장중, 장 마감 후)
     */
    @Scheduled(cron = "0 0 9,12,15 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledAnalysis() {
        log.info("AI 주식 분석 스케줄 실행");
        runAnalysis();
    }

    /**
     * 분석 실행
     */
    private synchronized void runAnalysis() {
        try {
            log.info("AI 주식 분석 시작...");

            // 1. 연속 순매수 종목 조회
            List<ConsecutiveBuyDto> foreignConsecutive = investorTradeService.getConsecutiveBuyStocks("FOREIGN", 3);
            List<ConsecutiveBuyDto> institutionConsecutive = investorTradeService.getConsecutiveBuyStocks("INSTITUTION", 3);

            // 2. 후보 종목 수집 (연속 순매수 + 최근 거래 상위)
            Set<String> candidateCodes = new HashSet<>();
            foreignConsecutive.forEach(c -> candidateCodes.add(c.getStockCode()));
            institutionConsecutive.forEach(c -> candidateCodes.add(c.getStockCode()));

            // 최근 투자자별 거래 상위 종목 추가 (외국인 + 기관)
            LocalDate today = LocalDate.now();
            LocalDate startDate = today.minusDays(5);
            List<InvestorDailyTrade> foreignTrades = investorDailyTradeRepository
                    .findByInvestorTypeAndDateRange("FOREIGN", startDate, today);
            foreignTrades.stream()
                    .filter(t -> t.getTradeType().equals("BUY"))
                    .map(InvestorDailyTrade::getStockCode)
                    .forEach(candidateCodes::add);

            List<InvestorDailyTrade> institutionTrades = investorDailyTradeRepository
                    .findByInvestorTypeAndDateRange("INSTITUTION", startDate, today);
            institutionTrades.stream()
                    .filter(t -> t.getTradeType().equals("BUY"))
                    .map(InvestorDailyTrade::getStockCode)
                    .forEach(candidateCodes::add);

            // 최대 50개로 제한
            List<String> stockCodes = candidateCodes.stream()
                    .limit(50)
                    .collect(Collectors.toList());

            log.info("분석 대상 종목 수: {}", stockCodes.size());

            // 3. 각 종목 분석
            Map<String, ConsecutiveBuyDto> foreignMap = foreignConsecutive.stream()
                    .collect(Collectors.toMap(ConsecutiveBuyDto::getStockCode, c -> c, (a, b) -> a));
            Map<String, ConsecutiveBuyDto> institutionMap = institutionConsecutive.stream()
                    .collect(Collectors.toMap(ConsecutiveBuyDto::getStockCode, c -> c, (a, b) -> a));

            List<AiStockRecommendationDto> allRecommendations = new ArrayList<>();

            for (String stockCode : stockCodes) {
                try {
                    AiStockRecommendationDto recommendation = analyzeStock(
                            stockCode,
                            foreignMap.get(stockCode),
                            institutionMap.get(stockCode)
                    );
                    if (recommendation != null && recommendation.getTotalScore() > 50) {
                        allRecommendations.add(recommendation);
                    }
                } catch (Exception e) {
                    log.warn("종목 분석 실패: {} - {}", stockCode, e.getMessage());
                }
            }

            // 4. 점수순 정렬
            allRecommendations.sort((a, b) -> b.getTotalScore().compareTo(a.getTotalScore()));

            // 5. 단기/중장기 TOP PICK 분류
            List<AiStockRecommendationDto> shortTermPicks = new ArrayList<>();
            List<AiStockRecommendationDto> longTermPicks = new ArrayList<>();

            for (AiStockRecommendationDto rec : allRecommendations) {
                if (rec.getShortTermScore() >= rec.getLongTermScore()) {
                    if (shortTermPicks.size() < 5) {
                        rec.setType("short");
                        shortTermPicks.add(rec);
                    }
                } else {
                    if (longTermPicks.size() < 5) {
                        rec.setType("long");
                        longTermPicks.add(rec);
                    }
                }
                if (shortTermPicks.size() >= 5 && longTermPicks.size() >= 5) break;
            }

            // 부족한 경우 상위 종목에서 채움
            if (shortTermPicks.size() < 3) {
                for (AiStockRecommendationDto rec : allRecommendations) {
                    if (!shortTermPicks.contains(rec) && shortTermPicks.size() < 3) {
                        rec.setType("short");
                        shortTermPicks.add(rec);
                    }
                }
            }
            if (longTermPicks.size() < 3) {
                for (AiStockRecommendationDto rec : allRecommendations) {
                    if (!longTermPicks.contains(rec) && !shortTermPicks.contains(rec) && longTermPicks.size() < 3) {
                        rec.setType("long");
                        longTermPicks.add(rec);
                    }
                }
            }

            // 6. 시장 지표 조회
            AiAnalysisResponseDto.MarketIndicators marketIndicators = getMarketIndicators();

            // 7. AI 앙상블 정보 생성
            AiAnalysisResponseDto.AiEnsembleInfo ensembleInfo = generateEnsembleInfo(allRecommendations);

            // 8. 결과 저장
            cachedAnalysis = AiAnalysisResponseDto.builder()
                    .shortTermPicks(shortTermPicks)
                    .longTermPicks(longTermPicks)
                    .marketIndicators(marketIndicators)
                    .ensembleInfo(ensembleInfo)
                    .analyzedAt(LocalDateTime.now())
                    .build();
            lastAnalysisTime = LocalDateTime.now();

            // 9. 고득점 종목 텔레그램 알림
            sendHighScoreAlerts(shortTermPicks, longTermPicks);

            log.info("AI 분석 완료 - 단기: {}개, 중장기: {}개",
                    shortTermPicks.size(), longTermPicks.size());

        } catch (Exception e) {
            log.error("AI 분석 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 개별 종목 분석
     */
    private AiStockRecommendationDto analyzeStock(String stockCode,
                                                   ConsecutiveBuyDto foreignConsec,
                                                   ConsecutiveBuyDto institutionConsec) {
        // 주가 정보 조회
        StockPriceDto priceDto = stockPriceService.getStockPrice(stockCode);
        if (priceDto == null || priceDto.getCurrentPrice() == null) {
            return null;
        }

        // 재무 데이터 조회
        Optional<StockFinancialData> financialOpt = stockFinancialDataRepository
                .findTopByStockCodeOrderByReportDateDesc(stockCode);

        // 점수 계산
        AiStockRecommendationDto.ScoreDetails scoreDetails = calculateScoreDetails(
                stockCode, priceDto, foreignConsec, institutionConsec, financialOpt.orElse(null)
        );

        // 기술적 점수 (RSI, MACD, 볼린저, VWAP)
        int technicalScore = calculateTechnicalScore(scoreDetails);

        // 수급 점수 (외국인, 기관 순매수)
        int supplyScore = calculateSupplyScore(scoreDetails);

        // 기본적 점수 (밸류에이션, 수익성)
        int fundamentalScore = calculateFundamentalScore(scoreDetails);

        // 단기 점수 (기술적 + 수급 가중)
        int shortTermScore = (int) Math.round(
                technicalScore * 0.4 + supplyScore * 0.5 + fundamentalScore * 0.1
        );

        // 중장기 점수 (기본적 + 수급 가중)
        int longTermScore = (int) Math.round(
                technicalScore * 0.2 + supplyScore * 0.3 + fundamentalScore * 0.5
        );

        // 종합 점수
        int totalScore = (int) Math.round(
                technicalScore * TECHNICAL_WEIGHT +
                supplyScore * SUPPLY_WEIGHT +
                fundamentalScore * FUNDAMENTAL_WEIGHT
        );

        // 의견 결정
        String opinion;
        String opinionClass;
        if (totalScore >= 85) {
            opinion = "풀매수";
            opinionClass = "strong-buy";
        } else if (totalScore >= 70) {
            opinion = "매수";
            opinionClass = "buy";
        } else if (totalScore >= 50) {
            opinion = "관망";
            opinionClass = "hold";
        } else {
            opinion = "매도";
            opinionClass = "sell";
        }

        // AI 코멘트 생성
        String aiSummary = generateAiSummary(priceDto.getStockName(), scoreDetails, totalScore);
        List<String> buyReasons = generateBuyReasons(scoreDetails);
        List<String> riskFactors = generateRiskFactors(scoreDetails);

        return AiStockRecommendationDto.builder()
                .stockCode(stockCode)
                .stockName(priceDto.getStockName())
                .currentPrice(priceDto.getCurrentPrice())
                .changeRate(priceDto.getChangeRate())
                .changePrice(priceDto.getChangePrice())
                .shortTermScore(shortTermScore)
                .longTermScore(longTermScore)
                .totalScore(totalScore)
                .opinion(opinion)
                .opinionClass(opinionClass)
                .aiSummary(aiSummary)
                .buyReasons(buyReasons)
                .riskFactors(riskFactors)
                .scoreDetails(scoreDetails)
                .analyzedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 세부 점수 계산
     */
    private AiStockRecommendationDto.ScoreDetails calculateScoreDetails(
            String stockCode,
            StockPriceDto priceDto,
            ConsecutiveBuyDto foreignConsec,
            ConsecutiveBuyDto institutionConsec,
            StockFinancialData financial) {

        AiStockRecommendationDto.ScoreDetails.ScoreDetailsBuilder builder =
                AiStockRecommendationDto.ScoreDetails.builder();

        // RSI 점수 - RSI 데이터가 없으므로 기본값 사용
        // TODO: RSI 데이터 연동 시 구현
        builder.rsiScore(50);  // 기본값

        // VWAP 점수 (현재가 < VWAP이면 저평가)
        // TODO: VWAP 데이터 연동 시 구현
        builder.vwapScore(50);

        // 볼린저밴드 점수
        // TODO: 볼린저밴드 데이터 연동 시 구현
        builder.bollingerScore(50);

        // MACD 점수
        // TODO: MACD 데이터 연동 시 구현
        builder.macdScore(50);

        // 외국인 연속 매수 점수
        if (foreignConsec != null) {
            int days = foreignConsec.getConsecutiveDays();
            builder.foreignerConsecutiveDays(days);
            builder.foreignerNetBuy(foreignConsec.getTotalNetBuyAmount().longValue());
            // 3일: 60점, 5일: 80점, 7일 이상: 100점
            builder.foreignerScore(Math.min(100, 50 + days * 10));
        } else {
            builder.foreignerScore(30);
        }

        // 기관 연속 매수 점수
        if (institutionConsec != null) {
            int days = institutionConsec.getConsecutiveDays();
            builder.institutionConsecutiveDays(days);
            builder.institutionNetBuy(institutionConsec.getTotalNetBuyAmount().longValue());
            builder.institutionScore(Math.min(100, 50 + days * 10));
        } else {
            builder.institutionScore(30);
        }

        // 외국인+기관 동시 연속 매수시 추가 점수
        if (foreignConsec != null && institutionConsec != null) {
            builder.consecutiveBuyScore(95);
        } else if (foreignConsec != null || institutionConsec != null) {
            builder.consecutiveBuyScore(70);
        } else {
            builder.consecutiveBuyScore(40);
        }

        // 기본적 분석 점수
        if (financial != null) {
            // PER 점수 (10 이하 고득점)
            BigDecimal per = financial.getPer();
            if (per != null && per.compareTo(BigDecimal.ZERO) > 0) {
                builder.per(per.doubleValue());
                if (per.compareTo(BigDecimal.valueOf(10)) <= 0) {
                    builder.valuationScore(90);
                } else if (per.compareTo(BigDecimal.valueOf(20)) <= 0) {
                    builder.valuationScore(70);
                } else if (per.compareTo(BigDecimal.valueOf(30)) <= 0) {
                    builder.valuationScore(50);
                } else {
                    builder.valuationScore(30);
                }
            } else {
                builder.valuationScore(40);
            }

            // PBR
            if (financial.getPbr() != null) {
                builder.pbr(financial.getPbr().doubleValue());
            }

            // 영업이익률 점수
            BigDecimal opMargin = financial.getOperatingMargin();
            if (opMargin != null) {
                builder.operatingMargin(opMargin.doubleValue());
                if (opMargin.compareTo(BigDecimal.valueOf(15)) >= 0) {
                    builder.profitabilityScore(90);
                } else if (opMargin.compareTo(BigDecimal.valueOf(10)) >= 0) {
                    builder.profitabilityScore(75);
                } else if (opMargin.compareTo(BigDecimal.valueOf(5)) >= 0) {
                    builder.profitabilityScore(60);
                } else if (opMargin.compareTo(BigDecimal.ZERO) >= 0) {
                    builder.profitabilityScore(45);
                } else {
                    builder.profitabilityScore(20);  // 적자
                }
            } else {
                builder.profitabilityScore(50);
            }

            // 성장성 점수
            // TODO: 전년 대비 매출/이익 성장률 계산
            builder.growthScore(50);
        } else {
            builder.valuationScore(50);
            builder.profitabilityScore(50);
            builder.growthScore(50);
        }

        return builder.build();
    }

    /**
     * 기술적 분석 종합 점수
     */
    private int calculateTechnicalScore(AiStockRecommendationDto.ScoreDetails details) {
        int rsi = details.getRsiScore() != null ? details.getRsiScore() : 50;
        int macd = details.getMacdScore() != null ? details.getMacdScore() : 50;
        int vwap = details.getVwapScore() != null ? details.getVwapScore() : 50;
        int bollinger = details.getBollingerScore() != null ? details.getBollingerScore() : 50;

        return (int) Math.round(rsi * 0.4 + macd * 0.2 + vwap * 0.2 + bollinger * 0.2);
    }

    /**
     * 수급 분석 종합 점수
     */
    private int calculateSupplyScore(AiStockRecommendationDto.ScoreDetails details) {
        int foreigner = details.getForeignerScore() != null ? details.getForeignerScore() : 30;
        int institution = details.getInstitutionScore() != null ? details.getInstitutionScore() : 30;
        int consecutive = details.getConsecutiveBuyScore() != null ? details.getConsecutiveBuyScore() : 40;

        return (int) Math.round(foreigner * 0.4 + institution * 0.4 + consecutive * 0.2);
    }

    /**
     * 기본적 분석 종합 점수
     */
    private int calculateFundamentalScore(AiStockRecommendationDto.ScoreDetails details) {
        int valuation = details.getValuationScore() != null ? details.getValuationScore() : 50;
        int profitability = details.getProfitabilityScore() != null ? details.getProfitabilityScore() : 50;
        int growth = details.getGrowthScore() != null ? details.getGrowthScore() : 50;

        return (int) Math.round(valuation * 0.4 + profitability * 0.35 + growth * 0.25);
    }

    /**
     * AI 요약 코멘트 생성 (Gemini AI 연동)
     */
    private String generateAiSummary(String stockName, AiStockRecommendationDto.ScoreDetails details, int totalScore) {
        // 1. 기본 요약 생성 (폴백용)
        StringBuilder fallbackSummary = new StringBuilder();

        if (details.getForeignerConsecutiveDays() != null && details.getForeignerConsecutiveDays() >= 3) {
            fallbackSummary.append(String.format("외국인 %d일 연속 순매수 중. ", details.getForeignerConsecutiveDays()));
        }
        if (details.getInstitutionConsecutiveDays() != null && details.getInstitutionConsecutiveDays() >= 3) {
            fallbackSummary.append(String.format("기관 %d일 연속 순매수 중. ", details.getInstitutionConsecutiveDays()));
        }
        if (details.getOperatingMargin() != null && details.getOperatingMargin() >= 10) {
            fallbackSummary.append(String.format("영업이익률 %.1f%%로 수익성 양호. ", details.getOperatingMargin()));
        }
        if (details.getPer() != null && details.getPer() < 15) {
            fallbackSummary.append(String.format("PER %.1f배로 저평가 매력. ", details.getPer()));
        }

        if (fallbackSummary.length() == 0) {
            if (totalScore >= 70) {
                fallbackSummary.append("전반적인 지표가 양호하여 관심 필요.");
            } else {
                fallbackSummary.append("현재 뚜렷한 매수 신호는 없으나 모니터링 권장.");
            }
        }

        // 2. Gemini AI 분석 시도 (고점수 종목만)
        if (totalScore >= 75) {
            try {
                String stockData = buildStockDataSummary(stockName, details);
                String aiAnalysis = geminiService.analyzeStockRecommendation(stockData);
                if (aiAnalysis != null && !aiAnalysis.isEmpty() &&
                    !aiAnalysis.startsWith("AI 서버") && !aiAnalysis.startsWith("Rate Limit")) {
                    return aiAnalysis;
                }
            } catch (Exception e) {
                log.debug("Gemini AI 분석 실패, 기본 요약 사용: {}", e.getMessage());
            }
        }

        return fallbackSummary.toString().trim();
    }

    /**
     * Gemini AI용 종목 데이터 요약 문자열 생성
     */
    private String buildStockDataSummary(String stockName, AiStockRecommendationDto.ScoreDetails details) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("종목: %s\n", stockName));

        if (details.getForeignerConsecutiveDays() != null) {
            sb.append(String.format("- 외국인 연속 순매수: %d일\n", details.getForeignerConsecutiveDays()));
        }
        if (details.getInstitutionConsecutiveDays() != null) {
            sb.append(String.format("- 기관 연속 순매수: %d일\n", details.getInstitutionConsecutiveDays()));
        }
        if (details.getPer() != null) {
            sb.append(String.format("- PER: %.1f배\n", details.getPer()));
        }
        if (details.getPbr() != null) {
            sb.append(String.format("- PBR: %.2f배\n", details.getPbr()));
        }
        if (details.getOperatingMargin() != null) {
            sb.append(String.format("- 영업이익률: %.1f%%\n", details.getOperatingMargin()));
        }

        return sb.toString();
    }

    /**
     * 매수 근거 리스트 생성
     */
    private List<String> generateBuyReasons(AiStockRecommendationDto.ScoreDetails details) {
        List<String> reasons = new ArrayList<>();

        if (details.getForeignerConsecutiveDays() != null && details.getForeignerConsecutiveDays() >= 3) {
            reasons.add("외국인 " + details.getForeignerConsecutiveDays() + "일 연속 순매수");
        }
        if (details.getInstitutionConsecutiveDays() != null && details.getInstitutionConsecutiveDays() >= 3) {
            reasons.add("기관 " + details.getInstitutionConsecutiveDays() + "일 연속 순매수");
        }
        if (details.getPer() != null && details.getPer() < 15) {
            reasons.add(String.format("저평가 PER %.1f배", details.getPer()));
        }
        if (details.getOperatingMargin() != null && details.getOperatingMargin() >= 10) {
            reasons.add(String.format("높은 영업이익률 %.1f%%", details.getOperatingMargin()));
        }
        if (details.getForeignerConsecutiveDays() != null && details.getInstitutionConsecutiveDays() != null) {
            reasons.add("외국인+기관 동반 매수");
        }

        return reasons;
    }

    /**
     * 리스크 요인 리스트 생성
     */
    private List<String> generateRiskFactors(AiStockRecommendationDto.ScoreDetails details) {
        List<String> risks = new ArrayList<>();

        if (details.getPer() != null && details.getPer() > 30) {
            risks.add("고평가 PER");
        }
        if (details.getOperatingMargin() != null && details.getOperatingMargin() < 0) {
            risks.add("영업적자 지속");
        }
        if (details.getForeignerConsecutiveDays() == null && details.getInstitutionConsecutiveDays() == null) {
            risks.add("수급 모멘텀 부재");
        }
        if (details.getPbr() != null && details.getPbr() > 5) {
            risks.add("높은 PBR");
        }

        if (risks.isEmpty()) {
            risks.add("현재 특별한 리스크 요인 없음");
        }

        return risks;
    }

    /**
     * 시장 지표 조회
     */
    private AiAnalysisResponseDto.MarketIndicators getMarketIndicators() {
        try {
            var timing = marketTimingService.getCurrentMarketTiming();
            if (timing != null) {
                String sentiment;
                double adr = timing.getCombinedAdr() != null ? timing.getCombinedAdr().doubleValue() : 100;
                if (adr >= 120) {
                    sentiment = "과열";
                } else if (adr <= 80) {
                    sentiment = "침체";
                } else {
                    sentiment = "정상";
                }

                // 공포/탐욕 지수 계산 (ADR 기반 단순화)
                double fearGreedIndex = Math.min(100, Math.max(0, adr));

                // KOSPI/KOSDAQ 지수 정보
                Double kospiIndex = null;
                Double kospiChange = null;
                Double kosdaqIndex = null;
                Double kosdaqChange = null;

                if (timing.getKospi() != null) {
                    kospiIndex = timing.getKospi().getIndexClose() != null ?
                            timing.getKospi().getIndexClose().doubleValue() : null;
                    kospiChange = timing.getKospi().getIndexChangeRate() != null ?
                            timing.getKospi().getIndexChangeRate().doubleValue() : null;
                }
                if (timing.getKosdaq() != null) {
                    kosdaqIndex = timing.getKosdaq().getIndexClose() != null ?
                            timing.getKosdaq().getIndexClose().doubleValue() : null;
                    kosdaqChange = timing.getKosdaq().getIndexChangeRate() != null ?
                            timing.getKosdaq().getIndexChangeRate().doubleValue() : null;
                }

                return AiAnalysisResponseDto.MarketIndicators.builder()
                        .kospiIndex(kospiIndex)
                        .kospiChange(kospiChange)
                        .kosdaqIndex(kosdaqIndex)
                        .kosdaqChange(kosdaqChange)
                        .adr(adr)
                        .marketSentiment(sentiment)
                        .fearGreedIndex(fearGreedIndex)
                        .build();
            }
        } catch (Exception e) {
            log.warn("시장 지표 조회 실패: {}", e.getMessage());
        }

        return AiAnalysisResponseDto.MarketIndicators.builder()
                .marketSentiment("알 수 없음")
                .build();
    }

    /**
     * AI 4대장 앙상블 정보 생성 (Gemini 연동)
     * - 상위 종목 기반 각 AI 점수 시뮬레이션 + Gemini 앙상블 의견
     */
    private AiAnalysisResponseDto.AiEnsembleInfo generateEnsembleInfo(
            List<AiStockRecommendationDto> recommendations) {

        if (recommendations.isEmpty()) {
            return AiAnalysisResponseDto.AiEnsembleInfo.builder()
                    .consensusOpinion("분석 대기")
                    .build();
        }

        // 상위 종목들의 평균 점수 기반으로 각 AI 점수 계산
        double avgScore = recommendations.stream()
                .limit(10)
                .mapToInt(AiStockRecommendationDto::getTotalScore)
                .average()
                .orElse(50);

        // 각 AI마다 특성에 따른 편차 (GPT: 기술적, Claude: 기본적, Gemini: 수급, Deepseek: 모멘텀)
        Random rand = new Random(LocalDate.now().toEpochDay());
        int gptScore = Math.min(100, Math.max(0, (int) (avgScore + rand.nextGaussian() * 5)));
        int claudeScore = Math.min(100, Math.max(0, (int) (avgScore + rand.nextGaussian() * 5)));
        int geminiScore = Math.min(100, Math.max(0, (int) (avgScore + rand.nextGaussian() * 5)));
        int deepseekScore = Math.min(100, Math.max(0, (int) (avgScore + rand.nextGaussian() * 5)));

        int consensusScore = (gptScore + claudeScore + geminiScore + deepseekScore) / 4;

        // 기본 의견 결정
        String consensusOpinion;
        if (consensusScore >= 80) {
            consensusOpinion = "적극 매수";
        } else if (consensusScore >= 65) {
            consensusOpinion = "매수";
        } else if (consensusScore >= 50) {
            consensusOpinion = "관망";
        } else {
            consensusOpinion = "주의";
        }

        // Gemini AI로 앙상블 의견 생성 시도
        try {
            String stocksSummary = recommendations.stream()
                    .limit(5)
                    .map(r -> String.format("%s(점수:%d)", r.getStockName(), r.getTotalScore()))
                    .collect(Collectors.joining(", "));

            String aiOpinion = geminiService.generateEnsembleOpinion("상위 추천 종목: " + stocksSummary);
            if (aiOpinion != null && !aiOpinion.isEmpty() &&
                !aiOpinion.startsWith("AI 서버") && !aiOpinion.startsWith("Rate Limit") &&
                !aiOpinion.startsWith("데이터가")) {
                // AI 의견이 있으면 추가
                consensusOpinion = aiOpinion.length() > 50 ? aiOpinion.substring(0, 50) + "..." : aiOpinion;
            }
        } catch (Exception e) {
            log.debug("Gemini 앙상블 의견 생성 실패, 기본 의견 사용: {}", e.getMessage());
        }

        return AiAnalysisResponseDto.AiEnsembleInfo.builder()
                .gptScore(gptScore)
                .claudeScore(claudeScore)
                .geminiScore(geminiScore)
                .deepseekScore(deepseekScore)
                .consensusScore(consensusScore)
                .consensusOpinion(consensusOpinion)
                .build();
    }

    /**
     * 고득점 종목 텔레그램 알림 발송
     */
    private void sendHighScoreAlerts(List<AiStockRecommendationDto> shortTerm,
                                      List<AiStockRecommendationDto> longTerm) {
        // 90점 이상 종목만 알림
        List<AiStockRecommendationDto> highScoreStocks = new ArrayList<>();
        highScoreStocks.addAll(shortTerm.stream()
                .filter(s -> s.getTotalScore() >= ALERT_SCORE_THRESHOLD)
                .toList());
        highScoreStocks.addAll(longTerm.stream()
                .filter(s -> s.getTotalScore() >= ALERT_SCORE_THRESHOLD)
                .toList());

        for (AiStockRecommendationDto stock : highScoreStocks) {
            sendAiRecommendationAlert(stock);
        }
    }

    /**
     * AI 추천 종목 텔레그램 알림
     */
    private void sendAiRecommendationAlert(AiStockRecommendationDto stock) {
        String type = "short".equals(stock.getType()) ? "단기" : "중장기";
        String reasons = String.join("\n• ", stock.getBuyReasons());

        String priceStr = stock.getCurrentPrice() != null ?
                String.format("%,.0f", stock.getCurrentPrice()) : "N/A";
        String changeRateStr = stock.getChangeRate() != null ?
                String.format("%.2f", stock.getChangeRate()) : "0.00";
        String changeSign = stock.getChangeRate() != null && stock.getChangeRate().doubleValue() >= 0 ? "+" : "";

        String message = String.format(
            """
            <b>🤖 AI %s TOP PICK!</b>

            📊 <b>%s</b> (%s)
            💰 현재가: <b>%s원</b> (%s%s%%)
            🎯 AI 점수: <b>%d점</b> / 의견: <b>%s</b>

            📈 <b>매수 근거</b>
            • %s

            💬 <b>AI 코멘트</b>
            %s

            ⏰ %s
            ━━━━━━━━━━━━━━━━
            🤖 MyPlatform AI 분석
            """,
            type,
            stock.getStockName(), stock.getStockCode(),
            priceStr,
            changeSign,
            changeRateStr,
            stock.getTotalScore(), stock.getOpinion(),
            reasons.isEmpty() ? "분석 중" : reasons,
            stock.getAiSummary(),
            LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        );

        telegramService.sendMessage(message);
    }
}
