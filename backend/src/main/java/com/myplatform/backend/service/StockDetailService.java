package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.*;
import com.myplatform.backend.dto.StockDetailDto.*;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 종목 종합 상세 서비스
 *
 * 여러 서비스의 데이터를 통합하여 한 번에 제공:
 * - ScalpingAnalysisService (수급)
 * - RiskManagementService (리스크)
 * - QuantScreenerService (재무)
 * - KoreaInvestmentService (가격/차트)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockDetailService {

    private final KoreaInvestmentService kisService;
    private final ScalpingAnalysisService scalpingService;
    private final RiskManagementService riskService;
    private final VwapService vwapService;
    private final StockPriceService stockPriceService;
    private final InvestorDailyTradeRepository investorDailyTradeRepository;

    // 장 마감 시간 (15:30)
    private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 종목 종합 상세 조회
     *
     * @param stockCode 종목코드 (6자리)
     * @return 종합 상세 정보
     */
    public StockDetailDto getStockDetail(String stockCode) {
        log.info("[StockDetail] 종목 {} 종합 상세 조회 시작", stockCode);
        long startTime = System.currentTimeMillis();

        StockDetailDto.StockDetailDtoBuilder builder = StockDetailDto.builder()
                .stockCode(stockCode)
                .fetchedAt(LocalDateTime.now());

        // 1. 현재가 조회 (필수) - 종목명 먼저 확보
        String stockName = stockCode;  // 기본값 (종목코드)
        JsonNode priceData = kisService.getStockPrice(stockCode);
        if (priceData != null && "0".equals(getFieldValue(priceData, "rt_cd"))) {
            PriceInfo priceInfo = parsePriceInfo(priceData);
            builder.price(priceInfo);

            // 종목명 설정 (KIS API에서 가져오기)
            String name = getFieldValue(priceData, "output", "hts_kor_isnm");
            if (name != null && !name.isEmpty()) {
                stockName = name;
            }
            log.info("[StockDetail] 종목명: {}, 현재가: {}", stockName, priceInfo.getCurrentPrice());
        } else {
            log.warn("[StockDetail] 현재가 조회 실패: {} - 종목명을 별도 조회합니다", stockCode);
            // 가격 조회 실패 시 종목명만 별도 조회
            try {
                List<StockPriceDto> searchResult = stockPriceService.searchStocks(stockCode);
                if (searchResult != null && !searchResult.isEmpty()) {
                    stockName = searchResult.get(0).getStockName();
                }
            } catch (Exception e) {
                log.debug("[StockDetail] 종목명 별도 조회 실패: {}", e.getMessage());
            }
        }
        // ★★★ 항상 stockName 설정 ★★★
        builder.stockName(stockName);

        // ★★★ 종목명을 final로 캡처 (람다에서 사용) ★★★
        final String finalStockName = stockName;

        // ★★★ 장 마감 여부 체크 ★★★
        boolean isAfterMarket = isAfterMarketHours();
        log.info("[StockDetail] 현재 시간: {}, 장 마감 여부: {}", LocalTime.now(KST), isAfterMarket);

        // 2. 병렬 조회 (수급, 리스크, 차트)
        // ★★★ 장 마감 후에는 DB에서 일별 누적 데이터 사용 ★★★
        CompletableFuture<SupplyDemand> supplyFuture =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        if (isAfterMarket) {
                            log.info("[StockDetail] 장 마감 후 - DB 일별 데이터 조회");
                            return getDailySummaryFromDb(stockCode);
                        } else {
                            log.info("[StockDetail] 장중 - 실시간 API 데이터 조회");
                            ScalpingAnalysisDto scalping = scalpingService.getScalpingAnalysis(stockCode);
                            return parseSupplyDemand(scalping);
                        }
                    } catch (Exception e) {
                        log.error("[StockDetail] 수급 조회 실패: {}", e.getMessage());
                        return null;
                    }
                });

        // ★★★ 뉴스 검색 시 정확한 종목명 사용 ★★★
        CompletableFuture<RiskAnalysisDto> riskFuture =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        log.info("[StockDetail] 리스크 분석 시작 - 종목명: '{}'", finalStockName);
                        return riskService.analyzeRisk(finalStockName);
                    } catch (Exception e) {
                        log.error("[StockDetail] 리스크 조회 실패: {}", e.getMessage());
                        return null;
                    }
                });

        CompletableFuture<ChartData> chartFuture =
                CompletableFuture.supplyAsync(() -> fetchChartData(stockCode));

        try {
            // 수급 정보 - 장 마감 여부에 따라 다른 소스 사용
            SupplyDemand supplyDemand = supplyFuture.get(30, TimeUnit.SECONDS);
            if (supplyDemand != null) {
                log.info("[StockDetail] 수급 데이터 - 외국인: {}억, 기관: {}억, 체결강도: {}%{}",
                        supplyDemand.getForeignNetBuy(), supplyDemand.getInstNetBuy(),
                        supplyDemand.getVolumePower(),
                        isAfterMarket ? " (장마감-DB)" : " (장중-API)");
                builder.supplyDemand(supplyDemand);
            } else {
                log.warn("[StockDetail] 수급 데이터 없음");
            }

            // 리스크 정보
            RiskAnalysisDto risk = riskFuture.get(60, TimeUnit.SECONDS);
            if (risk != null) {
                log.info("[StockDetail] 리스크 분석 완료 - 뉴스: {}건, 점수: {}",
                        risk.getRelatedNews() != null ? risk.getRelatedNews().size() : 0,
                        risk.getRiskScore());
                builder.risk(parseRiskInfo(risk));
            } else {
                log.warn("[StockDetail] 리스크 데이터 없음");
            }

            // 차트 데이터
            ChartData chart = chartFuture.get(30, TimeUnit.SECONDS);
            if (chart != null) {
                builder.chartData(chart);
            }

        } catch (Exception e) {
            log.warn("[StockDetail] 병렬 조회 중 오류: {}", e.getMessage(), e);
        }

        // 3. 재무 정보 조회
        FinancialInfo financial = fetchFinancialInfo(stockCode);
        builder.financial(financial);

        // 4. AI 종합 분석 생성
        StockDetailDto dto = builder.build();
        AiAnalysis aiAnalysis = generateAiAnalysis(dto);
        dto.setAiAnalysis(aiAnalysis);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[StockDetail] 종목 {} 종합 상세 조회 완료: {}ms", stockCode, elapsed);

        return dto;
    }

    /**
     * 가격 정보 파싱 (KIS API 실시간 시세)
     */
    private PriceInfo parsePriceInfo(JsonNode data) {
        JsonNode output = data.get("output");
        if (output == null) {
            log.warn("[StockDetail] 가격 output 없음");
            return null;
        }

        BigDecimal currentPrice = parseBigDecimal(output.get("stck_prpr"));
        BigDecimal changePrice = parseBigDecimal(output.get("prdy_vrss"));
        BigDecimal changeRate = parseBigDecimal(output.get("prdy_ctrt"));

        log.debug("[StockDetail] 시세 파싱 - 현재가: {}, 전일대비: {}, 등락률: {}%",
                currentPrice, changePrice, changeRate);

        return PriceInfo.builder()
                .currentPrice(currentPrice)
                .changePrice(changePrice)
                .changeRate(changeRate)
                .tradingVolume(parseLong(output.get("acml_vol")))
                .tradingValue(parseBigDecimal(output.get("acml_tr_pbmn"))
                        .divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP))
                .high(parseBigDecimal(output.get("stck_hgpr")))
                .low(parseBigDecimal(output.get("stck_lwpr")))
                .open(parseBigDecimal(output.get("stck_oprc")))
                .prevClose(parseBigDecimal(output.get("stck_sdpr")))
                .build();
    }

    /**
     * 수급 정보 변환 (null → 0 처리)
     */
    private SupplyDemand parseSupplyDemand(ScalpingAnalysisDto scalping) {
        if (scalping == null) {
            log.warn("[StockDetail] ScalpingAnalysisDto가 null");
            return SupplyDemand.builder()
                    .volumePower(BigDecimal.ZERO)
                    .volumeSignal("NEUTRAL")
                    .foreignNetBuy(BigDecimal.ZERO)
                    .instNetBuy(BigDecimal.ZERO)
                    .programNetBuy(BigDecimal.ZERO)
                    .programTrend("FLAT")
                    .build();
        }

        return SupplyDemand.builder()
                .volumePower(scalping.getVolumePower() != null ? scalping.getVolumePower() : BigDecimal.ZERO)
                .volumeSignal(scalping.getVolumeSignal() != null ? scalping.getVolumeSignal() : "NEUTRAL")
                .foreignNetBuy(scalping.getForeignNetBuy() != null ? scalping.getForeignNetBuy() : BigDecimal.ZERO)
                .instNetBuy(scalping.getInstNetBuy() != null ? scalping.getInstNetBuy() : BigDecimal.ZERO)
                .programNetBuy(scalping.getProgramNetBuy() != null ? scalping.getProgramNetBuy() : BigDecimal.ZERO)
                .programTrend(scalping.getProgramTrend() != null ? scalping.getProgramTrend() : "FLAT")
                .programSeries(scalping.getProgramTradingSeries())
                .build();
    }

    /**
     * 리스크 정보 변환
     */
    private RiskInfo parseRiskInfo(RiskAnalysisDto risk) {
        return RiskInfo.builder()
                .riskScore(risk.getRiskScore())
                .riskStatus(risk.getStatus() != null ? risk.getStatus().name() : "UNKNOWN")
                .riskReason(risk.getReason())
                .dangerDisclosureCount(risk.getDangerousDisclosures() != null ?
                        risk.getDangerousDisclosures().size() : 0)
                .newsCount(risk.getRelatedNews() != null ? risk.getRelatedNews().size() : 0)
                .disclosures(risk.getDangerousDisclosures())
                .news(risk.getRelatedNews())
                .build();
    }

    /**
     * 차트 데이터 조회
     */
    private ChartData fetchChartData(String stockCode) {
        try {
            JsonNode dailyData = kisService.getDailyPrices(stockCode, 60);
            if (dailyData == null) return null;

            JsonNode output2 = dailyData.get("output2");
            if (output2 == null || !output2.isArray()) return null;

            List<CandlePoint> candles = new ArrayList<>();
            List<VolumePoint> volumes = new ArrayList<>();
            List<BigDecimal> closes = new ArrayList<>();

            for (JsonNode item : output2) {
                String date = item.has("stck_bsop_date") ? item.get("stck_bsop_date").asText() : "";
                BigDecimal close = parseBigDecimal(item.get("stck_clpr"));

                candles.add(CandlePoint.builder()
                        .date(date)
                        .open(parseBigDecimal(item.get("stck_oprc")))
                        .high(parseBigDecimal(item.get("stck_hgpr")))
                        .low(parseBigDecimal(item.get("stck_lwpr")))
                        .close(close)
                        .build());

                volumes.add(VolumePoint.builder()
                        .date(date)
                        .volume(parseLong(item.get("acml_vol")))
                        .build());

                closes.add(close);
            }

            // 이동평균 계산
            BigDecimal ma5 = calculateMA(closes, 5);
            BigDecimal ma20 = calculateMA(closes, 20);
            BigDecimal ma60 = calculateMA(closes, 60);

            // VWAP
            BigDecimal vwap = null;
            try {
                var vwapResult = vwapService.calculateVwap(stockCode);
                if (vwapResult != null) {
                    vwap = vwapResult.getVwap();
                }
            } catch (Exception e) {
                log.debug("[StockDetail] VWAP 조회 실패: {}", e.getMessage());
            }

            return ChartData.builder()
                    .candles(candles)
                    .volumes(volumes)
                    .ma5(ma5)
                    .ma20(ma20)
                    .ma60(ma60)
                    .vwap(vwap)
                    .build();

        } catch (Exception e) {
            log.warn("[StockDetail] 차트 데이터 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 재무 정보 조회
     */
    private FinancialInfo fetchFinancialInfo(String stockCode) {
        try {
            JsonNode priceData = kisService.getStockPrice(stockCode);
            if (priceData == null) return null;

            JsonNode output = priceData.get("output");
            if (output == null) return null;

            return FinancialInfo.builder()
                    .per(parseBigDecimal(output.get("per")))
                    .pbr(parseBigDecimal(output.get("pbr")))
                    .eps(parseBigDecimal(output.get("eps")))
                    .bps(parseBigDecimal(output.get("bps")))
                    .marketCap(parseLong(output.get("hts_avls")))
                    .build();

        } catch (Exception e) {
            log.warn("[StockDetail] 재무 정보 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * AI 종합 분석 생성
     */
    private AiAnalysis generateAiAnalysis(StockDetailDto dto) {
        int score = 50; // 기본 점수
        List<String> buyReasons = new ArrayList<>();
        List<String> sellReasons = new ArrayList<>();
        String technicalSignal = "NEUTRAL";

        // 수급 분석
        if (dto.getSupplyDemand() != null) {
            SupplyDemand supply = dto.getSupplyDemand();

            // 체결강도
            if (supply.getVolumePower() != null) {
                double power = supply.getVolumePower().doubleValue();
                if (power >= 120) {
                    score += 15;
                    buyReasons.add("체결강도 강세 (" + String.format("%.0f", power) + "%)");
                } else if (power < 80) {
                    score -= 10;
                    sellReasons.add("체결강도 약세 (" + String.format("%.0f", power) + "%)");
                }
            }

            // 외국인
            if (supply.getForeignNetBuy() != null) {
                double foreign = supply.getForeignNetBuy().doubleValue();
                if (foreign > 10) {
                    score += 10;
                    buyReasons.add("외국인 순매수 +" + String.format("%.0f", foreign) + "억");
                } else if (foreign < -10) {
                    score -= 10;
                    sellReasons.add("외국인 순매도 " + String.format("%.0f", foreign) + "억");
                }
            }

            // 기관
            if (supply.getInstNetBuy() != null) {
                double inst = supply.getInstNetBuy().doubleValue();
                if (inst > 10) {
                    score += 10;
                    buyReasons.add("기관 순매수 +" + String.format("%.0f", inst) + "억");
                } else if (inst < -10) {
                    score -= 10;
                    sellReasons.add("기관 순매도 " + String.format("%.0f", inst) + "억");
                }
            }

            // 프로그램
            if (supply.getProgramNetBuy() != null && supply.getProgramNetBuy().doubleValue() > 20) {
                score += 5;
                buyReasons.add("프로그램 매집");
            }
        }

        // 재무 분석
        if (dto.getFinancial() != null) {
            FinancialInfo fin = dto.getFinancial();

            if (fin.getPer() != null && fin.getPer().doubleValue() > 0 && fin.getPer().doubleValue() < 10) {
                score += 5;
                buyReasons.add("저PER " + fin.getPer().setScale(1, RoundingMode.HALF_UP) + "배");
            }

            if (fin.getPbr() != null && fin.getPbr().doubleValue() > 0 && fin.getPbr().doubleValue() < 1) {
                score += 5;
                buyReasons.add("저PBR " + fin.getPbr().setScale(2, RoundingMode.HALF_UP) + "배");
            }
        }

        // 리스크 반영
        if (dto.getRisk() != null) {
            RiskInfo risk = dto.getRisk();

            if ("DANGER".equals(risk.getRiskStatus())) {
                score -= 30;
                sellReasons.add("리스크 경고: " + risk.getRiskReason());
            } else if ("WARNING".equals(risk.getRiskStatus())) {
                score -= 15;
                sellReasons.add("주의: " + risk.getRiskReason());
            }
        }

        // 차트 분석 (기술적 신호)
        if (dto.getChartData() != null && dto.getPrice() != null) {
            ChartData chart = dto.getChartData();
            BigDecimal currentPrice = dto.getPrice().getCurrentPrice();

            if (chart.getMa20() != null && currentPrice != null) {
                if (currentPrice.compareTo(chart.getMa20()) > 0 &&
                    currentPrice.compareTo(chart.getMa20().multiply(new BigDecimal("1.05"))) < 0) {
                    technicalSignal = "눌림목 구간";
                    buyReasons.add("20일선 지지 (눌림목)");
                    score += 5;
                } else if (currentPrice.compareTo(chart.getMa20()) < 0) {
                    technicalSignal = "이평선 하향 이탈";
                    sellReasons.add("20일선 하회");
                }
            }

            if (chart.getVwap() != null && currentPrice != null) {
                if (currentPrice.compareTo(chart.getVwap()) > 0) {
                    buyReasons.add("VWAP 상회 (매수세 우위)");
                }
            }
        }

        // 점수 범위 제한
        score = Math.max(0, Math.min(100, score));

        // 추천 결정
        String recommendation;
        String strategy;

        if (score >= 70) {
            recommendation = "BUY";
            strategy = "적극 매수 구간입니다. 수급과 재무 모두 양호하며, 분할 매수 전략을 권장합니다.";
        } else if (score >= 50) {
            recommendation = "HOLD";
            strategy = "관망 또는 소규모 진입 구간입니다. 추가 확인 후 결정하세요.";
        } else if (score >= 30) {
            recommendation = "HOLD";
            strategy = "신중한 접근이 필요합니다. 리스크 요인을 점검하세요.";
        } else {
            recommendation = "SELL";
            strategy = "매수 보류 권장. 리스크가 높거나 수급이 불리합니다.";
        }

        return AiAnalysis.builder()
                .overallScore(score)
                .recommendation(recommendation)
                .strategy(strategy)
                .technicalSignal(technicalSignal)
                .buyReasons(buyReasons)
                .sellReasons(sellReasons)
                .build();
    }

    /**
     * 이동평균 계산
     */
    private BigDecimal calculateMA(List<BigDecimal> prices, int period) {
        if (prices == null || prices.size() < period) return null;

        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(prices.get(i));
        }
        return sum.divide(BigDecimal.valueOf(period), 2, RoundingMode.HALF_UP);
    }

    // ========== 장 마감 후 일별 데이터 조회 ==========

    /**
     * 장 마감 여부 확인 (15:30 이후)
     */
    private boolean isAfterMarketHours() {
        LocalTime now = LocalTime.now(KST);
        return now.isAfter(MARKET_CLOSE_TIME);
    }

    /**
     * DB에서 오늘의 일별 누적 데이터 조회 (장 마감 후 사용)
     *
     * InvestorDailyTrade 테이블에서 오늘 날짜의 데이터를 조회하여
     * 외국인/기관의 순매수 금액을 합산
     */
    private SupplyDemand getDailySummaryFromDb(String stockCode) {
        LocalDate today = LocalDate.now(KST);

        // 오늘 데이터가 없으면 가장 최근 거래일 데이터 사용
        LocalDate targetDate = investorDailyTradeRepository.findLatestTradeDate();
        if (targetDate == null) {
            log.warn("[StockDetail] DB에 거래 데이터 없음");
            return buildEmptySupplyDemand();
        }

        log.info("[StockDetail] DB 일별 데이터 조회 - 종목: {}, 거래일: {}", stockCode, targetDate);

        // 해당 종목의 투자자별 거래 데이터 조회 (오늘 또는 최근 거래일)
        LocalDate startDate = targetDate;
        LocalDate endDate = targetDate;
        List<InvestorDailyTrade> trades = investorDailyTradeRepository
                .findByStockCodeAndDateRange(stockCode, startDate, endDate);

        if (trades.isEmpty()) {
            log.warn("[StockDetail] 종목 {} 거래 데이터 없음 ({})", stockCode, targetDate);
            // 데이터가 없으면 실시간 API fallback 시도
            try {
                ScalpingAnalysisDto scalping = scalpingService.getScalpingAnalysis(stockCode);
                return parseSupplyDemand(scalping);
            } catch (Exception e) {
                log.warn("[StockDetail] 실시간 API fallback 실패: {}", e.getMessage());
                return buildEmptySupplyDemand();
            }
        }

        // 투자자별 순매수 합산
        BigDecimal foreignNetBuy = BigDecimal.ZERO;
        BigDecimal instNetBuy = BigDecimal.ZERO;
        BigDecimal programNetBuy = BigDecimal.ZERO;
        BigDecimal volumePower = null;
        List<ScalpingAnalysisDto.ProgramTradingPoint> programSeries = null;

        for (InvestorDailyTrade trade : trades) {
            String investorType = trade.getInvestorType();
            BigDecimal netBuy = trade.getNetBuyAmount();
            if (netBuy == null) continue;

            switch (investorType) {
                case "FOREIGN":
                    foreignNetBuy = foreignNetBuy.add(netBuy);
                    break;
                case "INSTITUTION":
                    instNetBuy = instNetBuy.add(netBuy);
                    break;
                case "PENSION":
                    // 연기금은 기관에 포함
                    instNetBuy = instNetBuy.add(netBuy);
                    break;
            }
        }

        // ★★★ 프로그램 매매 및 체결강도: 실시간 API에서 조회 (장 마감 후에도 당일 누적값 제공) ★★★
        try {
            ScalpingAnalysisDto scalping = scalpingService.getScalpingAnalysis(stockCode);
            if (scalping != null) {
                // 체결강도
                if (scalping.getVolumePower() != null &&
                    scalping.getVolumePower().compareTo(BigDecimal.ZERO) > 0) {
                    volumePower = scalping.getVolumePower();
                }

                // ★ 프로그램 순매수 금액
                if (scalping.getProgramNetBuy() != null) {
                    programNetBuy = scalping.getProgramNetBuy();
                    log.info("[StockDetail] 프로그램 순매수 API 조회: {}억", programNetBuy);
                }

                // ★ 프로그램 매매 시계열 (차트용)
                if (scalping.getProgramTradingSeries() != null && !scalping.getProgramTradingSeries().isEmpty()) {
                    programSeries = scalping.getProgramTradingSeries();
                    log.info("[StockDetail] 프로그램 매매 시계열: {}건", programSeries.size());
                }
            }
        } catch (Exception e) {
            log.warn("[StockDetail] 프로그램/체결강도 조회 실패: {}", e.getMessage());
        }

        // 체결강도가 없으면 기본값 100 (균형)
        if (volumePower == null || volumePower.compareTo(BigDecimal.ZERO) == 0) {
            volumePower = new BigDecimal("100");
        }

        String volumeSignal = ScalpingAnalysisDto.calculateVolumeSignal(volumePower);
        String programTrend = ScalpingAnalysisDto.calculateProgramTrend(programNetBuy);

        log.info("[StockDetail] DB 일별 요약 - 외국인: {}억, 기관: {}억, 프로그램: {}억, 체결강도: {}%, 시계열: {}건",
                foreignNetBuy, instNetBuy, programNetBuy, volumePower,
                programSeries != null ? programSeries.size() : 0);

        return SupplyDemand.builder()
                .volumePower(volumePower)
                .volumeSignal(volumeSignal)
                .foreignNetBuy(foreignNetBuy)
                .instNetBuy(instNetBuy)
                .programNetBuy(programNetBuy)
                .programTrend(programTrend)
                .programSeries(programSeries)  // ★ 시계열 데이터 추가
                .build();
    }

    /**
     * 빈 수급 정보 반환 (데이터 없을 때)
     */
    private SupplyDemand buildEmptySupplyDemand() {
        return SupplyDemand.builder()
                .volumePower(new BigDecimal("100"))  // 기본값 100% (균형)
                .volumeSignal("NEUTRAL")
                .foreignNetBuy(BigDecimal.ZERO)
                .instNetBuy(BigDecimal.ZERO)
                .programNetBuy(BigDecimal.ZERO)
                .programTrend("FLAT")
                .build();
    }

    // ========== 유틸리티 ==========

    private String getFieldValue(JsonNode root, String... path) {
        JsonNode node = root;
        for (String key : path) {
            if (node == null) return null;
            node = node.get(key);
        }
        return node != null ? node.asText() : null;
    }

    private BigDecimal parseBigDecimal(JsonNode node) {
        if (node == null || node.isNull()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(node.asText().replace(",", ""));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private Long parseLong(JsonNode node) {
        if (node == null || node.isNull()) return 0L;
        try {
            return Long.parseLong(node.asText().replace(",", ""));
        } catch (Exception e) {
            return 0L;
        }
    }
}
