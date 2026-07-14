package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.*;
import com.myplatform.backend.dto.StockDetailDto.*;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
    private final StockFinancialDataRepository stockFinancialDataRepository;
    private final GeminiService geminiService;
    // 최근 5거래일 수급 재사용 — 장전 당일 0 대신 실수급을 AI 프롬프트에 주입(§4c). ObjectProvider 로 순환/미가용 안전.
    private final org.springframework.beans.factory.ObjectProvider<StockAnalysisService> stockAnalysisProvider;
    private final StockDetailCacheService cacheService;
    private final ChartSignalService chartSignalService;
    private final RedisCacheService redisCacheService;

    @Qualifier("stockDetailExecutor")
    private final Executor stockDetailExecutor;

    // Gemini 차트 해석 캐시 — 종목별 10분. 매 조회마다 LLM 호출하지 않게.
    private static final String GEMINI_CHART_CACHE = "geminiChartAnalysis";

    // 거래 시간 경계 — NXT(대체거래소) 반영: 08:00~20:00 사실상 거래.
    //   프리 08:00~08:50 · KRX 정규 09:00~15:30 · 애프터 15:30~20:00.
    //   (기존 09:00~15:30 KRX 기준 → 08:30 등 NXT 프리마켓에 "장전(초기화)"로 빈 데이터 표시되던 문제)
    private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(20, 0);   // NXT 애프터마켓 종료
    private static final LocalTime MARKET_PRE_OPEN_TIME = LocalTime.of(8, 0); // NXT 프리마켓 시작
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
        // ★ 화면 간 가격 불일치 해소: 목록(RecommendationService)과 동일한 공용 경로
        //    stockPriceService.getStockPrice() 사용 → priceCache + stock_price DB 공유.
        //    (기존: KIS 직접 호출로 목록과 다른 소스를 봐서 같은 종목이 화면마다 다른 가격/등락률)
        //    등락률 0·부호 보정도 StockPriceService 에 일원화되어 있음.
        String stockName = stockCode;  // 기본값 (종목코드)
        StockPriceDto priceDto = null;
        try {
            priceDto = stockPriceService.getStockPrice(stockCode);
        } catch (Exception e) {
            log.warn("[StockDetail] 공용 시세 조회 실패: {} - {}", stockCode, e.getMessage());
        }
        if (priceDto != null && priceDto.getCurrentPrice() != null) {
            builder.price(convertDtoToPriceInfo(priceDto));
            if (priceDto.getStockName() != null && !priceDto.getStockName().isEmpty()) {
                stockName = priceDto.getStockName();
            }
            log.info("[StockDetail] 종목명: {}, 현재가: {} (소스: {})",
                    stockName, priceDto.getCurrentPrice(), priceDto.getDataSource());
        } else {
            log.warn("[StockDetail] 현재가 조회 실패: {} - 종목명 별도 조회", stockCode);
            try {
                List<StockPriceDto> searchResult = stockPriceService.searchStocks(stockCode);
                if (searchResult != null && !searchResult.isEmpty()) {
                    stockName = searchResult.get(0).getStockName();
                }
            } catch (Exception e2) {
                log.debug("[StockDetail] 종목명 별도 조회 실패: {}", e2.getMessage());
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

        // 2. 병렬 조회 (수급, 리스크, 차트) — 전용 Executor 사용 (commonPool 고갈 방지)
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
                }, stockDetailExecutor);

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
                }, stockDetailExecutor);

        CompletableFuture<ChartData> chartFuture =
                CompletableFuture.supplyAsync(() -> fetchChartData(stockCode), stockDetailExecutor);

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
                // (2026-07-10) 가짜 긍정 뉴스 보충 제거 — 실뉴스 없으면 정직하게 없음(§4c).
                //   기존 enrichNewsWithPositiveItems 는 하드코딩 호재를 AI 프롬프트에 주입해 판단을 오염시켰음.
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

        // 3. 재무 정보 조회 (KIS 가격은 fetchFinancialInfo 내부에서 재조회, 실패 시 공용 DTO 폴백)
        FinancialInfo financial = fetchFinancialInfo(stockCode, null);
        if (financial == null && priceDto != null) {
            financial = convertNaverToFinancialInfo(priceDto);
            log.info("[StockDetail] 재무 정보 공용 시세 폴백 적용 - PER: {}, PBR: {}",
                    financial != null ? financial.getPer() : null,
                    financial != null ? financial.getPbr() : null);
        }

        // ★ 네이버 크롤링 (배당수익률 + 목표주가) + Forward 지표 + 태그
        Document naverMainDoc = fetchNaverMainPage(stockCode);

        // 네이버 메인 페이지 PER/PBR/EPS/BPS는 JavaScript 동적 로딩이라 Jsoup 스크래핑 불가
        // → TTM 계산(정확한 네이버 상장주식수 사용) 결과를 그대로 사용
        if (financial != null) {
            enrichWithDividendYieldFromDoc(financial, naverMainDoc, stockCode);
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
            // 폴백 경로에도 규칙 기반 차트 시그널 주입
            if (aiAnalysis != null) aiAnalysis.setChartSignals(chartSignalService.detect(dto));
        }
        // ★ 목표주가 컨센서스 (위에서 이미 크롤링한 doc 재사용)
        enrichWithConsensusTargetFromDoc(aiAnalysis, naverMainDoc, stockCode, dto.getPrice());
        dto.setAiAnalysis(aiAnalysis);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[StockDetail] 종목 {} 종합 상세 조회 완료: {}ms", stockCode, elapsed);

        return dto;
    }

    // ========== 점진적 로딩용 API (Quick + Heavy 분리) ==========

    /**
     * 1단계: 빠른 데이터 (시세 + 수급 + 차트 + 재무)
     * 외부 API 최소 호출 → 평균 3~5초 내 응답
     */
    public StockDetailDto getStockDetailQuick(String stockCode) {
        log.info("[StockDetail:Quick] 종목 {} 빠른 조회 시작", stockCode);
        long startTime = System.currentTimeMillis();

        StockDetailDto.StockDetailDtoBuilder builder = StockDetailDto.builder()
                .stockCode(stockCode)
                .fetchedAt(LocalDateTime.now());

        // ★ 시세 + 수급 + 차트 + 재무 모두 병렬 실행
        boolean isAfterMarket = isAfterMarketHours();
        boolean isBeforeMarket = isBeforeMarketHours();

        // 1. 시세 (비동기) — 전용 Executor
        // ★ 공용 경로(stockPriceService) 사용 → 목록 화면과 동일 캐시/DB 소스 (가격 불일치 해소)
        CompletableFuture<StockPriceDto> priceFuture =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return stockPriceService.getStockPrice(stockCode);
                    } catch (Exception e) {
                        log.warn("[StockDetail:Quick] 시세 조회 실패: {}", e.getMessage());
                        return null;
                    }
                }, stockDetailExecutor);

        // 2. 수급 (비동기)
        CompletableFuture<SupplyDemand> supplyFuture =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        if (isBeforeMarket) return buildEmptySupplyDemand();
                        else if (isAfterMarket) return getDailySummaryFromDb(stockCode);
                        else return parseSupplyDemand(scalpingService.getScalpingAnalysis(stockCode));
                    } catch (Exception e) {
                        log.error("[StockDetail:Quick] 수급 조회 실패: {}", e.getMessage());
                        return null;
                    }
                }, stockDetailExecutor);

        // 3. 차트 (비동기 + 캐시 — 별도 빈이라 @Cacheable 동작)
        CompletableFuture<ChartData> chartFuture =
                CompletableFuture.supplyAsync(() -> cacheService.getCachedChartData(stockCode), stockDetailExecutor);

        // 4. 재무 (비동기 + 캐시 — 별도 빈이라 @Cacheable 동작)
        CompletableFuture<FinancialInfo> financialFuture =
                CompletableFuture.supplyAsync(() -> cacheService.getCachedFinancialInfo(stockCode), stockDetailExecutor);

        // ★ 모든 Future 결과 수집
        try {
            // 시세 처리 — 공용 StockPriceDto (목록과 동일 소스)
            String stockName = stockCode;
            StockPriceDto priceDto = priceFuture.get(15, TimeUnit.SECONDS);
            if (priceDto != null && priceDto.getCurrentPrice() != null) {
                builder.price(convertDtoToPriceInfo(priceDto));
                if (priceDto.getStockName() != null && !priceDto.getStockName().isEmpty()) {
                    stockName = priceDto.getStockName();
                }
            }
            builder.stockName(stockName);

            // 수급 처리
            SupplyDemand supplyDemand = supplyFuture.get(15, TimeUnit.SECONDS);
            if (!isBeforeMarket && (supplyDemand == null || isEmptySupplyDemand(supplyDemand))) {
                try {
                    SupplyDemand naverSupply = fetchInvestorFromNaver(stockCode);
                    if (naverSupply != null && !isEmptySupplyDemand(naverSupply)) supplyDemand = naverSupply;
                } catch (Exception e) { /* skip */ }
            }
            if (supplyDemand != null) {
                if (isBeforeMarket) supplyDemand.setDataSource("장전(초기화)");
                else if (isAfterMarket) supplyDemand.setDataSource("일별(DB)");
                else supplyDemand.setDataSource("실시간");
                builder.supplyDemand(supplyDemand);
            }

            // 차트 처리
            ChartData chart = chartFuture.get(15, TimeUnit.SECONDS);
            if (chart != null) builder.chartData(chart);

            // 재무 처리 (Quick 경량 버전 — KIS PER/PBR만, 네이버 크롤링 없음)
            FinancialInfo financial = financialFuture.get(15, TimeUnit.SECONDS);
            builder.financial(financial);

        } catch (Exception e) {
            log.warn("[StockDetail:Quick] 병렬 조회 오류: {}", e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[StockDetail:Quick] 종목 {} 빠른 조회 완료: {}ms", stockCode, elapsed);
        return builder.build();
    }

    /**
     * 2단계: 무거운 데이터 (리스크 + AI분석 + 피어비교)
     * 캐시 적극 활용 → 캐시 히트 시 1초 이내, 미스 시 10~30초
     */
    public Map<String, Object> getStockDetailHeavy(String stockCode) {
        log.info("[StockDetail:Heavy] 종목 {} 무거운 데이터 조회 시작", stockCode);
        long startTime = System.currentTimeMillis();

        Map<String, Object> result = new HashMap<>();

        // 종목명 확보 (AI 프롬프트용) + 시세 — 공용 경로(목록과 동일 소스)
        String stockName = stockCode;
        PriceInfo priceInfo = null;
        try {
            StockPriceDto priceDto = stockPriceService.getStockPrice(stockCode);
            if (priceDto != null && priceDto.getCurrentPrice() != null) {
                if (priceDto.getStockName() != null && !priceDto.getStockName().isEmpty()) {
                    stockName = priceDto.getStockName();
                }
                priceInfo = convertDtoToPriceInfo(priceDto);
            }
        } catch (Exception e) {
            log.debug("[StockDetail:Heavy] 시세/종목명 조회 실패: {}", e.getMessage());
        }
        final String finalStockName = stockName;
        final PriceInfo finalPriceInfo = priceInfo;

        // ★ 리스크 + 네이버크롤링 + AI규칙기반 + 피어비교 모두 병렬 — 전용 Executor
        // Supplier 패턴으로 cacheService 프록시 경유 → @Cacheable 실제 동작
        CompletableFuture<RiskInfo> riskFuture =
                CompletableFuture.supplyAsync(() -> cacheService.getCachedRiskInfo(
                        stockCode, () -> fetchRiskInfo(finalStockName, stockCode)), stockDetailExecutor);

        CompletableFuture<Map<String, Object>> peerFuture =
                CompletableFuture.supplyAsync(() -> cacheService.getCachedPeerData(
                        stockCode, () -> fetchPeerData(stockCode, finalStockName)), stockDetailExecutor);

        // 네이버 크롤링 (배당수익률 + Forward 지표 + 목표주가) — Heavy로 이동
        CompletableFuture<Map<String, Object>> naverEnrichFuture =
                CompletableFuture.supplyAsync(() -> {
                    Map<String, Object> enrichData = new HashMap<>();
                    try {
                        Document naverMainDoc = fetchNaverMainPage(stockCode);
                        FinancialInfo financial = fetchFinancialInfo(stockCode, null);
                        if (financial != null) {
                            enrichWithDividendYieldFromDoc(financial, naverMainDoc, stockCode);
                            enrichWithForwardMetrics(financial, finalPriceInfo);
                            financial.setInvestmentTags(generateInvestmentTags(financial, finalStockName));
                        }
                        enrichData.put("financial", financial);
                        enrichData.put("naverDoc", naverMainDoc);
                    } catch (Exception e) {
                        log.warn("[StockDetail:Heavy] 네이버 크롤링 실패: {}", e.getMessage());
                    }
                    return enrichData;
                }, stockDetailExecutor);

        // AI 분석 (Gemini 또는 규칙기반)
        CompletableFuture<AiAnalysis> aiFuture =
                CompletableFuture.supplyAsync(() -> cacheService.getCachedAiAnalysis(
                        stockCode, () -> fetchAiAnalysis(stockCode)), stockDetailExecutor);

        try {
            RiskInfo riskInfo = riskFuture.get(60, TimeUnit.SECONDS);
            result.put("risk", riskInfo);

            Map<String, Object> peerData = peerFuture.get(30, TimeUnit.SECONDS);
            result.put("peerComparisons", peerData.get("peers"));
            result.put("sectorAvgPbr", peerData.get("sectorAvgPbr"));
            result.put("sectorName", peerData.get("sectorName"));

            // 네이버 크롤링 결과 (상세 재무 + 배당 + Forward)
            Map<String, Object> naverEnrich = naverEnrichFuture.get(30, TimeUnit.SECONDS);
            if (naverEnrich.get("financial") != null) {
                result.put("financial", naverEnrich.get("financial"));
            }

            AiAnalysis aiAnalysis = aiFuture.get(60, TimeUnit.SECONDS);
            if (aiAnalysis != null) {
                Document naverDoc = (Document) naverEnrich.get("naverDoc");
                enrichWithConsensusTargetFromDoc(aiAnalysis, naverDoc, stockCode, finalPriceInfo);
            }
            result.put("aiAnalysis", aiAnalysis);

        } catch (Exception e) {
            log.warn("[StockDetail:Heavy] 병렬 조회 오류: {}", e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[StockDetail:Heavy] 종목 {} 무거운 데이터 조회 완료: {}ms", stockCode, elapsed);
        return result;
    }

    // ========== 캐시 적용 메서드들 (cacheService를 통해서만 노출) ==========

    // 캐시는 cacheService.getCachedRiskInfo 래퍼가 담당 (self-invocation 방지)
    private RiskInfo fetchRiskInfo(String stockName, String stockCode) {
        try {
            RiskAnalysisDto risk = riskService.analyzeRisk(stockName, stockCode);
            if (risk != null) {
                // (2026-07-10) 가짜 긍정 뉴스 보충 제거(§4c) — 실뉴스만 사용.
                return parseRiskInfo(risk);
            }
        } catch (Exception e) {
            log.error("[StockDetail:Cache] 리스크 조회 실패: {}", e.getMessage());
        }
        return null;
    }

    // 캐시는 cacheService.getCachedPeerData 래퍼가 담당 (self-invocation 방지)
    private Map<String, Object> fetchPeerData(String stockCode, String stockName) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 피어 비교용 재무정보는 FnGuide 기반 rich 버전 사용 (ROE/배당까지 필요)
            // getCachedPeerData 자체가 cacheService 래퍼로 캐시되므로, 내부 직접 호출 OK
            FinancialInfo financial = fetchFinancialInfo(stockCode, null);

            String sector = detectSector(stockCode, stockName);
            List<StockDetailDto.PeerComparison> peers = buildPeerComparisons(stockCode, stockName, financial);
            result.put("peers", peers);
            result.put("sectorName", sector);

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
                    result.put("sectorAvgPbr", pbrSum.divide(BigDecimal.valueOf(count), 2, java.math.RoundingMode.HALF_UP));
                }
            }
        } catch (Exception e) {
            log.error("[StockDetail:Cache] 피어 조회 실패: {}", e.getMessage());
        }
        return result;
    }

    // 캐시는 cacheService.getCachedAiAnalysis 래퍼가 담당 (self-invocation 방지)
    private AiAnalysis fetchAiAnalysis(String stockCode) {
        try {
            // Quick 데이터로 StockDetailDto 구성
            StockDetailDto quickDto = getStockDetailQuick(stockCode);
            AiAnalysis aiAnalysis = generateGeminiAnalysis(quickDto);
            if (aiAnalysis == null) {
                aiAnalysis = generateAiAnalysis(quickDto);
                if (aiAnalysis != null) aiAnalysis.setChartSignals(chartSignalService.detect(quickDto));
            }
            return aiAnalysis;
        } catch (Exception e) {
            log.warn("[StockDetail:Cache] AI 분석 실패: {}", e.getMessage());
            return null;
        }
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
                .changeRate(displaySafeChangeRate(changeRate))   // 표시 계층 정제 — 손상 등락률(>40%)은 null(§4c)
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
     * ★ 체결강도가 null 또는 0이면 null 유지 — "데이터 없음"을 100%(균형)으로 위장하지 않는다.
     *   (과거 100 강제 변환이 화면 체결강도 항상-100% 버그의 한 축이었음)
     * ★ 외국인/기관/프로그램 값이 있으면 그대로 사용, 없으면 0
     */
    private SupplyDemand parseSupplyDemand(ScalpingAnalysisDto scalping) {
        if (scalping == null) {
            log.warn("[StockDetail] ScalpingAnalysisDto가 null - 기본값 반환");
            return buildEmptySupplyDemand();
        }

        // ★ 체결강도: 0 은 미수집 의미 — null 로 정규화 (프론트가 "데이터 없음" 표시)
        BigDecimal volumePower = scalping.getVolumePower();
        if (volumePower != null && volumePower.signum() == 0) {
            volumePower = null;
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
            // 120일 + 여유분으로 요청 (MA120 계산용)
            JsonNode dailyData = kisService.getDailyPrices(stockCode, 150);
            if (dailyData == null) return null;

            JsonNode output2 = dailyData.get("output2");
            if (output2 == null || !output2.isArray()) return null;

            List<CandlePoint> allCandles = new ArrayList<>();
            List<VolumePoint> allVolumes = new ArrayList<>();
            List<BigDecimal> allCloses = new ArrayList<>();

            for (JsonNode item : output2) {
                String date = item.has("stck_bsop_date") ? item.get("stck_bsop_date").asText() : "";
                BigDecimal close = parseBigDecimal(item.get("stck_clpr"));

                allCandles.add(CandlePoint.builder()
                        .date(date)
                        .open(parseBigDecimal(item.get("stck_oprc")))
                        .high(parseBigDecimal(item.get("stck_hgpr")))
                        .low(parseBigDecimal(item.get("stck_lwpr")))
                        .close(close)
                        .build());

                allVolumes.add(VolumePoint.builder()
                        .date(date)
                        .volume(parseLong(item.get("acml_vol")))
                        .build());

                allCloses.add(close);
            }

            // 표시용 캔들 (최근 60개)
            int displayCount = Math.min(60, allCandles.size());
            List<CandlePoint> candles = allCandles.subList(0, displayCount);
            List<VolumePoint> volumes = allVolumes.subList(0, displayCount);

            // 현재값 이동평균
            BigDecimal ma5 = calculateMA(allCloses, 5);
            BigDecimal ma20 = calculateMA(allCloses, 20);
            BigDecimal ma60 = calculateMA(allCloses, 60);

            // 이동평균선 배열 (각 캔들별 MA값, 차트 오버레이용)
            List<BigDecimal> maLine5 = calculateMALine(allCloses, 5, displayCount);
            List<BigDecimal> maLine20 = calculateMALine(allCloses, 20, displayCount);
            List<BigDecimal> maLine60 = calculateMALine(allCloses, 60, displayCount);
            List<BigDecimal> maLine120 = calculateMALine(allCloses, 120, displayCount);

            // 볼린저밴드 (20일 기준)
            List<BigDecimal> bbUpper = new ArrayList<>();
            List<BigDecimal> bbLower = new ArrayList<>();
            for (int i = 0; i < displayCount; i++) {
                if (i + 20 <= allCloses.size()) {
                    List<BigDecimal> window = allCloses.subList(i, i + 20);
                    BigDecimal mean = window.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(20), 2, RoundingMode.HALF_UP);
                    double variance = window.stream()
                            .mapToDouble(p -> Math.pow(p.subtract(mean).doubleValue(), 2))
                            .average().orElse(0);
                    BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance)).setScale(2, RoundingMode.HALF_UP);
                    bbUpper.add(mean.add(stdDev.multiply(BigDecimal.valueOf(2))));
                    bbLower.add(mean.subtract(stdDev.multiply(BigDecimal.valueOf(2))));
                } else {
                    bbUpper.add(null);
                    bbLower.add(null);
                }
            }

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
                    .ma5(ma5).ma20(ma20).ma60(ma60)
                    .vwap(vwap)
                    .maLine5(maLine5).maLine20(maLine20).maLine60(maLine60).maLine120(maLine120)
                    .bbUpper(bbUpper).bbLower(bbLower)
                    .build();

        } catch (Exception e) {
            log.warn("[StockDetail] 차트 데이터 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 재무 정보 조회
     */
    /**
     * 재무 정보 조회 — TTM 연결 재무제표 기준으로 통일
     *
     * 1순위: FnGuide (PER/PBR/EPS/BPS/ROE/영업이익률/부채비율 — 연결 기준)
     * 2순위: KIS API (시가총액/외국인지분 + FnGuide 실패 시 폴백)
     */
    private FinancialInfo fetchFinancialInfo(String stockCode, JsonNode priceData) {
        try {
            // KIS 가격 데이터 (시가총액, 외국인지분용)
            if (priceData == null || !"0".equals(priceData.path("rt_cd").asText())) {
                priceData = kisService.getStockPrice(stockCode);
            }
            if (priceData == null) return null;

            JsonNode output = priceData.get("output");
            if (output == null) return null;

            BigDecimal currentPrice = parseBigDecimal(output.get("stck_prpr"));
            Long marketCap = parseLong(output.get("hts_avls"));
            BigDecimal foreignOwnership = parseBigDecimal(output.get("hts_frgn_ehrt"));

            // ★★★ 1순위: FnGuide에서 PER/PBR/EPS/BPS/ROE 등 전체 재무지표 조회
            BigDecimal per = null, pbr = null, eps = null, bps = null;
            BigDecimal roe = null, operatingMargin = null, netMargin = null, debtRatio = null;

            try {
                Map<String, BigDecimal> fnData = fetchFnGuideMetrics(stockCode);
                if (fnData != null && !fnData.isEmpty()) {
                    eps = fnData.get("eps");
                    bps = fnData.get("bps");
                    per = fnData.get("per");
                    pbr = fnData.get("pbr");
                    roe = fnData.get("roe");
                    operatingMargin = fnData.get("operatingMargin");
                    netMargin = fnData.get("netMargin");
                    debtRatio = fnData.get("debtRatio");

                    log.info("[StockDetail] {} ★FnGuide 1순위: PER={}, PBR={}, EPS={}, BPS={}, ROE={}",
                            stockCode, per, pbr, eps, bps, roe);
                }
            } catch (Exception e) {
                log.warn("[StockDetail] {} FnGuide 조회 실패: {}", stockCode, e.getMessage());
            }

            // ★ 2순위 폴백: FnGuide 실패 시 KIS 데이터 사용
            if (eps == null) {
                eps = parseBigDecimal(output.get("eps"));
                log.info("[StockDetail] {} EPS 폴백 → KIS: {}", stockCode, eps);
            }
            if (bps == null) bps = parseBigDecimal(output.get("bps"));

            // ★ 3순위: PER/PBR 직접 계산 (주가 ÷ EPS = PER, 주가 ÷ BPS = PBR)
            if (per == null && currentPrice != null && eps != null && eps.compareTo(BigDecimal.ZERO) > 0) {
                per = currentPrice.divide(eps, 1, RoundingMode.HALF_UP);
                log.info("[StockDetail] {} PER 직접 계산: {} (주가: {} ÷ EPS: {})", stockCode, per, currentPrice, eps);
            }
            if (pbr == null && currentPrice != null && bps != null && bps.compareTo(BigDecimal.ZERO) > 0) {
                pbr = currentPrice.divide(bps, 2, RoundingMode.HALF_UP);
                log.info("[StockDetail] {} PBR 직접 계산: {} (주가: {} ÷ BPS: {})", stockCode, pbr, currentPrice, bps);
            }
            // 최종 폴백: KIS PER/PBR
            if (per == null) per = parseBigDecimal(output.get("per"));
            if (pbr == null) pbr = parseBigDecimal(output.get("pbr"));

            // ROE/마진 폴백: FnGuide 실패 시 DB 데이터 사용
            if (roe == null || operatingMargin == null || debtRatio == null) {
                try {
                    List<StockFinancialData> dbDataList = stockFinancialDataRepository
                            .findByStockCodeOrderByReportDateDesc(stockCode);
                    if (!dbDataList.isEmpty()) {
                        for (StockFinancialData hist : dbDataList) {
                            if (roe == null && hist.getRoe() != null) roe = hist.getRoe();
                            if (operatingMargin == null && hist.getOperatingMargin() != null) operatingMargin = hist.getOperatingMargin();
                            if (netMargin == null && hist.getNetMargin() != null) netMargin = hist.getNetMargin();
                            if (debtRatio == null && hist.getDebtRatio() != null) debtRatio = hist.getDebtRatio();
                            if (roe != null && operatingMargin != null && debtRatio != null) break;
                        }
                        log.info("[StockDetail] {} DB 폴백 비율: ROE={}, 영업이익률={}, 부채비율={}",
                                stockCode, roe, operatingMargin, debtRatio);
                    }
                } catch (Exception e) {
                    log.warn("[StockDetail] {} DB 데이터 조회 실패: {}", stockCode, e.getMessage());
                }
            }

            return FinancialInfo.builder()
                    .per(per)
                    .pbr(pbr)
                    .eps(eps)
                    .bps(bps)
                    .roe(roe)
                    .operatingMargin(operatingMargin)
                    .netMargin(netMargin)
                    .debtRatio(debtRatio)
                    .marketCap(marketCap)
                    .foreignOwnership(foreignOwnership != null && foreignOwnership.compareTo(BigDecimal.ZERO) > 0
                            ? foreignOwnership : null)
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
            double dailyChangeRate = dto.getPrice().getChangeRate() != null ? dto.getPrice().getChangeRate().doubleValue() : 0;

            // ★ 당일 급등(+5% 이상) 시 강한 돌파로 우선 분류
            if (currentPrice != null && dailyChangeRate >= 5.0) {
                if (chart.getMa20() != null && currentPrice.compareTo(chart.getMa20()) > 0) {
                    technicalSignal = "강한 돌파 상승 (추세 추종)";
                    buyReasons.add(String.format("당일 +%.1f%% 급등 + 20일선 상회 (강한 상승 모멘텀)", dailyChangeRate));
                    score += 12;
                } else {
                    technicalSignal = "급등 돌파 (강세)";
                    buyReasons.add(String.format("당일 +%.1f%% 급등 (단기 모멘텀 강세)", dailyChangeRate));
                    score += 10;
                }
            } else if (chart.getMa20() != null && currentPrice != null) {
                if (currentPrice.compareTo(chart.getMa20().multiply(new BigDecimal("1.05"))) >= 0) {
                    technicalSignal = "20일선 상향 돌파 (강세)";
                    buyReasons.add("20일선 5% 이상 상회 (강한 상승 추세)");
                    score += 8;
                } else if (currentPrice.compareTo(chart.getMa20()) > 0) {
                    technicalSignal = "눌림목 구간";
                    buyReasons.add("20일선 지지 (눌림목)");
                    score += 5;
                } else {
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
                String sellers = (foreignNet < -5 && instNet < -5) ? "외인/기관" :
                                 (foreignNet < -5) ? "외인" : "기관";
                score -= 15;
                sellReasons.add("⚠ 수급 괴리: 주가 상승(+" + String.format("%.1f", changeRate) + "%) 중 " + sellers + " 매도 → 개인 주도 상승 위험");
            } else if (changeRate < -1.0 && (foreignNet > 5 || instNet > 5)) {
                String buyers = (foreignNet > 5 && instNet > 5) ? "외인/기관" :
                                (foreignNet > 5) ? "외인" : "기관";
                score += 5;
                buyReasons.add(buyers + " 매집: 주가 하락 중 " + buyers + " 매수 → 저점 매집 가능성");
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

        // ★ technicalSignal과 recommendation 동기화 (규칙기반 폴백 — Gemini 본문 없음이라 bodyVerdict=null).
        //   parseGeminiResponse 와 동일한 순수 로직 재사용("수급 강세" 미스노머 제거).
        technicalSignal = resolveTechnicalSignal(technicalSignal, recommendation, null);

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

    /** 각 캔들 위치별 이동평균값 배열 생성 (차트 오버레이용) */
    private List<BigDecimal> calculateMALine(List<BigDecimal> allCloses, int period, int displayCount) {
        List<BigDecimal> line = new ArrayList<>();
        for (int i = 0; i < displayCount; i++) {
            if (i + period <= allCloses.size()) {
                BigDecimal sum = BigDecimal.ZERO;
                for (int j = i; j < i + period; j++) {
                    sum = sum.add(allCloses.get(j));
                }
                line.add(sum.divide(BigDecimal.valueOf(period), 2, RoundingMode.HALF_UP));
            } else {
                line.add(null);
            }
        }
        return line;
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

        // ★ Freshness check: 최신 거래일이 4일 이상 오래된 경우 경고
        long dataAgeDays = java.time.temporal.ChronoUnit.DAYS.between(targetDate, today);
        if (dataAgeDays >= 4) {
            log.warn("[StockDetail] ⚠ 수급 데이터 오래됨! 최신 거래일: {} ({}일 전) - 종목: {}", targetDate, dataAgeDays, stockCode);
        }

        log.info("[StockDetail] DB 일별 데이터 조회 - 종목: {}, 거래일: {} ({}일 전)", stockCode, targetDate, dataAgeDays);

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

        // 체결강도 없으면 null 유지 — 100(균형) 위장 금지. 프론트가 "데이터 없음" 표시.
        if (volumePower != null && volumePower.signum() == 0) {
            volumePower = null;
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
                .volumePower(null)  // 데이터 없음 — 100(균형) 위장 금지
                .volumeSignal("NEUTRAL")
                .foreignNetBuy(BigDecimal.ZERO)
                .instNetBuy(BigDecimal.ZERO)
                .programNetBuy(BigDecimal.ZERO)
                .programTrend("FLAT")
                .build();
    }

    // ========== 네이버 폴백 변환 ==========

    /**
     * 표시 계층 등락률 정제(P3-6/prdy_ctrt 손상 대응) — 순수 함수(테스트 대상).
     *
     * <p>KRX 일일 변동제한은 ±30%인데 손상된 {@code prdy_ctrt}(예: 011930 900.00%)가 그대로 표시되던
     * 문제 대응. |등락률| &gt; 40%(변동제한 30% + 오탐 방지 여유)면 <b>손상 필드로 보고 null</b> — §4c(그럴듯한
     * 값으로 위장 금지, 프론트가 '—' 렌더). <b>표시 DTO(PriceInfo) 한정</b>이라 저장값(stock_price)·시세 단일경로
     * ·시그널/봇(모두 {@code StockPriceDto.changeRate} 직접 소비)에는 영향 없다.
     */
    static final BigDecimal DISPLAY_MAX_ABS_CHANGE_RATE = new BigDecimal("40");

    static BigDecimal displaySafeChangeRate(BigDecimal rate) {
        if (rate == null) return null;
        return rate.abs().compareTo(DISPLAY_MAX_ABS_CHANGE_RATE) > 0 ? null : rate;
    }

    /**
     * StockPriceDto → PriceInfo 변환.
     *
     * <p>원래 네이버 폴백 전용이었으나, 화면 간 가격 불일치(목록 vs 상세) 해소를 위해
     * 상세 1차 시세도 {@code StockPriceService.getStockPrice()}(공용 캐시/DB/API 폴백 +
     * 등락률 0·부호 보정 일원화) 를 거치도록 통일하면서 공용 변환기로 승격.
     * KIS/네이버 어느 소스의 DTO든 동일하게 변환한다.
     */
    private PriceInfo convertDtoToPriceInfo(StockPriceDto naverData) {
        // 표시 계층 정제 — 손상 등락률(>40%)은 null. prevClose 역산·표시 모두 이 값을 쓴다(오염 전파 차단).
        BigDecimal safeRate = displaySafeChangeRate(naverData.getChangeRate());
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
        } else if (naverData.getCurrentPrice() != null && safeRate != null
                && safeRate.compareTo(BigDecimal.ZERO) != 0) {
            // changePrice 없으면 changeRate로 역산 (손상 등락률이면 safeRate=null → 역산 스킵, prevClose null 유지)
            BigDecimal rate = safeRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
            prevClose = naverData.getCurrentPrice().divide(BigDecimal.ONE.add(rate), 0, RoundingMode.HALF_UP);
        }

        BigDecimal changePrice = naverData.getChangePrice();
        if (changePrice == null && prevClose != null && naverData.getCurrentPrice() != null) {
            changePrice = naverData.getCurrentPrice().subtract(prevClose);
        }

        return PriceInfo.builder()
                .currentPrice(naverData.getCurrentPrice())
                .changePrice(changePrice)
                .changeRate(safeRate)
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

    // ========== 네이버 상장주식수 크롤링 ==========

    /**
     * 네이버 기업개요 페이지에서 상장주식수 크롤링
     * URL: https://finance.naver.com/item/coinfo.naver?code={stockCode}
     * HTML: <th>상장주식수</th><td><em>46,290,951</em></td>
     */
    private BigDecimal fetchNaverListedShares(String stockCode) {
        try {
            Document doc = Jsoup.connect("https://finance.naver.com/item/coinfo.naver?code=" + stockCode)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .referrer("https://finance.naver.com/")
                    .timeout(10000)
                    .get();

            Element th = doc.selectFirst("th:contains(상장주식수)");
            if (th != null) {
                Element td = th.nextElementSibling();
                if (td != null) {
                    Element em = td.selectFirst("em");
                    String text = (em != null) ? em.text() : td.text();
                    BigDecimal shares = parseNaverNumber(text);
                    if (shares != null && shares.compareTo(BigDecimal.ZERO) > 0) {
                        log.info("[StockDetail] {} 네이버 상장주식수: {}", stockCode, shares);
                        return shares;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[StockDetail] {} 네이버 상장주식수 크롤링 실패: {}", stockCode, e.getMessage());
        }
        return null;
    }

    // ========== FnGuide 재무지표 크롤링 (1순위 소스) ==========

    /**
     * FnGuide에서 PER/PBR/EPS/BPS/ROE/영업이익률/부채비율 조회 (IFRS 연결 기준)
     * URL: https://comp.fnguide.com/SVO2/ASP/SVD_main.asp?pGB=1&gicode=A{code}
     * HTML 구조: div#highlight_D_A 내 테이블에서 최신 연간 데이터 추출
     */
    private Map<String, BigDecimal> fetchFnGuideMetrics(String stockCode) {
        try {
            String url = "https://comp.fnguide.com/SVO2/ASP/SVD_main.asp?pGB=1&gicode=A" + stockCode;
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .referrer("https://comp.fnguide.com/")
                    .timeout(15000)
                    .get();

            Map<String, BigDecimal> metrics = new HashMap<>();

            // ★ 상단 박스에서 PER, PBR 추출 (trailing 기준 — 가장 신뢰도 높음)
            // 구조: <a id="h_per">PER</a> ... <dd>59.91</dd>
            BigDecimal topPer = extractFnGuideTopValue(doc, "h_per");
            BigDecimal topPbr = extractFnGuideTopValue(doc, "h_pbr");

            // ★ highlight_D_A (IFRS 연결, Annual) 테이블에서 최신 결산 EPS/BPS/ROE 등
            Element highlightTable = doc.selectFirst("div#highlight_D_A table");
            if (highlightTable != null) {
                // 헤더에서 최신 실적 연도 컬럼 인덱스 찾기 (E 추정치 제외)
                Elements headerCells = highlightTable.select("thead tr.td_gapcolor2 th");
                int latestCol = -1;
                for (int i = 0; i < headerCells.size(); i++) {
                    String headerText = headerCells.get(i).text().trim();
                    // Annual 영역(앞 4개)에서 (E) 아닌 최신 컬럼
                    if (i < 4 && !headerText.contains("(E)") && headerText.matches("\\d{4}/\\d{2}")) {
                        latestCol = i;
                    }
                }
                if (latestCol < 0) latestCol = 2; // 기본값: 3번째 Annual 컬럼

                log.info("[StockDetail] {} FnGuide 최신 결산 컬럼: {} (인덱스: {})",
                        stockCode, latestCol < headerCells.size() ? headerCells.get(latestCol).text() : "?", latestCol);

                // 각 행에서 데이터 추출 (BPS/ROE/마진/부채비율은 Annual 테이블 사용)
                BigDecimal issuedShares = null; // 발행주식수 (천주) - 보통주만
                BigDecimal tableEps = null; // FnGuide 테이블 EPS(원) 직접값

                Elements rows = highlightTable.select("tbody tr");
                for (Element row : rows) {
                    String label = row.selectFirst("th") != null ? row.selectFirst("th").text().trim() : "";
                    Elements tds = row.select("td");
                    if (tds.size() <= latestCol) continue;

                    String cellText = tds.get(latestCol).text().trim();
                    BigDecimal value = parseFnGuideNumber(cellText);
                    if (value == null) continue;

                    if (label.startsWith("발행주식수") && label.contains("보통주")) {
                        // 보통주 발행주식수만 사용 (우선주 제외)
                        issuedShares = value;
                    } else if (label.startsWith("발행주식수") && issuedShares == null) {
                        // "보통주" 명시 없으면 첫 번째 발행주식수 행 사용 (폴백)
                        issuedShares = value;
                    } else if (label.startsWith("EPS") && label.contains("원")) {
                        // EPS(원) 행 직접 읽기
                        tableEps = value;
                    } else if (label.startsWith("BPS")) {
                        metrics.put("bps", value);
                    } else if (label.startsWith("PER")) {
                        if (topPer == null) metrics.put("per", value);
                    } else if (label.startsWith("PBR")) {
                        if (topPbr == null) metrics.put("pbr", value);
                    } else if (label.contains("ROE")) {
                        metrics.put("roe", value);
                    } else if (label.contains("영업이익률")) {
                        metrics.put("operatingMargin", value);
                    } else if (label.contains("지배주주순이익률")) {
                        metrics.put("netMargin", value);
                    } else if (label.contains("부채비율")) {
                        metrics.put("debtRatio", value);
                    }
                }

                // FnGuide 테이블에 EPS(원) 행이 있으면 우선 사용 (연결 지배지분 기준)
                if (tableEps != null) {
                    metrics.put("eps", tableEps);
                    log.info("[StockDetail] {} EPS FnGuide 테이블 직접값: {}", stockCode, tableEps);
                }

                // ★ EPS TTM 계산 (테이블 EPS 없을 때만): highlight_D_Q (분기) 테이블에서 최근 4분기 지배주주순이익 합산
                if (tableEps != null) {
                    log.info("[StockDetail] {} 테이블 EPS 있음 → TTM 계산 스킵", stockCode);
                }
                BigDecimal ttmNetIncome = null;
                Element quarterTable = doc.selectFirst("div#highlight_D_Q table");
                if (quarterTable != null) {
                    // 분기 헤더에서 실적 확정 컬럼 인덱스들 찾기 (E 추정치 제외)
                    Elements qHeaderCells = quarterTable.select("thead tr.td_gapcolor2 th");
                    List<Integer> actualQCols = new ArrayList<>();
                    for (int i = 0; i < qHeaderCells.size(); i++) {
                        String hText = qHeaderCells.get(i).text().trim();
                        if (!hText.contains("(E)") && hText.matches("\\d{4}/\\d{2}")) {
                            actualQCols.add(i);
                        }
                    }
                    // 최근 4분기 (뒤에서 4개)
                    int startIdx = Math.max(0, actualQCols.size() - 4);
                    List<Integer> last4Cols = actualQCols.subList(startIdx, actualQCols.size());

                    if (!last4Cols.isEmpty()) {
                        log.info("[StockDetail] {} TTM 분기 컬럼: {}", stockCode,
                                last4Cols.stream().map(ci -> ci < qHeaderCells.size() ? qHeaderCells.get(ci).text() : "?")
                                        .collect(java.util.stream.Collectors.joining(", ")));

                        Elements qRows = quarterTable.select("tbody tr");
                        for (Element qRow : qRows) {
                            String qLabel = qRow.selectFirst("th") != null ? qRow.selectFirst("th").text().trim() : "";
                            if (qLabel.contains("지배주주순이익") && !qLabel.contains("률") && !qLabel.contains("비지배")) {
                                Elements qTds = qRow.select("td");
                                BigDecimal sum = BigDecimal.ZERO;
                                int validCount = 0;
                                for (int colIdx : last4Cols) {
                                    if (colIdx < qTds.size()) {
                                        BigDecimal qVal = parseFnGuideNumber(qTds.get(colIdx).text().trim());
                                        if (qVal != null) {
                                            sum = sum.add(qVal);
                                            validCount++;
                                        }
                                    }
                                }
                                if (validCount >= 4) {
                                    ttmNetIncome = sum;
                                    log.info("[StockDetail] {} TTM 지배주주순이익: {}억원 ({}분기 합산)",
                                            stockCode, ttmNetIncome, validCount);
                                } else {
                                    log.warn("[StockDetail] {} TTM 분기 데이터 부족: {}개/4개", stockCode, validCount);
                                }
                                break;
                            }
                        }
                    }
                }

                // ★ EPS = TTM 지배주주순이익(억원) × 1억 / 발행주식수(천주 × 1,000)
                // 테이블 EPS가 없을 때만 TTM 계산 사용
                if (tableEps == null && ttmNetIncome != null && issuedShares != null
                        && issuedShares.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal calcEps = ttmNetIncome
                            .multiply(new BigDecimal("100000000"))  // 억원 → 원
                            .divide(issuedShares.multiply(new BigDecimal("1000")), 0, RoundingMode.HALF_UP);  // 천주 → 주
                    metrics.put("eps", calcEps);
                    log.info("[StockDetail] {} EPS TTM 폴백 계산: {} (TTM순이익: {}억 ÷ 발행주식수: {}천주)",
                            stockCode, calcEps, ttmNetIncome, issuedShares);
                }
            }

            // 상단 PER/PBR이 있으면 우선 사용 (trailing 기준)
            if (topPer != null && topPer.compareTo(new BigDecimal("1000")) < 0) {
                metrics.put("per", topPer);
            }
            if (topPbr != null && topPbr.compareTo(new BigDecimal("100")) < 0) {
                metrics.put("pbr", topPbr);
            }

            // ★ PER/PBR 다단계 검증
            BigDecimal fnEps = metrics.get("eps");
            BigDecimal fnBps = metrics.get("bps");
            BigDecimal fnPer = metrics.get("per");
            BigDecimal fnPbr = metrics.get("pbr");

            // 검증 1: 절대 상한 — 1000/100 이상이면 날짜값 오파싱
            boolean perInvalid = (fnPer != null && fnPer.compareTo(new BigDecimal("1000")) >= 0);
            boolean pbrInvalid = (fnPbr != null && fnPbr.compareTo(new BigDecimal("100")) >= 0);

            // 검증 2: 음수 PER인데 EPS가 양수 → 오파싱 (적자기업의 음수PER은 EPS도 음수여야 함)
            if (fnPer != null && fnPer.compareTo(BigDecimal.ZERO) < 0
                    && fnEps != null && fnEps.compareTo(BigDecimal.ZERO) > 0) {
                log.warn("[StockDetail] {} PER 음수({})인데 EPS 양수({}) → 오파싱 판정",
                        stockCode, fnPer, fnEps);
                perInvalid = true;
            }

            // 검증 3: PBR 음수인데 BPS 양수 → 오파싱
            if (fnPbr != null && fnPbr.compareTo(BigDecimal.ZERO) < 0
                    && fnBps != null && fnBps.compareTo(BigDecimal.ZERO) > 0) {
                log.warn("[StockDetail] {} PBR 음수({})인데 BPS 양수({}) → 오파싱 판정",
                        stockCode, fnPbr, fnBps);
                pbrInvalid = true;
            }

            // 검증 4: EPS·BPS 기반 역산 교차검증 (PER×EPS ≈ PBR×BPS 일관성 체크)
            if (fnPer != null && fnEps != null && fnPbr != null && fnBps != null
                    && fnEps.compareTo(BigDecimal.ZERO) != 0 && fnBps.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal impliedPriceByPer = fnPer.multiply(fnEps);
                BigDecimal impliedPriceByPbr = fnPbr.multiply(fnBps);
                if (impliedPriceByPer.compareTo(BigDecimal.ZERO) > 0 && impliedPriceByPbr.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal priceRatio = impliedPriceByPer.divide(impliedPriceByPbr, 2, RoundingMode.HALF_UP);
                    if (priceRatio.compareTo(new BigDecimal("3")) > 0 || priceRatio.compareTo(new BigDecimal("0.33")) < 0) {
                        log.warn("[StockDetail] {} PER×EPS({}) vs PBR×BPS({}) 3배 이상 괴리 → 직접 계산",
                                stockCode, impliedPriceByPer, impliedPriceByPbr);
                        perInvalid = true;
                        pbrInvalid = true;
                    }
                }
            }

            if (perInvalid || pbrInvalid) {
                log.warn("[StockDetail] {} FnGuide PER/PBR 이상값 감지 (PER={}, PBR={}, perInvalid={}, pbrInvalid={}) → 직접 계산",
                        stockCode, fnPer, fnPbr, perInvalid, pbrInvalid);
                if (perInvalid) metrics.remove("per");
                if (pbrInvalid) metrics.remove("pbr");
            }

            log.info("[StockDetail] {} FnGuide RAW: PER={}, PBR={}, EPS={}, BPS={}, ROE={}, 영업이익률={}, 부채비율={}",
                    stockCode,
                    metrics.get("per"), metrics.get("pbr"),
                    metrics.get("eps"), metrics.get("bps"),
                    metrics.get("roe"), metrics.get("operatingMargin"),
                    metrics.get("debtRatio"));

            return metrics.isEmpty() ? null : metrics;

        } catch (Exception e) {
            log.warn("[StockDetail] {} FnGuide 크롤링 실패: {}", stockCode, e.getMessage());
        }
        return null;
    }

    /**
     * FnGuide 상단 박스에서 PER/PBR 값 추출
     * HTML 구조: <a id="h_per">PER</a> 뒤의 <dd>59.91</dd>
     */
    private BigDecimal extractFnGuideTopValue(Document doc, String anchorId) {
        try {
            Element anchor = doc.selectFirst("a#" + anchorId);
            if (anchor != null) {
                // anchor의 부모(li 또는 div)에서 dd 값 추출
                Element parent = anchor.parent();
                while (parent != null && !parent.tagName().equals("div") && !parent.tagName().equals("li")) {
                    parent = parent.parent();
                }
                if (parent != null) {
                    Element dd = parent.selectFirst("dd");
                    if (dd != null) {
                        return parseFnGuideNumber(dd.text().trim());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[StockDetail] FnGuide 상단 {} 추출 실패: {}", anchorId, e.getMessage());
        }
        return null;
    }

    private BigDecimal parseFnGuideNumber(String text) {
        if (text == null || text.isBlank() || text.equals("&nbsp;") || text.equals("N/A")) return null;
        try {
            String cleaned = text.replaceAll("[^0-9.\\-]", "");
            if (cleaned.isEmpty()) return null;
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ========== 목표주가 컨센서스 ==========

    /**
     * 네이버 금융 메인 페이지 1회 크롤링 (배당수익률 + 목표주가 공용)
     */
    private Document fetchNaverMainPage(String stockCode) {
        try {
            return Jsoup.connect("https://finance.naver.com/item/main.naver?code=" + stockCode)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .referrer("https://finance.naver.com/")
                    .timeout(10000)
                    .get();
        } catch (Exception e) {
            log.warn("[StockDetail] 네이버 메인 페이지 크롤링 실패 [{}]: {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * 네이버 금융에서 목표주가 컨센서스 추출
     * HTML 구조: <th>투자의견|목표주가</th><td><span><em>4.00</em>매수</span> | <em>654,231</em></td>
     * → td 안의 em 중 값이 1,000 이상인 것이 목표주가
     */
    private void enrichWithConsensusTargetFromDoc(AiAnalysis aiAnalysis, Document doc, String stockCode, PriceInfo priceInfo) {
        if (aiAnalysis == null || doc == null) return;
        try {
            // 전략 1: th:contains(목표주가) → 형제 td → em 중 큰 값
            Element opinionTh = doc.selectFirst("th:contains(목표주가)");
            if (opinionTh != null) {
                Element opinionTd = opinionTh.nextElementSibling();
                if (opinionTd != null) {
                    Elements ems = opinionTd.select("em");
                    log.debug("[목표주가 파싱] {} - td 내 em {} 개: {}",
                            stockCode, ems.size(), ems.eachText());

                    // em 값 중 1000 이상인 가장 큰 값 = 목표주가 (4.00 같은 점수 제외)
                    BigDecimal targetPrice = null;
                    for (Element em : ems) {
                        BigDecimal val = parseNaverNumber(em.text());
                        if (val != null && val.compareTo(new BigDecimal("1000")) > 0) {
                            if (targetPrice == null || val.compareTo(targetPrice) > 0) {
                                targetPrice = val;
                            }
                        }
                    }

                    if (targetPrice != null && targetPrice.compareTo(new BigDecimal("100000000")) < 0) {
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
                        log.info("[StockDetail] 목표주가 컨센서스: {} → {}원 (상승여력: {}%)",
                                stockCode, targetPrice, aiAnalysis.getTargetUpside());
                    } else {
                        log.warn("[목표주가 파싱] {} - 유효한 목표주가 없음 (em 값: {})",
                                stockCode, ems.eachText());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[StockDetail] 목표주가 파싱 실패 [{}]: {}", stockCode, e.getMessage());
        }
    }

    /**
     * 네이버 금융에서 배당수익률 추출
     * HTML 구조: <em id="_dvr">1.97</em>% (또는 th:contains(배당수익률) → td > em)
     * 정상 범위: 0 < 배당수익률 < 30%
     */
    private void enrichWithDividendYieldFromDoc(FinancialInfo financial, Document doc, String stockCode) {
        if (financial == null || financial.getDividendYield() != null || doc == null) return;
        try {
            BigDecimal divYield = null;

            // 전략 1: ID 기반 — em[id=_dvr] (Jsoup에서 _로 시작하는 ID는 속성 셀렉터가 안전)
            Element dvrEl = doc.selectFirst("em[id=_dvr]");
            if (dvrEl != null) {
                divYield = parseNaverNumber(dvrEl.text());
                log.debug("[배당수익률 파싱] {} - em[id=_dvr] 원본값: '{}' → parsed: {}",
                        stockCode, dvrEl.text(), divYield);
            }

            // 전략 2: 폴백 — th:contains(배당수익률) → td > em
            if (divYield == null) {
                Element divTh = doc.selectFirst("th:contains(배당수익률)");
                if (divTh != null) {
                    Element divTd = divTh.nextElementSibling();
                    if (divTd != null) {
                        Element em = divTd.selectFirst("em");
                        if (em != null) {
                            divYield = parseNaverNumber(em.text());
                            log.debug("[배당수익률 파싱] {} - th 폴백 원본값: '{}' → parsed: {}",
                                    stockCode, em.text(), divYield);
                        }
                    }
                }
            }

            // 범위 검증: 0 < 배당수익률 < 30% (세계 최고 배당주도 20% 안넘음)
            if (divYield != null && divYield.compareTo(BigDecimal.ZERO) > 0
                    && divYield.compareTo(new BigDecimal("30")) < 0) {
                financial.setDividendYield(divYield);
                log.info("[StockDetail] 배당수익률: {} → {}%", stockCode, divYield);
            } else if (divYield != null) {
                log.warn("[배당수익률 파싱] {} - 범위 초과로 무시: {}% (정상범위: 0~30%)",
                        stockCode, divYield);
            }
        } catch (Exception e) {
            log.warn("[StockDetail] 배당수익률 파싱 실패 [{}]: {}", stockCode, e.getMessage());
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
                }, stockDetailExecutor));
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

    // (2026-07-10) enrichNewsWithPositiveItems / generatePositiveNews 삭제 —
    //   실뉴스 부족 시 하드코딩 가짜 호재("자사주 1조원 소각" 등)를 AI 프롬프트/리스크 뉴스에 주입해
    //   판단을 오염시키던 §4c 위반. 실뉴스만 사용하고, 없으면 정직하게 "관련 뉴스 없음"으로 둔다.

    // ========== Gemini AI 분석 ==========

    /**
     * Gemini 기반 AI 분석 (실제 데이터 기반)
     * 실패 시 null 반환 → 규칙기반 폴백
     */
    private AiAnalysis generateGeminiAnalysis(StockDetailDto dto) {
        try {
            String prompt = buildGeminiPrompt(dto);
            if (prompt == null) return null;

            // Redis L2 캐시 확인 — 매 종목 조회마다 Gemini 호출하지 않게.
            // 10분 TTL. 같은 종목을 짧은 간격으로 여러 명이 봐도 LLM 쿼터 1회만 소모.
            // §16-11: 캐시 키에 세션 phase 포함 — 장전(0/미거래) 분석이 개장 후까지 유지되지 않게(다른 키=miss=재분석).
            String phase = isBeforeMarketHours() ? "PRE" : (isAfterMarketHours() ? "POST" : "OPEN");
            String cacheKey = dto.getStockCode() + ":" + phase;
            AiAnalysis cached = redisCacheService.get(GEMINI_CHART_CACHE, cacheKey, AiAnalysis.class);
            if (cached != null) {
                log.debug("[StockDetail] Gemini 분석 Redis HIT - {}", cacheKey);
                // 시그널은 최신 시세 기준으로 재계산 (캐시하지 않음)
                cached.setChartSignals(chartSignalService.detect(dto));
                return cached;
            }

            // Gemini AI 분석 - 실패 시 null → 규칙기반 폴백
            String response = geminiService.analyzeStockDetail(prompt);
            if (response == null) return null;

            log.info("[StockDetail] Gemini AI 분석 응답: {}", response);

            AiAnalysis result = parseGeminiResponse(response, dto);
            if (result != null) {
                // 차트 시그널 주입 (코드 기반, LLM 과 별개)
                result.setChartSignals(chartSignalService.detect(dto));
                // 캐시 저장 — chartSignals 는 위에서 매번 재계산하므로 제외해도 되지만 호환성 위해 포함
                redisCacheService.put(GEMINI_CHART_CACHE, cacheKey, result, Duration.ofMinutes(10));
            }
            return result;
        } catch (Exception e) {
            log.warn("[StockDetail] Gemini AI 분석 실패: {} - 규칙기반 폴백", e.getMessage());
            return null;
        }
    }

    /**
     * Gemini 프롬프트 구성 (실제 데이터 요약)
     */
    String buildGeminiPrompt(StockDetailDto dto) {   // package-private: 프롬프트 정합 테스트용
        StringBuilder sb = new StringBuilder();
        // ★ 분석 기준일 명시 주입 — LLM 이 날짜를 발명(예: 2년 전)하지 않게 서버 오늘/세션 앵커 제공(§4c).
        boolean preMarket = isBeforeMarketHours();
        String session = preMarket ? "장전(당일 미거래)" : (isAfterMarketHours() ? "장마감" : "장중");
        sb.append(String.format("분석 기준일: %s (%s)\n", java.time.LocalDate.now(KST), session));
        sb.append(String.format("종목: %s (%s)\n", dto.getStockName(), dto.getStockCode()));

        // 가격 정보 — 장전(미거래)이면 거래량/고저시가는 0이므로 "미거래" 로 정직 표기(0 을 실측처럼 넘기지 않음, §4c).
        if (dto.getPrice() != null) {
            PriceInfo p = dto.getPrice();
            if (preMarket) {
                sb.append(String.format("현재가: %s원(전일종가 기준), 거래량/고저시가: 장전 미거래\n",
                        p.getCurrentPrice()));
            } else {
                sb.append(String.format("현재가: %s원, 등락률: %s%%, 거래량: %s\n",
                        p.getCurrentPrice(), p.getChangeRate(),
                        p.getTradingVolume() != null ? p.getTradingVolume() : "N/A"));
                if (p.getHigh() != null && p.getLow() != null) {
                    sb.append(String.format("고가: %s, 저가: %s, 시가: %s\n",
                            p.getHigh(), p.getLow(), p.getOpen()));
                }
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

        // 수급 정보 — 장전(초기화)이면 당일 값은 전부 0이므로 그 0을 주입하지 않는다(§4c).
        //   대신 최근 5거래일 누적 수급(실데이터)을 제공 → QuickSummaryBar 와 같은 소스, AI 오독 방지.
        if (dto.getSupplyDemand() != null) {
            SupplyDemand s = dto.getSupplyDemand();
            if ("장전(초기화)".equals(s.getDataSource())) {
                String fiveDay = build5DaySupplyLine(dto.getStockCode());
                sb.append(fiveDay != null ? fiveDay
                        : "수급: 당일 장전 미거래 · 최근 수급 데이터 미수집\n");
            } else {
                sb.append(String.format("외국인 순매수: %s억, 기관 순매수: %s억, 체결강도: %s%% (%s)\n",
                        s.getForeignNetBuy(), s.getInstNetBuy(), s.getVolumePower(),
                        s.getDataSource() != null ? s.getDataSource() : "실시간"));
            }
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

        // 차트 기술적 지표 — AI 가 해석할 수 있게 풍부하게 제공
        if (dto.getChartData() != null) {
            ChartData c = dto.getChartData();
            BigDecimal current = dto.getPrice().getCurrentPrice();
            sb.append(String.format("MA5: %s, MA20: %s, MA60: %s, VWAP: %s\n",
                    c.getMa5() != null ? c.getMa5() : "N/A",
                    c.getMa20() != null ? c.getMa20() : "N/A",
                    c.getMa60() != null ? c.getMa60() : "N/A",
                    c.getVwap() != null ? c.getVwap() : "N/A"));

            // 현재가 대비 MA 괴리율
            if (current != null) {
                if (c.getMa20() != null && c.getMa20().signum() > 0) {
                    BigDecimal diff = current.subtract(c.getMa20())
                            .divide(c.getMa20(), 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                    sb.append(String.format("MA20 괴리율: %+.2f%%\n", diff.doubleValue()));
                }
                if (c.getMa60() != null && c.getMa60().signum() > 0) {
                    BigDecimal diff = current.subtract(c.getMa60())
                            .divide(c.getMa60(), 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                    sb.append(String.format("MA60 괴리율: %+.2f%%\n", diff.doubleValue()));
                }
            }

            // 볼린저 밴드 현재 범위
            if (c.getBbUpper() != null && !c.getBbUpper().isEmpty()
                    && c.getBbLower() != null && !c.getBbLower().isEmpty()) {
                BigDecimal up = c.getBbUpper().get(c.getBbUpper().size() - 1);
                BigDecimal lo = c.getBbLower().get(c.getBbLower().size() - 1);
                sb.append(String.format("볼린저: 상단 %s, 하단 %s\n",
                        up != null ? up : "N/A", lo != null ? lo : "N/A"));
            }

            // 추세 채널(30봉 회귀) — 차트 오버레이/보드와 동일 산식(TrendChannelCalculator).
            // 미성립(봉<10·결측)이면 라인 생략(§4c) — AI 는 있는 데이터로만 해석.
            String channelLine = buildChannelLine(c.getCandles());
            if (channelLine != null) sb.append(channelLine);

            // 코드가 확정한 차트 시그널 — AI 가 이를 참고해서 해석 생성
            List<ChartSignal> signals = chartSignalService.detect(dto);
            if (!signals.isEmpty()) {
                sb.append("감지된 시그널: ");
                for (int i = 0; i < Math.min(6, signals.size()); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(signals.get(i).getLabel());
                }
                sb.append("\n");
            }
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
     * 추세 채널 프롬프트 줄(최근 30봉 회귀 채널) — 순수 함수(테스트 대상). 차트 오버레이/종합판단 보드와
     * 동일 산식({@link com.myplatform.backend.util.TrendChannelCalculator}). AI 는 이 위치를 차트 해석
     * 맥락(지지/저항 레벨)으로만 사용 — 산식/점수 미편입. 채널 미성립(봉<10·결측)이면 null = 라인 생략(§4c).
     *
     * @param candles 최신→과거 순 캔들(ChartData.candles 그대로)
     */
    static String buildChannelLine(List<StockDetailDto.CandlePoint> candles) {
        if (candles == null || candles.isEmpty()) return null;
        List<com.myplatform.backend.util.TrendChannelCalculator.Bar> bars = new ArrayList<>();
        int limit = Math.min(30, candles.size());
        for (int i = limit - 1; i >= 0; i--) {   // 최신→과거 입력을 과거→최신으로
            StockDetailDto.CandlePoint c = candles.get(i);
            if (c == null || c.getHigh() == null || c.getLow() == null || c.getClose() == null) continue;
            bars.add(new com.myplatform.backend.util.TrendChannelCalculator.Bar(
                    c.getHigh().doubleValue(), c.getLow().doubleValue(), c.getClose().doubleValue()));
        }
        var ch = com.myplatform.backend.util.TrendChannelCalculator.compute(bars);
        if (ch == null) return null;
        String dir = switch (ch.direction()) {
            case "UP" -> "상승 채널";
            case "DOWN" -> "하락 채널";
            default -> "박스권(횡보)";
        };
        return String.format("추세 채널(30일 회귀): %s (기울기 %+.2f%%/일), 채널 내 위치 %d%% (0=하단 지지/100=상단 저항)\n",
                dir, ch.slopePctPerBar(), Math.round(ch.position() * 100));
    }

    /**
     * 최근 5거래일 누적 수급 프롬프트 줄 — 장전 당일 0 대신 실수급 제공(§4c). StockAnalysisService(QuickSummaryBar
     * 와 동일 소스) 재사용. 데이터 없음/미가용/오류 시 null(호출부가 "미수집" 표기).
     */
    private String build5DaySupplyLine(String stockCode) {
        try {
            StockAnalysisService svc = stockAnalysisProvider.getIfAvailable();
            if (svc == null) return null;
            StockAnalysisService.FiveDaySupply f = svc.getFiveDayNetBuy(stockCode);
            if (f == null) return null;
            BigDecimal oneEok = BigDecimal.valueOf(100_000_000L);
            long foreignEok = f.foreignNetKrw().divide(oneEok, 0, RoundingMode.HALF_UP).longValue();
            long instEok = f.institutionNetKrw().divide(oneEok, 0, RoundingMode.HALF_UP).longValue();
            return String.format("최근 %d거래일 누적 수급 — 외국인 %+d억, 기관 %+d억 (당일은 장전 미거래)\n",
                    f.days(), foreignEok, instEok);
        } catch (Exception e) {
            log.debug("[StockDetail] 5일 수급 조립 실패 [{}]: {}", stockCode, e.getMessage());
            return null;
        }
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
     * 기술적 신호 라벨 — 순수 함수(테스트 대상). 차트 신호 + 점수 recommendation + 본문 종합판단으로 정합.
     * <p>① <b>"수급 강세" 미스노머 제거</b>(라벨은 수급 데이터와 무관 — 이전엔 매수 rec 을 "수급 강세"로 오표기).
     * ② <b>본문 종합판단이 매도/관망인데 점수는 매수</b>면 라벨을 본문 쪽으로 억제(라벨/본문 모순 방지).
     */
    static String resolveTechnicalSignal(String chartSignal, String recommendation, String bodyVerdict) {
        boolean chartBearish = "이평선 하향 이탈".equals(chartSignal) || "NEUTRAL".equals(chartSignal);
        boolean chartBullishSig = chartSignal != null && (chartSignal.contains("강세") || chartSignal.contains("돌파"));
        boolean recBullish = "BUY".equals(recommendation) || "TRADING_BUY".equals(recommendation) || "WAIT_AND_BUY".equals(recommendation);
        boolean recBearish = "SELL".equals(recommendation);

        // ② 본문 매도/관망 ↔ 점수 매수 모순 → 본문 우선(라벨 억제)
        if (recBullish && ("매도".equals(bodyVerdict) || "관망".equals(bodyVerdict))) {
            return "매도".equals(bodyVerdict) ? "관망 (본문 매도 의견)" : "관망";
        }
        if (chartBullishSig && recBearish) {
            return "기술적 반등이나 종합 약세";
        }
        if (chartBearish && recBullish) {
            switch (recommendation) {
                case "BUY": return "매수 우위";                       // ← 이전 "수급 강세 (적극 매수)" 대체(미스노머 제거)
                case "TRADING_BUY": return "단기 매수 구간";
                case "WAIT_AND_BUY": return "조정 대기 (눌림목 매수)";
            }
        }
        if ("NEUTRAL".equals(chartSignal) || "이평선 하향 이탈".equals(chartSignal)) {
            switch (recommendation) {
                case "BUY": return "매수 신호";
                case "TRADING_BUY": return "단기 매수";
                case "WAIT_AND_BUY": return "조정 대기";
                case "SELL": return "매도 신호";
                case "HOLD": return "중립";
            }
        }
        return chartSignal;   // 이평선 상회 등은 원 신호 유지
    }

    /**
     * 표시용 recommendation 을 본문 종합판단(bodyVerdict)과 정합 — 큰 판정 뱃지가 본문과 모순되지 않게(라벨-본문 소스 분리 방지, AUDIT 2026-07-10 #2).
     * <p>점수 기반 rec 은 그대로 두되(산식·점수 불변), <b>본문이 매도/관망인데 rec 이 매수 계열</b>이면 HOLD 로 억제한다
     * ({@link #resolveTechnicalSignal} ②의 칩 억제와 동일 철학을 큰 뱃지에 확장). bodyVerdict 가 없으면(근거 없음)
     * 원본 유지(§4c fail-open — 결측 근거로 판정을 바꾸지 않음). 순수(테스트 대상).
     */
    static String reconcileRecommendationWithBody(String recommendation, String bodyVerdict) {
        if (bodyVerdict == null) return recommendation;
        boolean recBullish = "BUY".equals(recommendation) || "TRADING_BUY".equals(recommendation)
                || "WAIT_AND_BUY".equals(recommendation);
        boolean recBearish = "SELL".equals(recommendation);
        if (recBullish && ("매도".equals(bodyVerdict) || "관망".equals(bodyVerdict))) return "HOLD";
        if (recBearish && "매수".equals(bodyVerdict)) return "HOLD";
        return recommendation;
    }

    /** 본문 텍스트에서 종합판단 verdict 추출 — 최초 등장하는 매수/관망/매도. 없으면 null. 순수(테스트 대상). */
    static String classifyVerdict(String text) {
        if (text == null) return null;
        int pBuy = text.indexOf("매수"), pHold = text.indexOf("관망"), pSell = text.indexOf("매도");
        int best = Integer.MAX_VALUE;
        String v = null;
        if (pBuy >= 0 && pBuy < best) { best = pBuy; v = "매수"; }
        if (pHold >= 0 && pHold < best) { best = pHold; v = "관망"; }
        if (pSell >= 0 && pSell < best) { best = pSell; v = "매도"; }
        return v;
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

        // 기술적 신호(차트 기반) → recommendation·본문 종합판단과 정합.
        //   ★ "수급 강세" 미스노머 제거(수급 데이터 무근거) + 본문 종합판단이 매도/관망인데 점수는 매수면 라벨 억제.
        String chartSignal = "NEUTRAL";
        if (dto.getChartData() != null && dto.getPrice() != null && dto.getChartData().getMa20() != null) {
            BigDecimal cp = dto.getPrice().getCurrentPrice();
            BigDecimal ma20 = dto.getChartData().getMa20();
            if (cp != null && cp.compareTo(ma20) > 0) {
                chartSignal = "이평선 상회";
            } else if (cp != null) {
                chartSignal = "이평선 하향 이탈";
            }
        }
        String bodyVerdict = classifyVerdict(extractSection(response, "종합 판단"));
        String technicalSignal = resolveTechnicalSignal(chartSignal, recommendation, bodyVerdict);
        // ★ 큰 판정 뱃지(recommendation)도 본문 종합판단과 정합 — 점수 매수 rec 이 본문 매도/관망과 모순이면 HOLD 억제(#2).
        //   점수(overallScore)·technicalSignal·근거는 원본 그대로 두고 표시 라벨만 정합(산식 무접촉).
        String displayRecommendation = reconcileRecommendationWithBody(recommendation, bodyVerdict);

        // ★ 동적 가격 가이드
        String priceGuide = generatePriceGuide(dto, recommendation, score);

        // ★ "차트 해석" 섹션 추출 — Gemini 응답에서 ■ 차트 해석 ~ ■ 다음 섹션 사이 텍스트
        String chartAnalysis = extractSection(response, "차트 해석");

        return AiAnalysis.builder()
                .overallScore(score)
                .recommendation(displayRecommendation)
                .strategy(response) // Gemini 전체 응답을 전략 텍스트로 사용
                .technicalSignal(technicalSignal)
                .buyReasons(buyReasons)
                .sellReasons(sellReasons)
                .conflictAnalysis(conflictAnalysis)
                .priceGuide(priceGuide)
                .chartAnalysis(chartAnalysis)
                .build();
    }

    /**
     * Gemini 응답에서 "■ XX" 섹션 본문 추출.
     * 다음 "■" 나 문서 끝까지.
     */
    private String extractSection(String response, String sectionTitle) {
        if (response == null || sectionTitle == null) return null;
        int start = response.indexOf("■ " + sectionTitle);
        if (start < 0) return null;
        int bodyStart = response.indexOf("\n", start);
        if (bodyStart < 0) return null;
        int nextSection = response.indexOf("\n■", bodyStart);
        String body = nextSection > 0
                ? response.substring(bodyStart, nextSection)
                : response.substring(bodyStart);
        String trimmed = body.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
