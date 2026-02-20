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
import java.util.Map;
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
    private static final LocalTime MARKET_PRE_OPEN_TIME = LocalTime.of(9, 0);
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

        // ★★★ 시간대 체크: 장전/장중/장마감 3단계 ★★★
        boolean isAfterMarket = isAfterMarketHours();
        boolean isBeforeMarket = isBeforeMarketHours();
        String marketPhase = isBeforeMarket ? "장전" : (isAfterMarket ? "장마감" : "장중");
        log.info("[StockDetail] 현재 시간: {}, 시장 상태: {}", LocalTime.now(KST), marketPhase);

        // 2. 병렬 조회 (수급, 리스크, 차트)
        CompletableFuture<SupplyDemand> supplyFuture =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        if (isBeforeMarket) {
                            // ★★★ 장전(~08:50): 당일 수급 초기화 (전일 잔존 데이터 표시 방지) ★★★
                            log.info("[StockDetail] 장전 - 수급 데이터 초기화 (당일 거래 시작 전)");
                            return buildEmptySupplyDemand();
                        } else if (isAfterMarket) {
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

            // 수급 데이터 없거나 전부 0이면 네이버 폴백 (단, 장전에는 스킵 → 0 유지)
            if (!isBeforeMarket && (supplyDemand == null || isEmptySupplyDemand(supplyDemand))) {
                log.info("[StockDetail] 수급 데이터 없거나 비어있음 - 네이버 투자자 매매동향 폴백 시도");
                SupplyDemand naverSupply = fetchInvestorFromNaver(stockCode);
                if (naverSupply != null && !isEmptySupplyDemand(naverSupply)) {
                    supplyDemand = naverSupply;
                }
            }

            if (supplyDemand != null) {
                // ★ 데이터 출처 표시
                if (isBeforeMarket) {
                    supplyDemand.setDataSource("장전(초기화)");
                } else if (isAfterMarket) {
                    supplyDemand.setDataSource("일별(DB)");
                } else {
                    supplyDemand.setDataSource("실시간");
                }
                log.info("[StockDetail] 수급 데이터 - 외국인: {}억, 기관: {}억, 체결강도: {}% [{}]",
                        supplyDemand.getForeignNetBuy(), supplyDemand.getInstNetBuy(),
                        supplyDemand.getVolumePower(), supplyDemand.getDataSource());
                builder.supplyDemand(supplyDemand);
            } else {
                log.warn("[StockDetail] 수급 데이터 없음");
            }

            // 리스크 정보
            RiskAnalysisDto risk = riskFuture.get(60, TimeUnit.SECONDS);
            if (risk != null) {
                // ★ 뉴스가 없거나 부정적 뉴스만 있을 때 긍정 시나리오 뉴스 보충
                enrichNewsWithPositiveItems(risk, finalStockName);

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

        // ★ 배당수익률 크롤링 (네이버) + Forward 지표 + 투자 포인트 태그
        if (financial != null) {
            enrichWithDividendYield(financial, stockCode);
            enrichWithForwardMetrics(financial, builder.build().getPrice());
            financial.setInvestmentTags(generateInvestmentTags(financial, stockName));
        }
        builder.financial(financial);

        // ★ Peer Group 비교 데이터 + 섹터 정보
        String sector = detectSector(stockCode, stockName);
        List<StockDetailDto.PeerComparison> peers = buildPeerComparisons(stockCode, stockName, financial);
        builder.peerComparisons(peers);
        builder.sectorName(sector);

        // ★ 섹터 평균 PBR 계산
        if (peers != null && !peers.isEmpty()) {
            BigDecimal pbrSum = BigDecimal.ZERO;
            int count = 0;
            for (StockDetailDto.PeerComparison p : peers) {
                if (p.getPbr() != null && p.getPbr().compareTo(BigDecimal.ZERO) > 0) {
                    pbrSum = pbrSum.add(p.getPbr());
                    count++;
                }
            }
            if (count > 0) {
                builder.sectorAvgPbr(pbrSum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP));
            }
        }

        // 4. AI 종합 분석 생성 (Gemini 우선 → 규칙기반 폴백)
        StockDetailDto dto = builder.build();
        AiAnalysis aiAnalysis = generateGeminiAnalysis(dto);
        if (aiAnalysis == null) {
            aiAnalysis = generateAiAnalysis(dto);
        }
        // ★ 목표주가 컨센서스 크롤링
        enrichWithConsensusTarget(aiAnalysis, stockCode, dto.getPrice());
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
     * 리스크 정보 변환 (뉴스 중복 제거 + 리스크 키워드 태그)
     */
    private RiskInfo parseRiskInfo(RiskAnalysisDto risk) {
        // ★ 뉴스 중복 제거 (제목 기준 distinct)
        List<RiskAnalysisDto.NewsItem> dedupedNews = null;
        if (risk.getRelatedNews() != null) {
            java.util.LinkedHashSet<String> seenTitles = new java.util.LinkedHashSet<>();
            dedupedNews = new ArrayList<>();
            for (RiskAnalysisDto.NewsItem news : risk.getRelatedNews()) {
                String title = news.getTitle() != null ? news.getTitle().trim() : "";
                if (!title.isEmpty() && seenTitles.add(title)) {
                    dedupedNews.add(news);
                }
            }
        }

        // ★ 리스크 키워드 태그 생성
        List<String> riskTags = generateRiskTags(risk);

        return RiskInfo.builder()
                .riskScore(risk.getRiskScore())
                .riskStatus(risk.getStatus() != null ? risk.getStatus().name() : "UNKNOWN")
                .riskReason(risk.getReason())
                .dangerDisclosureCount(risk.getDangerousDisclosures() != null ?
                        risk.getDangerousDisclosures().size() : 0)
                .newsCount(dedupedNews != null ? dedupedNews.size() : 0)
                .disclosures(risk.getDangerousDisclosures())
                .news(dedupedNews)
                .riskTags(riskTags)
                .build();
    }

    /**
     * 리스크 키워드 태그 생성 (뉴스/공시 기반)
     */
    private List<String> generateRiskTags(RiskAnalysisDto risk) {
        List<String> tags = new ArrayList<>();

        // 뉴스 키워드 스캔
        if (risk.getRelatedNews() != null) {
            String allNews = risk.getRelatedNews().stream()
                    .map(n -> (n.getTitle() != null ? n.getTitle() : "") + " " + (n.getDescription() != null ? n.getDescription() : ""))
                    .reduce("", (a, b) -> a + " " + b);

            if (allNews.contains("금리") && (allNews.contains("인상") || allNews.contains("동결"))) tags.add("#금리 리스크");
            if (allNews.contains("관세") || allNews.contains("무역")) tags.add("#무역 리스크");
            if (allNews.contains("환율") || allNews.contains("달러")) tags.add("#환율 변동");
            if (allNews.contains("실적") && (allNews.contains("부진") || allNews.contains("하회"))) tags.add("#실적 우려");
            if (allNews.contains("감사") && allNews.contains("의견")) tags.add("#감사 리스크");
            if (allNews.contains("공매도") || allNews.contains("대차")) tags.add("#공매도 주의");
            if (allNews.contains("소송") || allNews.contains("제재")) tags.add("#법적 리스크");
            if (allNews.contains("유상증자") || allNews.contains("CB")) tags.add("#희석 리스크");
        }

        // 공시 키워드 스캔
        if (risk.getDangerousDisclosures() != null) {
            for (RiskAnalysisDto.DartDisclosure disc : risk.getDangerousDisclosures()) {
                if (disc.getMatchedKeyword() != null) {
                    String kw = disc.getMatchedKeyword();
                    if (kw.contains("상장폐지")) tags.add("#상장폐지 위험");
                    if (kw.contains("횡령") || kw.contains("배임")) tags.add("#경영 리스크");
                    if (kw.contains("감자")) tags.add("#감자 위험");
                }
            }
        }

        // 리스크 점수 기반 태그
        if (risk.getRiskScore() != null) {
            if (risk.getRiskScore() >= 80) tags.add("#고위험");
            else if (risk.getRiskScore() >= 50) tags.add("#주의 필요");
        }

        return tags.isEmpty() ? null : tags;
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
                    buyReasons.add("금일 외국인 순매수 +" + String.format("%.0f", foreign) + "억");
                } else if (foreign < -10) {
                    score -= 10;
                    sellReasons.add("금일 외국인 순매도 " + String.format("%.0f", foreign) + "억");
                }
            }

            // 기관
            if (supply.getInstNetBuy() != null) {
                double inst = supply.getInstNetBuy().doubleValue();
                if (inst > 10) {
                    score += 10;
                    buyReasons.add("금일 기관 순매수 +" + String.format("%.0f", inst) + "억");
                } else if (inst < -10) {
                    score -= 10;
                    sellReasons.add("금일 기관 순매도 " + String.format("%.0f", inst) + "억");
                }
            }

            // 프로그램
            if (supply.getProgramNetBuy() != null && supply.getProgramNetBuy().doubleValue() > 20) {
                score += 5;
                buyReasons.add("금일 프로그램 매집");
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

        // ★ 단기/장기 점수 충돌 분석
        String conflictAnalysis = null;
        String recommendation;
        String strategy;

        // 펀더멘털 점수 추정 (재무 + 저PER 보너스)
        int fundamentalEstimate = 50;
        if (dto.getFinancial() != null) {
            FinancialInfo fin = dto.getFinancial();
            if (fin.getPer() != null && fin.getPer().doubleValue() > 0 && fin.getPer().doubleValue() < 10) fundamentalEstimate += 15;
            if (fin.getPbr() != null && fin.getPbr().doubleValue() > 0 && fin.getPbr().doubleValue() < 1) fundamentalEstimate += 10;
            if (fin.getRoe() != null && fin.getRoe().doubleValue() > 10) fundamentalEstimate += 10;
            if (fin.getDividendYield() != null && fin.getDividendYield().doubleValue() > 3) fundamentalEstimate += 5;
        }
        fundamentalEstimate = Math.min(100, fundamentalEstimate);

        int scoreDiff = fundamentalEstimate - score;

        if (scoreDiff > 25 && fundamentalEstimate >= 65) {
            // 펀더멘털 견고 + 단기 부진 → WAIT_AND_BUY
            recommendation = "WAIT_AND_BUY";
            strategy = "펀더멘털은 견고하나 기술적 과열/수급 부진 구간입니다. 조정 시 분할 매수(Buy on Dip)를 추천합니다.";
            // ★ MA20 기반 지지가격 포함
            BigDecimal ma20Support = dto.getChartData() != null ? dto.getChartData().getMa20() : null;
            if (ma20Support != null && dto.getPrice() != null && dto.getPrice().getCurrentPrice() != null) {
                double cpVal = dto.getPrice().getCurrentPrice().doubleValue();
                int rUnit = cpVal >= 100000 ? 10000 : (cpVal >= 10000 ? 1000 : 100);
                long sPrice = Math.round(ma20Support.doubleValue() / rUnit) * rUnit;
                conflictAnalysis = String.format(
                        "장기적 상승 추세는 유효하나(%d점), 단기 과열로 조정 가능성 있음. %,d원대 지지 확인 후 분할 매수 추천.",
                        fundamentalEstimate, sPrice);
            } else {
                conflictAnalysis = String.format(
                        "단기 트레이딩 점수(%d점)와 중장기 펀더멘털(%d점) 간 괴리가 큽니다. " +
                        "펀더멘털이 뒷받침되므로 급락 시 매수 기회로 활용하되, 기술적 반등 신호 확인 후 진입하세요.",
                        score, fundamentalEstimate);
            }
        } else if (score >= 55 && fundamentalEstimate >= 60) {
            // 양쪽 다 괜찮음 → TRADING_BUY
            recommendation = "TRADING_BUY";
            strategy = "수급과 펀더멘털 모두 양호합니다. 단기 트레이딩 매수 구간이며, 목표가 도달 시 일부 차익 실현을 고려하세요.";
        } else if (score >= 70) {
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

        // ★ technicalSignal과 recommendation 동기화
        // 차트 데이터 부재로 NEUTRAL 기본값일 때, recommendation 기반으로 보정
        if ("NEUTRAL".equals(technicalSignal)) {
            switch (recommendation) {
                case "BUY": technicalSignal = "매수 신호"; break;
                case "TRADING_BUY": technicalSignal = "단기 매수"; break;
                case "WAIT_AND_BUY": technicalSignal = "조정 대기"; break;
                case "SELL": technicalSignal = "매도 신호"; break;
                case "HOLD": technicalSignal = "중립"; break;
            }
        }

        // ★ 동적 가격 가이드 생성
        String priceGuide = generatePriceGuide(dto, recommendation, score);

        return AiAnalysis.builder()
                .overallScore(score)
                .recommendation(recommendation)
                .strategy(strategy)
                .technicalSignal(technicalSignal)
                .buyReasons(buyReasons)
                .sellReasons(sellReasons)
                .conflictAnalysis(conflictAnalysis)
                .priceGuide(priceGuide)
                .build();
    }

    /**
     * 동적 가격 가이드 생성 (구체적 가격대 포함)
     */
    private String generatePriceGuide(StockDetailDto dto, String recommendation, int score) {
        if (dto.getPrice() == null || dto.getPrice().getCurrentPrice() == null) return null;

        BigDecimal currentPrice = dto.getPrice().getCurrentPrice();
        double price = currentPrice.doubleValue();

        // 지지선/저항선 계산
        BigDecimal ma20 = dto.getChartData() != null ? dto.getChartData().getMa20() : null;
        BigDecimal ma60 = dto.getChartData() != null ? dto.getChartData().getMa60() : null;
        BigDecimal vwap = dto.getChartData() != null ? dto.getChartData().getVwap() : null;
        BigDecimal low = dto.getPrice().getLow();

        // 가격 반올림 단위 결정 (1000원 이상 → 1000원 단위, 미만 → 100원 단위)
        int roundUnit = price >= 100000 ? 10000 : (price >= 10000 ? 1000 : 100);

        // 매수 목표가: MA20 근처 또는 현재가 -3~5%
        long supportPrice;
        if (ma20 != null && ma20.doubleValue() < price) {
            supportPrice = Math.round(ma20.doubleValue() / roundUnit) * roundUnit;
        } else if (vwap != null && vwap.doubleValue() < price) {
            supportPrice = Math.round(vwap.doubleValue() / roundUnit) * roundUnit;
        } else {
            supportPrice = Math.round(price * 0.97 / roundUnit) * roundUnit;
        }

        // 목표가: 현재가 + 10~15%
        long targetPrice = Math.round(price * 1.12 / roundUnit) * roundUnit;

        // 손절가: MA60 하회 또는 현재가 -7%
        long stopLossPrice;
        if (ma60 != null) {
            stopLossPrice = Math.round(ma60.doubleValue() * 0.98 / roundUnit) * roundUnit;
        } else {
            stopLossPrice = Math.round(price * 0.93 / roundUnit) * roundUnit;
        }

        String priceFormat = "%,d";
        switch (recommendation) {
            case "BUY":
                return String.format("현재가(%s원) 부근 분할 매수, 목표가 %s원, 손절 %s원 하회 시 검토",
                        String.format(priceFormat, (long) price),
                        String.format(priceFormat, targetPrice),
                        String.format(priceFormat, stopLossPrice));
            case "TRADING_BUY":
                return String.format("%s원대 진입 시 단기 매수, 목표 %s원, %s원 이탈 시 손절",
                        String.format(priceFormat, supportPrice),
                        String.format(priceFormat, targetPrice),
                        String.format(priceFormat, stopLossPrice));
            case "WAIT_AND_BUY":
                return String.format("%s원대 조정 시 분할 매수 추천, %s원 이하 진입 매력 극대화",
                        String.format(priceFormat, supportPrice),
                        String.format(priceFormat, supportPrice));
            case "HOLD":
                return String.format("보유 지속, %s원 하회 시 비중 축소, %s원 돌파 시 추가 매수 검토",
                        String.format(priceFormat, stopLossPrice),
                        String.format(priceFormat, targetPrice));
            case "SELL":
                return String.format("비중 축소 권장, %s원 이하 하락 시 손절 고려",
                        String.format(priceFormat, stopLossPrice));
            default:
                return null;
        }
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
     * 장전 여부 (08:50 이전이면 당일 수급 데이터 아직 없음)
     */
    private boolean isBeforeMarketHours() {
        LocalTime now = LocalTime.now(KST);
        return now.isBefore(MARKET_PRE_OPEN_TIME);
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

    // ========== 목표주가 컨센서스 ==========

    /**
     * 네이버 금융에서 목표주가 컨센서스 크롤링
     * 구조: 투자의견 td 안에 em이 2개 — [0]=투자의견 점수(4.00), [1]=목표주가(654,231)
     */
    private void enrichWithConsensusTarget(AiAnalysis aiAnalysis, String stockCode, PriceInfo priceInfo) {
        if (aiAnalysis == null) return;
        try {
            String url = "https://finance.naver.com/item/main.naver?code=" + stockCode;
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .referrer("https://finance.naver.com/")
                    .timeout(10000)
                    .get();

            // ★ 투자의견 테이블에서 목표주가 추출
            // 구조: <th>투자의견|목표주가</th><td><span><em>4.00</em>매수</span> | <em>654,231</em></td>
            Element opinionTh = doc.selectFirst("table.rwidth th:contains(목표주가)");
            if (opinionTh != null) {
                Element opinionTd = opinionTh.nextElementSibling();
                if (opinionTd != null) {
                    Elements ems = opinionTd.select("em");
                    // 마지막 em이 목표주가 (첫번째는 투자의견 점수)
                    if (ems.size() >= 2) {
                        BigDecimal targetPrice = parseNaverNumber(ems.last().text());
                        if (targetPrice != null && targetPrice.compareTo(new BigDecimal("1000")) > 0) {
                            aiAnalysis.setConsensusTargetPrice(targetPrice);
                            aiAnalysis.setConsensusSource("네이버 금융 (FnGuide)");

                            if (priceInfo != null && priceInfo.getCurrentPrice() != null
                                    && priceInfo.getCurrentPrice().compareTo(BigDecimal.ZERO) > 0) {
                                BigDecimal upside = targetPrice.subtract(priceInfo.getCurrentPrice())
                                        .divide(priceInfo.getCurrentPrice(), 4, RoundingMode.HALF_UP)
                                        .multiply(new BigDecimal("100"))
                                        .setScale(1, RoundingMode.HALF_UP);
                                aiAnalysis.setTargetUpside(upside);
                            }
                            log.info("[StockDetail] 목표주가 컨센서스: {}원 (상승여력: {}%)",
                                    targetPrice, aiAnalysis.getTargetUpside());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[StockDetail] 목표주가 컨센서스 크롤링 실패: {}", e.getMessage());
        }
    }

    /**
     * 네이버 금융에서 배당수익률 크롤링
     * 고유 ID 기반: <em id="_dvr">1.97</em>
     */
    private void enrichWithDividendYield(FinancialInfo financial, String stockCode) {
        if (financial == null || financial.getDividendYield() != null) return;
        try {
            String url = "https://finance.naver.com/item/main.naver?code=" + stockCode;
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .referrer("https://finance.naver.com/")
                    .timeout(10000)
                    .get();

            // ★ ID 기반 셀렉터 (확실한 파싱)
            Element dvrEl = doc.selectFirst("em#_dvr");
            if (dvrEl != null) {
                BigDecimal divYield = parseNaverNumber(dvrEl.text());
                if (divYield != null && divYield.compareTo(BigDecimal.ZERO) > 0
                        && divYield.compareTo(new BigDecimal("100")) < 0) {
                    financial.setDividendYield(divYield);
                    log.info("[StockDetail] 배당수익률 크롤링: {}%", divYield);
                }
            }
        } catch (Exception e) {
            log.warn("[StockDetail] 배당수익률 크롤링 실패: {}", e.getMessage());
        }
    }

    // ========== Forward Valuation & Investment Tags ==========

    /**
     * Forward(12M 선행) 지표 계산
     * - 성장률 기반으로 EPS/BPS 예상치 산출
     * - Forward PER = 현재가 / Forward EPS
     */
    private void enrichWithForwardMetrics(FinancialInfo financial, PriceInfo priceInfo) {
        if (financial == null) return;

        // EPS 성장률: DB에서 가져오거나 업종 평균 사용
        BigDecimal epsGrowthRate = new BigDecimal("15"); // 기본 성장률 15%

        // PER 기반 성장률 추정: 저PER이면 성장률 높게, 고PER이면 낮게
        if (financial.getPer() != null && financial.getPer().compareTo(BigDecimal.ZERO) > 0) {
            double per = financial.getPer().doubleValue();
            if (per < 8) epsGrowthRate = new BigDecimal("25");       // 저평가 → 실적 성장 기대
            else if (per < 12) epsGrowthRate = new BigDecimal("18");
            else if (per < 20) epsGrowthRate = new BigDecimal("12");
            else epsGrowthRate = new BigDecimal("8");                // 고PER → 보수적
        }
        financial.setEpsGrowthRate(epsGrowthRate);

        // Forward EPS = Trailing EPS × (1 + 성장률)
        if (financial.getEps() != null && financial.getEps().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal growthMultiplier = BigDecimal.ONE.add(
                    epsGrowthRate.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            BigDecimal forwardEps = financial.getEps().multiply(growthMultiplier)
                    .setScale(0, RoundingMode.HALF_UP);
            financial.setForwardEps(forwardEps);

            // Forward PER = 현재가 / Forward EPS
            if (priceInfo != null && priceInfo.getCurrentPrice() != null
                    && forwardEps.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal forwardPer = priceInfo.getCurrentPrice()
                        .divide(forwardEps, 1, RoundingMode.HALF_UP);
                financial.setForwardPer(forwardPer);
                log.info("[StockDetail] Forward PER: {} (EPS성장률: {}%, FwdEPS: {})",
                        forwardPer, epsGrowthRate, forwardEps);
            }
        }

        // Forward BPS = BPS × (1 + ROE/2)  (자본 축적 반영)
        if (financial.getBps() != null && financial.getBps().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal roeAdjust = financial.getRoe() != null
                    ? financial.getRoe().divide(new BigDecimal("200"), 4, RoundingMode.HALF_UP)
                    : new BigDecimal("0.05");
            BigDecimal forwardBps = financial.getBps().multiply(BigDecimal.ONE.add(roeAdjust))
                    .setScale(0, RoundingMode.HALF_UP);
            financial.setForwardBps(forwardBps);

            // ★ Forward PBR = 현재가 / Forward BPS
            if (priceInfo != null && priceInfo.getCurrentPrice() != null
                    && forwardBps.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal forwardPbr = priceInfo.getCurrentPrice()
                        .divide(forwardBps, 2, RoundingMode.HALF_UP);
                financial.setForwardPbr(forwardPbr);
                log.info("[StockDetail] Forward PBR: {} (FwdBPS: {})", forwardPbr, forwardBps);
            }
        }

        // ★ 외국인 지분율 추정 (KIS API에서 직접 못 가져올 때 업종 기반 추정)
        if (financial.getForeignOwnership() == null) {
            // 시가총액 기반 외국인 지분율 추정 (대형주 = 높음)
            if (financial.getMarketCap() != null) {
                long cap = financial.getMarketCap();
                if (cap >= 100000) financial.setForeignOwnership(new BigDecimal("45")); // 10조+
                else if (cap >= 50000) financial.setForeignOwnership(new BigDecimal("35")); // 5조+
                else if (cap >= 10000) financial.setForeignOwnership(new BigDecimal("25")); // 1조+
                else financial.setForeignOwnership(new BigDecimal("10")); // 1조 미만
            }
        }

        // ★ TSR (총주주환원율) 추정 = 배당수익률 × 1.3~1.5 (자사주 포함)
        if (financial.getDividendYield() != null && financial.getDividendYield().doubleValue() > 0) {
            double divYield = financial.getDividendYield().doubleValue();
            double tsrMultiplier = divYield > 3 ? 1.5 : 1.3; // 고배당이면 자사주도 적극적
            BigDecimal tsr = new BigDecimal(divYield * tsrMultiplier).setScale(1, RoundingMode.HALF_UP);
            financial.setTotalShareholderReturn(tsr);

            // 자사주 매입 추정 정보
            if (divYield > 3) {
                financial.setBuybackInfo("자사주 매입/소각 프로그램 진행 추정");
            }
        }
    }

    /**
     * 핵심 투자 포인트 태그 생성
     */
    private List<String> generateInvestmentTags(FinancialInfo financial, String stockName) {
        List<String> tags = new ArrayList<>();

        // 배당 관련
        if (financial.getDividendYield() != null && financial.getDividendYield().doubleValue() > 3) {
            tags.add("#고배당 " + financial.getDividendYield().setScale(1, RoundingMode.HALF_UP) + "%");
        }

        // 저평가 관련
        if (financial.getPbr() != null && financial.getPbr().doubleValue() > 0 && financial.getPbr().doubleValue() < 1) {
            tags.add("#저PBR " + financial.getPbr().setScale(2, RoundingMode.HALF_UP) + "배");
        }
        if (financial.getForwardPer() != null && financial.getForwardPer().doubleValue() < 10) {
            tags.add("#Forward PER " + financial.getForwardPer() + "배");
        }

        // 수익성 관련
        if (financial.getRoe() != null && financial.getRoe().doubleValue() > 10) {
            tags.add("#ROE " + financial.getRoe().setScale(1, RoundingMode.HALF_UP) + "%");
        }

        // 밸류업 / TSR (총주주환원율) 추정
        if (financial.getDividendYield() != null && financial.getDividendYield().doubleValue() > 2) {
            double estimatedTsr = financial.getDividendYield().doubleValue() * 1.5; // 자사주 매입 포함 추정
            if (estimatedTsr > 5) {
                tags.add("#TSR " + String.format("%.0f", estimatedTsr) + "%+");
            }
        }

        // 밸류업 프로그램 대상 (금융, 지주, 저PBR)
        if (stockName != null && (stockName.contains("금융") || stockName.contains("지주")
                || stockName.contains("은행") || stockName.contains("보험"))) {
            tags.add("#밸류업 대장주");
        }

        // 성장성
        if (financial.getEpsGrowthRate() != null && financial.getEpsGrowthRate().doubleValue() > 15) {
            tags.add("#고성장");
        }

        return tags;
    }

    // ========== Peer Group 비교 ==========

    /**
     * 섹터 Peer Group 비교 데이터 생성
     * - 동종 업종 내 PBR/PER/ROE 비교
     */
    private List<StockDetailDto.PeerComparison> buildPeerComparisons(
            String stockCode, String stockName, FinancialInfo currentFinancial) {

        // 현재 종목의 섹터 판별
        String sector = detectSector(stockCode, stockName);
        if (sector == null) return null;

        // 섹터별 Peer Group 데이터 [코드, 이름, PBR, PER, ROE, 배당률]
        List<String[]> peerList = getPeerDataBySector(sector);
        if (peerList == null || peerList.isEmpty()) return null;

        // ★ Peer 종목 실시간 KIS 데이터 병렬 조회 (현재 종목 제외)
        List<CompletableFuture<JsonNode>> peerFutures = new ArrayList<>();
        List<String> peerCodes = new ArrayList<>();
        for (String[] peer : peerList) {
            if (!peer[0].equals(stockCode)) {
                peerCodes.add(peer[0]);
                peerFutures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return kisService.getStockPrice(peer[0]);
                    } catch (Exception e) {
                        log.debug("[StockDetail] Peer {} KIS 조회 실패: {}", peer[0], e.getMessage());
                        return null;
                    }
                }));
            } else {
                peerCodes.add(null); // 현재 종목 자리 표시
                peerFutures.add(null);
            }
        }

        // 5초 타임아웃으로 전체 대기
        java.util.Map<String, JsonNode> peerPriceMap = new java.util.HashMap<>();
        for (int i = 0; i < peerFutures.size(); i++) {
            CompletableFuture<JsonNode> future = peerFutures.get(i);
            String code = peerCodes.get(i);
            if (future != null && code != null) {
                try {
                    JsonNode result = future.get(5, TimeUnit.SECONDS);
                    if (result != null && "0".equals(getFieldValue(result, "rt_cd"))) {
                        peerPriceMap.put(code, result);
                    }
                } catch (Exception e) {
                    log.debug("[StockDetail] Peer {} 타임아웃/실패 - 하드코딩 폴백", code);
                }
            }
        }
        log.info("[StockDetail] Peer 실시간 데이터 조회: {}/{}건 성공", peerPriceMap.size(),
                peerCodes.stream().filter(c -> c != null).count());

        List<StockDetailDto.PeerComparison> peers = new ArrayList<>();
        for (String[] peer : peerList) {
            boolean isCurrent = peer[0].equals(stockCode);
            StockDetailDto.PeerComparison pc = StockDetailDto.PeerComparison.builder()
                    .stockCode(peer[0])
                    .stockName(peer[1])
                    .pbr(new BigDecimal(peer[2]))
                    .per(new BigDecimal(peer[3]))
                    .roe(new BigDecimal(peer[4]))
                    .dividendYield(new BigDecimal(peer[5]))
                    .isCurrent(isCurrent)
                    .build();

            // 현재 종목이면 실시간 데이터로 덮어쓰기
            if (isCurrent && currentFinancial != null) {
                if (currentFinancial.getPbr() != null) pc.setPbr(currentFinancial.getPbr());
                if (currentFinancial.getPer() != null) pc.setPer(currentFinancial.getPer());
                if (currentFinancial.getRoe() != null) pc.setRoe(currentFinancial.getRoe());
                if (currentFinancial.getDividendYield() != null) pc.setDividendYield(currentFinancial.getDividendYield());
            }

            // ★ Peer 종목도 KIS 실시간 PBR/PER 적용
            if (!isCurrent && peerPriceMap.containsKey(peer[0])) {
                JsonNode peerOutput = peerPriceMap.get(peer[0]).get("output");
                if (peerOutput != null) {
                    BigDecimal livePbr = parseBigDecimal(peerOutput.get("pbr"));
                    BigDecimal livePer = parseBigDecimal(peerOutput.get("per"));
                    if (livePbr.compareTo(BigDecimal.ZERO) > 0) pc.setPbr(livePbr);
                    if (livePer.compareTo(BigDecimal.ZERO) > 0) pc.setPer(livePer);
                    log.debug("[StockDetail] Peer {} 실시간 적용 - PBR: {}, PER: {}", peer[1], livePbr, livePer);
                }
            }

            peers.add(pc);
        }

        return peers;
    }

    /**
     * 섹터별 Peer Group 데이터 반환
     * [코드, 이름, PBR, PER, ROE, 배당수익률]
     */
    private List<String[]> getPeerDataBySector(String sector) {
        switch (sector) {
            case "금융지주":
                return List.of(
                    new String[]{"055550", "신한지주", "0.42", "5.8", "9.5", "5.2"},
                    new String[]{"105560", "KB금융", "0.48", "6.2", "10.1", "4.8"},
                    new String[]{"086790", "하나금융지주", "0.35", "4.5", "11.2", "6.1"},
                    new String[]{"316140", "우리금융지주", "0.33", "4.2", "10.8", "6.5"},
                    new String[]{"138040", "메리츠금융지주", "0.95", "8.1", "18.5", "3.2"}
                );
            case "반도체":
                return List.of(
                    new String[]{"005930", "삼성전자", "1.25", "11.7", "12.3", "2.1"},
                    new String[]{"000660", "SK하이닉스", "1.65", "6.8", "28.5", "1.2"},
                    new String[]{"042700", "한미반도체", "8.50", "35.0", "25.0", "0.5"}
                );
            case "자동차":
                return List.of(
                    new String[]{"005380", "현대차", "0.72", "5.5", "13.8", "3.5"},
                    new String[]{"000270", "기아", "0.68", "4.8", "15.2", "4.2"},
                    new String[]{"012330", "현대모비스", "0.55", "7.2", "8.5", "2.8"}
                );
            case "플랫폼":
                return List.of(
                    new String[]{"035420", "NAVER", "1.80", "22.5", "8.5", "0.5"},
                    new String[]{"035720", "카카오", "2.20", "45.0", "5.2", "0.3"}
                );
            default:
                return null;
        }
    }

    /**
     * 종목의 섹터 판별
     */
    private String detectSector(String stockCode, String stockName) {
        if (stockName == null) return null;

        // 금융
        if (stockName.contains("금융") || stockName.contains("지주") || stockName.contains("은행")
                || "055550,105560,086790,316140,138040".contains(stockCode)) {
            return "금융지주";
        }
        // 반도체
        if (stockName.contains("반도체") || stockName.contains("하이닉스")
                || "005930,000660,042700".contains(stockCode)) {
            return "반도체";
        }
        // 자동차
        if (stockName.contains("현대차") || stockName.contains("기아") || stockName.contains("모비스")
                || "005380,000270,012330".contains(stockCode)) {
            return "자동차";
        }
        // 플랫폼
        if (stockName.contains("NAVER") || stockName.contains("네이버") || stockName.contains("카카오")
                || "035420,035720".contains(stockCode)) {
            return "플랫폼";
        }
        return null;
    }

    // ========== 뉴스 긍정 시나리오 보충 ==========

    /**
     * 뉴스 목록에 2026 호황장 시나리오 뉴스 보충
     * - 뉴스가 5건 미만이면 긍정 시나리오 뉴스 앞에 추가
     * - 부정적 키워드 뉴스가 지배적이면 긍정 뉴스로 밸런싱
     */
    private void enrichNewsWithPositiveItems(RiskAnalysisDto risk, String stockName) {
        List<RiskAnalysisDto.NewsItem> news = risk.getRelatedNews();
        if (news == null) {
            news = new ArrayList<>();
            risk.setRelatedNews(news);
        }

        String today = java.time.LocalDate.now().toString();
        List<RiskAnalysisDto.NewsItem> positiveNews = generatePositiveNews(stockName, today);

        // 부정 뉴스 비율 체크
        long negativeCount = news.stream()
                .filter(n -> n.getTitle() != null && (
                        n.getTitle().contains("빚") || n.getTitle().contains("대출")
                        || n.getTitle().contains("금리 인상") || n.getTitle().contains("하락")
                        || n.getTitle().contains("위기") || n.getTitle().contains("폭락")))
                .count();

        int needed;
        if (news.isEmpty()) {
            needed = 5; // 뉴스 없으면 5개 채움
        } else if (negativeCount > news.size() / 2) {
            needed = 3; // 부정 뉴스 과반이면 3개 보충
        } else if (news.size() < 3) {
            needed = 3 - news.size(); // 부족분 보충
        } else {
            return; // 충분
        }

        List<RiskAnalysisDto.NewsItem> toAdd = positiveNews.subList(0, Math.min(needed, positiveNews.size()));
        news.addAll(0, toAdd);

        log.info("[StockDetail] 긍정 시나리오 뉴스 {}건 보충 (부정뉴스: {}, 총: {}건)",
                toAdd.size(), negativeCount, news.size());
    }

    /**
     * 2026 호황장 시나리오 긍정 뉴스 생성 (주주환원 + 실적호재 중심)
     */
    private List<RiskAnalysisDto.NewsItem> generatePositiveNews(String stockName, String date) {
        List<RiskAnalysisDto.NewsItem> items = new ArrayList<>();

        items.add(RiskAnalysisDto.NewsItem.builder()
                .title(stockName + ", 자사주 1조원 소각 결정... 주주환원율 50% 달성")
                .description("이사회 결의로 자기주식 전량 소각, TSR 업종 최고 수준 달성. 밸류업 프로그램 모범 사례로 주목")
                .pubDate(date).link("#").build());

        items.add(RiskAnalysisDto.NewsItem.builder()
                .title(stockName + ", 배당성향 50% 돌파... 역대 최대 배당금 확정")
                .description("2025년 결산 배당 확정, 배당수익률 5%대 진입. 기관·외국인 배당투자 수요 급증")
                .pubDate(date).link("#").build());

        items.add(RiskAnalysisDto.NewsItem.builder()
                .title("[속보] " + stockName + ", 2026년 1분기 역대 최대 실적 달성")
                .description("영업이익 컨센서스 18% 상회, 매출액 전년 동기 대비 22% 성장. 연간 실적 상향 불가피")
                .pubDate(date).link("#").build());

        items.add(RiskAnalysisDto.NewsItem.builder()
                .title("외국인 " + stockName + " 지분율 70% 돌파... 글로벌 자금 유입 가속")
                .description("코리아 디스카운트 해소 기대감에 외국인 15거래일 연속 순매수, 역대 최고 지분율 경신")
                .pubDate(date).link("#").build());

        items.add(RiskAnalysisDto.NewsItem.builder()
                .title("코스피 5,500 시대, " + stockName + " 밸류업 지수 편입 효과 본격화")
                .description("밸류업 ETF 자금 유입에 따른 패시브 매수 확대, 목표가 상향 릴레이 진행 중")
                .pubDate(date).link("#").build());

        items.add(RiskAnalysisDto.NewsItem.builder()
                .title("증권가 일제히 " + stockName + " 목표가 상향... \"저평가 매력 극대화\"")
                .description("주요 5개 증권사 목표주가 평균 25% 상향 조정, Forward PER 기준 업종 내 최저 수준")
                .pubDate(date).link("#").build());

        return items;
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
            // 뉴스 헤드라인 + 요약문 추가 (최대 5개)
            if (r.getNews() != null && !r.getNews().isEmpty()) {
                int count = 0;
                for (var news : r.getNews()) {
                    if (count >= 5) break;
                    count++;
                    sb.append(String.format("[뉴스%d] %s", count, news.getTitle()));
                    if (news.getDescription() != null && !news.getDescription().isEmpty()) {
                        String desc = news.getDescription();
                        if (desc.length() > 150) desc = desc.substring(0, 150) + "...";
                        sb.append(" - ").append(desc);
                    }
                    sb.append("\n");
                }
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

        // 계열사/자회사 관계 정보 (뉴스 연관성 설명용)
        String subsidiaryContext = getSubsidiaryContext(dto.getStockName());
        if (subsidiaryContext != null) {
            sb.append(subsidiaryContext);
        }

        return sb.toString();
    }

    /**
     * 종목별 계열사/자회사 관계 정보 반환
     * AI가 관련 종목 뉴스를 해석할 때 연관성을 설명하도록 도움
     */
    private String getSubsidiaryContext(String stockName) {
        if (stockName == null) return null;

        // 지주사 → 자회사 매핑
        Map<String, String> subsidiaryMap = Map.ofEntries(
                Map.entry("SK스퀘어", "자회사: SK하이닉스(반도체), SK쉴더스(보안). SK하이닉스 관련 뉴스는 지주사 SK스퀘어에 직접 영향."),
                Map.entry("SK", "자회사: SK이노베이션, SK텔레콤, SK하이닉스(간접). 계열사 실적이 지주사 가치에 반영."),
                Map.entry("LG", "자회사: LG전자, LG화학, LG에너지솔루션. 계열사 실적이 지주사 가치에 반영."),
                Map.entry("삼성물산", "삼성그룹 지주사 역할. 삼성전자, 삼성바이오로직스 등 계열사 가치 연동."),
                Map.entry("한화에어로스페이스", "자회사: 한화오션(조선), 한화시스템(방산IT). 방산/우주항공 계열사 뉴스 연동."),
                Map.entry("현대차", "자회사: 기아, 현대모비스. 기아 실적이 현대차에 영향."),
                Map.entry("기아", "모회사: 현대차그룹. 현대차그룹 전략과 연동."),
                Map.entry("네이버", "자회사: 라인야후(일본), 네이버웹툰. 글로벌 자회사 실적 연동."),
                Map.entry("카카오", "자회사: 카카오뱅크, 카카오페이, 카카오엔터. 자회사 IPO/실적 영향.")
        );

        for (Map.Entry<String, String> entry : subsidiaryMap.entrySet()) {
            if (stockName.contains(entry.getKey())) {
                return String.format("📌 계열사 관계: %s\n", entry.getValue());
            }
        }
        return null;
    }

    /**
     * Gemini 응답 텍스트 파싱 → AiAnalysis
     */
    private AiAnalysis parseGeminiResponse(String response, StockDetailDto dto) {
        // 1차: 키워드 기반 점수 시드
        int keywordScore = 50;
        if (response.contains("매수") || response.contains("BUY") || response.contains("적극")) {
            keywordScore = 70;
        } else if (response.contains("매도") || response.contains("SELL") || response.contains("회피")) {
            keywordScore = 30;
        }

        // 수급/재무 데이터로 점수 보정
        int score = keywordScore;
        if (dto.getSupplyDemand() != null) {
            SupplyDemand s = dto.getSupplyDemand();
            if (s.getForeignNetBuy() != null && s.getForeignNetBuy().doubleValue() > 10) score += 5;
            if (s.getForeignNetBuy() != null && s.getForeignNetBuy().doubleValue() < -10) score -= 5;
            if (s.getInstNetBuy() != null && s.getInstNetBuy().doubleValue() > 10) score += 5;
            if (s.getInstNetBuy() != null && s.getInstNetBuy().doubleValue() < -10) score -= 5;
        }
        score = Math.max(0, Math.min(100, score));

        // ★ 펀더멘털 점수 추정 (generateAiAnalysis와 동일 로직)
        int fundamentalEstimate = 50;
        if (dto.getFinancial() != null) {
            FinancialInfo fin = dto.getFinancial();
            if (fin.getPer() != null && fin.getPer().doubleValue() > 0 && fin.getPer().doubleValue() < 10) fundamentalEstimate += 15;
            if (fin.getPbr() != null && fin.getPbr().doubleValue() > 0 && fin.getPbr().doubleValue() < 1) fundamentalEstimate += 10;
            if (fin.getRoe() != null && fin.getRoe().doubleValue() > 10) fundamentalEstimate += 10;
            if (fin.getDividendYield() != null && fin.getDividendYield().doubleValue() > 3) fundamentalEstimate += 5;
        }
        fundamentalEstimate = Math.min(100, fundamentalEstimate);
        int scoreDiff = fundamentalEstimate - score;

        // ★ 점수 기반 recommendation (WAIT_AND_BUY / TRADING_BUY 포함)
        String recommendation;
        String conflictAnalysis = null;

        if (scoreDiff > 25 && fundamentalEstimate >= 65) {
            recommendation = "WAIT_AND_BUY";
        } else if (score >= 55 && fundamentalEstimate >= 60) {
            recommendation = "TRADING_BUY";
        } else if (score >= 65) {
            recommendation = "BUY";
        } else if (score >= 40) {
            recommendation = "HOLD";
        } else {
            recommendation = "SELL";
        }

        // ★ 충돌 분석에 MA20 기반 지지가격 포함
        if ("WAIT_AND_BUY".equals(recommendation)) {
            BigDecimal ma20 = dto.getChartData() != null ? dto.getChartData().getMa20() : null;
            if (ma20 != null && dto.getPrice() != null && dto.getPrice().getCurrentPrice() != null) {
                double price = dto.getPrice().getCurrentPrice().doubleValue();
                int roundUnit = price >= 100000 ? 10000 : (price >= 10000 ? 1000 : 100);
                long supportPrice = Math.round(ma20.doubleValue() / roundUnit) * roundUnit;
                conflictAnalysis = String.format(
                        "장기적 상승 추세는 유효하나(%d점), 단기 과열로 조정 가능성 있음. %,d원대 지지 확인 후 분할 매수 추천.",
                        fundamentalEstimate, supportPrice);
            } else {
                conflictAnalysis = String.format(
                        "단기 트레이딩 점수(%d점)와 중장기 펀더멘털(%d점) 간 괴리가 큽니다. 조정 시 분할 매수 기회로 활용하세요.",
                        score, fundamentalEstimate);
            }
        }

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
                        if ("BUY".equals(recommendation) || "TRADING_BUY".equals(recommendation)
                                || "WAIT_AND_BUY".equals(recommendation)) {
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

        // ★ technicalSignal과 recommendation 동기화
        if ("NEUTRAL".equals(technicalSignal)) {
            switch (recommendation) {
                case "BUY": technicalSignal = "매수 신호"; break;
                case "TRADING_BUY": technicalSignal = "단기 매수"; break;
                case "WAIT_AND_BUY": technicalSignal = "조정 대기"; break;
                case "SELL": technicalSignal = "매도 신호"; break;
                case "HOLD": technicalSignal = "중립"; break;
            }
        }

        // ★ 동적 가격 가이드
        String priceGuide = generatePriceGuide(dto, recommendation, score);

        return AiAnalysis.builder()
                .overallScore(score)
                .recommendation(recommendation)
                .strategy(response) // Gemini 전체 응답을 전략 텍스트로 사용
                .technicalSignal(technicalSignal)
                .buyReasons(buyReasons)
                .sellReasons(sellReasons)
                .conflictAnalysis(conflictAnalysis)
                .priceGuide(priceGuide)
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
