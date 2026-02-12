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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

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
    private final GeminiService geminiService;

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
        StockPriceDto naverData = null; // 네이버 폴백 데이터 (재무 정보 재사용)
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
            log.warn("[StockDetail] KIS 현재가 조회 실패: {} - 네이버 폴백 시도", stockCode);
            // KIS 실패 → stockPriceService (내부 KIS→Naver 자동 폴백)
            try {
                naverData = stockPriceService.getStockPrice(stockCode);
                if (naverData != null && naverData.getCurrentPrice() != null) {
                    stockName = naverData.getStockName() != null ? naverData.getStockName() : stockCode;
                    PriceInfo priceInfo = convertNaverToPriceInfo(naverData);
                    builder.price(priceInfo);
                    log.info("[StockDetail] 네이버 폴백 성공: {} - 현재가: {}", stockName, naverData.getCurrentPrice());
                } else {
                    log.warn("[StockDetail] 네이버 폴백도 데이터 없음 - 종목명 별도 조회");
                    List<StockPriceDto> searchResult = stockPriceService.searchStocks(stockCode);
                    if (searchResult != null && !searchResult.isEmpty()) {
                        stockName = searchResult.get(0).getStockName();
                    }
                }
            } catch (Exception e) {
                log.warn("[StockDetail] 네이버 폴백 실패: {} - 종목명 별도 조회", e.getMessage());
                try {
                    List<StockPriceDto> searchResult = stockPriceService.searchStocks(stockCode);
                    if (searchResult != null && !searchResult.isEmpty()) {
                        stockName = searchResult.get(0).getStockName();
                    }
                } catch (Exception e2) {
                    log.debug("[StockDetail] 종목명 별도 조회 실패: {}", e2.getMessage());
                }
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

        // ★★★ 뉴스: 네이버 금융 종목별 뉴스 (종목코드 기반) ★★★
        CompletableFuture<RiskAnalysisDto> riskFuture =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        log.info("[StockDetail] 리스크 분석 시작 - 종목명: '{}', 코드: '{}'", finalStockName, stockCode);
                        return riskService.analyzeRisk(finalStockName, stockCode);
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

            // 수급 데이터 없거나 전부 0이면 네이버 폴백
            if (supplyDemand == null || isEmptySupplyDemand(supplyDemand)) {
                log.info("[StockDetail] 수급 데이터 없거나 비어있음 - 네이버 투자자 매매동향 폴백 시도");
                SupplyDemand naverSupply = fetchInvestorFromNaver(stockCode);
                if (naverSupply != null && !isEmptySupplyDemand(naverSupply)) {
                    supplyDemand = naverSupply;
                }
            }

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

        // 3. 재무 정보 조회 (KIS 실패 시 네이버 폴백)
        FinancialInfo financial = fetchFinancialInfo(stockCode);
        if (financial == null && naverData != null) {
            financial = convertNaverToFinancialInfo(naverData);
            log.info("[StockDetail] 재무 정보 네이버 폴백 적용 - PER: {}, PBR: {}",
                    financial != null ? financial.getPer() : null,
                    financial != null ? financial.getPbr() : null);
        }
        builder.financial(financial);

        // 4. AI 종합 분석 생성 (Gemini 우선 → 규칙기반 폴백)
        StockDetailDto dto = builder.build();
        AiAnalysis aiAnalysis = generateGeminiAnalysis(dto);
        if (aiAnalysis == null) {
            aiAnalysis = generateAiAnalysis(dto);
        }
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
     * 수급 정보 변환 (null/0 처리 개선)
     *
     * ★ 체결강도가 null 또는 0이면 기본값 100% (균형) 사용
     * ★ 외국인/기관/프로그램 값이 있으면 그대로 사용, 없으면 0
     */
    private SupplyDemand parseSupplyDemand(ScalpingAnalysisDto scalping) {
        if (scalping == null) {
            log.warn("[StockDetail] ScalpingAnalysisDto가 null - 기본값 반환");
            return buildEmptySupplyDemand();
        }

        // ★ 체결강도: null 또는 0이면 기본값 100 (균형)
        BigDecimal volumePower = scalping.getVolumePower();
        if (volumePower == null || volumePower.compareTo(BigDecimal.ZERO) == 0) {
            volumePower = new BigDecimal("100");
        }

        // ★ 외국인/기관/프로그램 데이터 로깅
        log.info("[StockDetail] parseSupplyDemand - 체결강도: {}%, 외인: {}억, 기관: {}억, 프로그램: {}억",
                volumePower, scalping.getForeignNetBuy(), scalping.getInstNetBuy(), scalping.getProgramNetBuy());

        String volumeSignal = scalping.getVolumeSignal();
        if (volumeSignal == null) {
            volumeSignal = ScalpingAnalysisDto.calculateVolumeSignal(volumePower);
        }

        return SupplyDemand.builder()
                .volumePower(volumePower)
                .volumeSignal(volumeSignal)
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

        // 수급-가격 괴리 감지
        if (dto.getPrice() != null && dto.getSupplyDemand() != null) {
            double changeRate = dto.getPrice().getChangeRate() != null ? dto.getPrice().getChangeRate().doubleValue() : 0;
            double foreignNet = dto.getSupplyDemand().getForeignNetBuy() != null ? dto.getSupplyDemand().getForeignNetBuy().doubleValue() : 0;
            double instNet = dto.getSupplyDemand().getInstNetBuy() != null ? dto.getSupplyDemand().getInstNetBuy().doubleValue() : 0;
            if (changeRate > 1.0 && (foreignNet < -5 || instNet < -5)) {
                score -= 15;
                sellReasons.add("⚠ 수급 괴리: 주가 상승(+" + String.format("%.1f", changeRate) + "%) 중 외인/기관 매도 → 개인 주도 상승 위험");
            } else if (changeRate < -1.0 && (foreignNet > 5 || instNet > 5)) {
                score += 5;
                buyReasons.add("기관 매집: 주가 하락 중 외인/기관 매수 → 저점 매집 가능성");
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
            log.info("[StockDetail] 종목 {} DB 데이터 없음 ({}) - 실시간 API 조회로 전환", stockCode, targetDate);
            // ★ DB에 데이터가 없으면 실시간 API에서 전체 데이터 조회
            // 장 마감 후에도 KIS API는 당일 누적 데이터를 제공함
            try {
                ScalpingAnalysisDto scalping = scalpingService.getScalpingAnalysis(stockCode);
                if (scalping != null) {
                    log.info("[StockDetail] 실시간 API 응답 - 체결강도: {}, 외인: {}, 기관: {}, 프로그램: {}",
                            scalping.getVolumePower(), scalping.getForeignNetBuy(),
                            scalping.getInstNetBuy(), scalping.getProgramNetBuy());
                }
                return parseSupplyDemand(scalping);
            } catch (Exception e) {
                log.warn("[StockDetail] 실시간 API 조회 실패: {} - 기본값 반환", e.getMessage());
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

    // ========== 네이버 폴백 변환 ==========

    /**
     * 네이버 StockPriceDto → PriceInfo 변환
     */
    private PriceInfo convertNaverToPriceInfo(StockPriceDto naverData) {
        BigDecimal tradingValue = null;
        if (naverData.getAccumulatedTradingValue() != null) {
            tradingValue = naverData.getAccumulatedTradingValue()
                    .divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP);
        }

        Long volume = null;
        if (naverData.getVolume() != null) {
            volume = naverData.getVolume().longValue();
        }

        BigDecimal prevClose = null;
        if (naverData.getCurrentPrice() != null && naverData.getChangePrice() != null) {
            prevClose = naverData.getCurrentPrice().subtract(naverData.getChangePrice());
        } else if (naverData.getCurrentPrice() != null && naverData.getChangeRate() != null
                && naverData.getChangeRate().compareTo(BigDecimal.ZERO) != 0) {
            // changePrice 없으면 changeRate로 역산
            BigDecimal rate = naverData.getChangeRate().divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
            prevClose = naverData.getCurrentPrice().divide(BigDecimal.ONE.add(rate), 0, RoundingMode.HALF_UP);
        }

        BigDecimal changePrice = naverData.getChangePrice();
        if (changePrice == null && prevClose != null && naverData.getCurrentPrice() != null) {
            changePrice = naverData.getCurrentPrice().subtract(prevClose);
        }

        return PriceInfo.builder()
                .currentPrice(naverData.getCurrentPrice())
                .changePrice(changePrice)
                .changeRate(naverData.getChangeRate())
                .tradingVolume(volume)
                .tradingValue(tradingValue)
                .high(naverData.getHighPrice())
                .low(naverData.getLowPrice())
                .open(naverData.getOpenPrice())
                .prevClose(prevClose)
                .build();
    }

    /**
     * 네이버 StockPriceDto → FinancialInfo 변환
     */
    private FinancialInfo convertNaverToFinancialInfo(StockPriceDto naverData) {
        Long marketCapBillion = null;
        if (naverData.getMarketCap() != null) {
            // 네이버 marketCap은 원 단위 → 억원 변환
            marketCapBillion = naverData.getMarketCap()
                    .divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP)
                    .longValue();
        }

        return FinancialInfo.builder()
                .per(naverData.getPer())
                .pbr(naverData.getPbr())
                .bps(naverData.getBps())
                .marketCap(marketCapBillion)
                .build();
    }

    /**
     * 수급 데이터가 비어있는지 확인 (외국인/기관 모두 0)
     */
    private boolean isEmptySupplyDemand(SupplyDemand sd) {
        if (sd == null) return true;

        boolean foreignZero = sd.getForeignNetBuy() == null
                || sd.getForeignNetBuy().compareTo(BigDecimal.ZERO) == 0;
        boolean instZero = sd.getInstNetBuy() == null
                || sd.getInstNetBuy().compareTo(BigDecimal.ZERO) == 0;

        return foreignZero && instZero;
    }

    /**
     * 네이버 투자자 매매동향 크롤링 (외국인/기관 순매매)
     * URL: https://finance.naver.com/item/frgn.naver?code={stockCode}
     */
    private SupplyDemand fetchInvestorFromNaver(String stockCode) {
        try {
            String url = "https://finance.naver.com/item/frgn.naver?code=" + stockCode;
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .referrer("https://finance.naver.com/")
                    .timeout(10000)
                    .get();

            // table.type2 내 tbody tr에서 최신 거래일 데이터 추출
            Elements rows = doc.select("table.type2 tbody tr");

            for (Element row : rows) {
                Elements tds = row.select("td");
                if (tds.size() < 9) continue;

                // 날짜가 있는 유효 데이터 행인지 확인
                String dateText = tds.get(0).text().trim();
                if (dateText.isEmpty() || !dateText.contains(".")) continue;

                // 컬럼: 날짜 | 종가 | 전일비 | 등락률 | 거래량 | 기관순매매량 | 외국인순매매량 | ...
                BigDecimal closePrice = parseNaverNumber(tds.get(1).text());
                BigDecimal instNetShares = parseNaverNumber(tds.get(5).text()); // 기관 순매매량 (주)
                BigDecimal foreignNetShares = parseNaverNumber(tds.get(6).text()); // 외국인 순매매량 (주)

                if (closePrice == null || closePrice.compareTo(BigDecimal.ZERO) == 0) continue;

                // 주수 × 종가 ÷ 1억 → 억원 변환
                BigDecimal instNetBuy = BigDecimal.ZERO;
                BigDecimal foreignNetBuy = BigDecimal.ZERO;

                if (instNetShares != null) {
                    instNetBuy = instNetShares.multiply(closePrice)
                            .divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP);
                }
                if (foreignNetShares != null) {
                    foreignNetBuy = foreignNetShares.multiply(closePrice)
                            .divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP);
                }

                log.info("[StockDetail] 네이버 투자자 매매동향 ({}) - 기관: {}억, 외국인: {}억",
                        dateText, instNetBuy, foreignNetBuy);

                return SupplyDemand.builder()
                        .volumePower(new BigDecimal("100")) // 기본값
                        .volumeSignal("NEUTRAL")
                        .foreignNetBuy(foreignNetBuy)
                        .instNetBuy(instNetBuy)
                        .programNetBuy(BigDecimal.ZERO)
                        .programTrend("FLAT")
                        .build();
            }

            log.warn("[StockDetail] 네이버 투자자 매매동향 파싱 실패 - 유효 데이터 행 없음");
        } catch (Exception e) {
            log.warn("[StockDetail] 네이버 투자자 매매동향 크롤링 실패: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 네이버 페이지 숫자 파싱 (쉼표, +/- 처리)
     */
    private BigDecimal parseNaverNumber(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            String cleaned = text.trim()
                    .replace(",", "")
                    .replace("+", "")
                    .replace("\u00A0", ""); // non-breaking space
            if (cleaned.isEmpty() || cleaned.equals("-")) return BigDecimal.ZERO;
            return new BigDecimal(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    // ========== Gemini AI 분석 ==========

    /**
     * Gemini 기반 AI 분석 (실제 데이터 기반)
     * 실패 시 null 반환 → 규칙기반 폴백
     */
    private AiAnalysis generateGeminiAnalysis(StockDetailDto dto) {
        try {
            String prompt = buildGeminiPrompt(dto);
            if (prompt == null) return null;

            // Gemini Only (Ollama 폴백 없음) - 실패 시 null → 규칙기반 폴백
            String response = geminiService.analyzeStockDetail(prompt);
            if (response == null) return null;

            log.info("[StockDetail] Gemini AI 분석 응답: {}", response);

            return parseGeminiResponse(response, dto);
        } catch (Exception e) {
            log.warn("[StockDetail] Gemini AI 분석 실패: {} - 규칙기반 폴백", e.getMessage());
            return null;
        }
    }

    /**
     * Gemini 프롬프트 구성 (실제 데이터 요약)
     */
    private String buildGeminiPrompt(StockDetailDto dto) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("종목: %s (%s)\n", dto.getStockName(), dto.getStockCode()));

        // 가격 정보
        if (dto.getPrice() != null) {
            PriceInfo p = dto.getPrice();
            sb.append(String.format("현재가: %s원, 등락률: %s%%, 거래량: %s\n",
                    p.getCurrentPrice(), p.getChangeRate(),
                    p.getTradingVolume() != null ? p.getTradingVolume() : "N/A"));
            if (p.getHigh() != null && p.getLow() != null) {
                sb.append(String.format("고가: %s, 저가: %s, 시가: %s\n",
                        p.getHigh(), p.getLow(), p.getOpen()));
            }
        } else {
            return null; // 가격 없으면 분석 불가
        }

        // 재무 정보
        if (dto.getFinancial() != null) {
            FinancialInfo f = dto.getFinancial();
            sb.append(String.format("PER: %s, PBR: %s, BPS: %s, 시가총액: %s억원\n",
                    f.getPer() != null ? f.getPer() : "N/A",
                    f.getPbr() != null ? f.getPbr() : "N/A",
                    f.getBps() != null ? f.getBps() : "N/A",
                    f.getMarketCap() != null ? f.getMarketCap() : "N/A"));
        }

        // 수급 정보
        if (dto.getSupplyDemand() != null) {
            SupplyDemand s = dto.getSupplyDemand();
            sb.append(String.format("외국인 순매수: %s억, 기관 순매수: %s억, 체결강도: %s%%\n",
                    s.getForeignNetBuy(), s.getInstNetBuy(), s.getVolumePower()));
        }

        // 리스크 정보
        if (dto.getRisk() != null) {
            RiskInfo r = dto.getRisk();
            sb.append(String.format("리스크상태: %s, 관련뉴스: %s건\n",
                    r.getRiskStatus(), r.getNewsCount()));
            // 뉴스 헤드라인 추가 (최대 3개)
            if (r.getNews() != null && !r.getNews().isEmpty()) {
                sb.append("주요뉴스: ");
                int count = 0;
                for (var news : r.getNews()) {
                    if (count >= 3) break;
                    sb.append(news.getTitle()).append("; ");
                    count++;
                }
                sb.append("\n");
            }
        }

        // 차트 기술적 지표
        if (dto.getChartData() != null) {
            ChartData c = dto.getChartData();
            sb.append(String.format("MA5: %s, MA20: %s, MA60: %s, VWAP: %s\n",
                    c.getMa5() != null ? c.getMa5() : "N/A",
                    c.getMa20() != null ? c.getMa20() : "N/A",
                    c.getMa60() != null ? c.getMa60() : "N/A",
                    c.getVwap() != null ? c.getVwap() : "N/A"));
        }

        // 수급-가격 괴리 감지
        if (dto.getPrice() != null && dto.getSupplyDemand() != null) {
            BigDecimal changeRate = dto.getPrice().getChangeRate();
            BigDecimal foreignNet = dto.getSupplyDemand().getForeignNetBuy();
            BigDecimal instNet = dto.getSupplyDemand().getInstNetBuy();
            boolean priceUp = changeRate != null && changeRate.doubleValue() > 1.0;
            boolean priceDown = changeRate != null && changeRate.doubleValue() < -1.0;
            boolean foreignSell = foreignNet != null && foreignNet.doubleValue() < -5;
            boolean instSell = instNet != null && instNet.doubleValue() < -5;
            boolean foreignBuy = foreignNet != null && foreignNet.doubleValue() > 5;
            boolean instBuy = instNet != null && instNet.doubleValue() > 5;

            if (priceUp && (foreignSell || instSell)) {
                sb.append("⚠ 경고: 주가 상승 중이지만 외국인/기관이 매도 중 (수급 괴리 - 개인 주도 상승 위험)\n");
            } else if (priceDown && (foreignBuy || instBuy)) {
                sb.append("💡 참고: 주가 하락 중이지만 외국인/기관이 매수 중 (기관 매집 가능성)\n");
            }
        }

        return sb.toString();
    }

    /**
     * Gemini 응답 텍스트 파싱 → AiAnalysis
     */
    private AiAnalysis parseGeminiResponse(String response, StockDetailDto dto) {
        // 추천 결정 파싱
        String recommendation = "HOLD";
        if (response.contains("매수") || response.contains("BUY") || response.contains("적극")) {
            recommendation = "BUY";
        } else if (response.contains("매도") || response.contains("SELL") || response.contains("회피")) {
            recommendation = "SELL";
        }

        // 점수 추정 (추천 기반)
        int score;
        switch (recommendation) {
            case "BUY": score = 75; break;
            case "SELL": score = 25; break;
            default: score = 50; break;
        }

        // 수급/재무 데이터로 점수 보정
        if (dto.getSupplyDemand() != null) {
            SupplyDemand s = dto.getSupplyDemand();
            if (s.getForeignNetBuy() != null && s.getForeignNetBuy().doubleValue() > 10) score += 5;
            if (s.getForeignNetBuy() != null && s.getForeignNetBuy().doubleValue() < -10) score -= 5;
            if (s.getInstNetBuy() != null && s.getInstNetBuy().doubleValue() > 10) score += 5;
            if (s.getInstNetBuy() != null && s.getInstNetBuy().doubleValue() < -10) score -= 5;
        }
        score = Math.max(0, Math.min(100, score));

        // 매수/매도 근거 추출 (응답에서 줄 단위 파싱)
        List<String> buyReasons = new ArrayList<>();
        List<String> sellReasons = new ArrayList<>();

        String[] lines = response.split("[\\n\\r]+");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("-") || trimmed.startsWith("•") || trimmed.matches("^\\d+\\..*")) {
                String content = trimmed.replaceFirst("^[-•]\\s*|^\\d+\\.\\s*", "").trim();
                if (content.length() > 5) {
                    if (content.contains("매수") || content.contains("긍정") || content.contains("호재")
                            || content.contains("상승") || content.contains("지지") || content.contains("매력")) {
                        buyReasons.add(content);
                    } else if (content.contains("리스크") || content.contains("주의") || content.contains("하락")
                            || content.contains("매도") || content.contains("부정") || content.contains("우려")) {
                        sellReasons.add(content);
                    } else {
                        // 매수/관망 추천이면 매수근거로, 매도면 매도근거로
                        if ("BUY".equals(recommendation)) {
                            buyReasons.add(content);
                        } else if ("SELL".equals(recommendation)) {
                            sellReasons.add(content);
                        }
                    }
                }
            }
        }

        // 기술적 신호
        String technicalSignal = "NEUTRAL";
        if (dto.getChartData() != null && dto.getPrice() != null && dto.getChartData().getMa20() != null) {
            BigDecimal cp = dto.getPrice().getCurrentPrice();
            BigDecimal ma20 = dto.getChartData().getMa20();
            if (cp != null && cp.compareTo(ma20) > 0) {
                technicalSignal = "이평선 상회";
            } else if (cp != null) {
                technicalSignal = "이평선 하향 이탈";
            }
        }

        return AiAnalysis.builder()
                .overallScore(score)
                .recommendation(recommendation)
                .strategy(response) // Gemini 전체 응답을 전략 텍스트로 사용
                .technicalSignal(technicalSignal)
                .buyReasons(buyReasons)
                .sellReasons(sellReasons)
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
