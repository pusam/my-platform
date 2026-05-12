package com.myplatform.backend.service;

import com.myplatform.backend.dto.*;
import com.myplatform.backend.dto.TradingIndicatorDto.LeadingSectorResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 시장 데이터 Redis L2 캐시 워머
 * - cache.redis.enabled=true 일 때만 활성화
 * - 거래시간 동안만 스케줄 동작 (Asia/Seoul 기준 08:00~20:00)
 *   · 08:00~09:00: NXT 프리마켓
 *   · 09:00~15:30: KRX 정규장 + NXT
 *   · 15:30~18:00: KRX 시간외 + NXT 갭
 *   · 18:00~20:00: NXT 애프터마켓
 * - 기존 서비스 메서드 호출 → Caffeine L1도 자동 갱신 → Redis L2에도 저장
 */
@Service
@ConditionalOnProperty(name = "cache.redis.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class MarketCacheWarmerService {

    private final RedisCacheService redisCacheService;
    private final InvestorTradeService investorTradeService;
    private final InvestorSurgeService investorSurgeService;
    private final SectorTradingService sectorTradingService;
    private final SectorAnalysisService sectorAnalysisService;
    private final AiStockAnalysisService aiStockAnalysisService;
    private final SectorOpportunityService sectorOpportunityService;
    private final MarketIndicatorService marketIndicatorService;
    private final MarketTimingService marketTimingService;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // NXT/시간외 거래 시간까지 커버 (2025-03 NXT 도입, 2026-09 KRX 본 장 연장 예정)
    private static final LocalTime MARKET_OPEN = LocalTime.of(8, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(20, 0);

    // ===== Redis 캐시명 상수 =====
    private static final String CACHE_SMART_MONEY = "smartMoneyRealtime";
    private static final String CACHE_INVESTOR_TREND = "investorTrend";
    private static final String CACHE_CONSECUTIVE_BUY = "consecutiveBuys";
    private static final String CACHE_INVESTOR_SURGE = "investorSurge";
    private static final String CACHE_SECTOR_TRADING = "sectorTrading";
    private static final String CACHE_SECTOR_ANALYSIS = "sectorAnalysis";
    private static final String CACHE_AI_STRATEGY = "aiStrategy";

    // ===== TTL 상수 =====
    private static final Duration TTL_SMART_MONEY = Duration.ofMinutes(5);
    private static final Duration TTL_INVESTOR_TREND = Duration.ofMinutes(10);
    private static final Duration TTL_CONSECUTIVE_BUY = Duration.ofHours(2);
    private static final Duration TTL_INVESTOR_SURGE = Duration.ofMinutes(15);
    private static final Duration TTL_SECTOR_TRADING = Duration.ofMinutes(10);
    private static final Duration TTL_AI_STRATEGY = Duration.ofMinutes(15);

    /**
     * 서버 시작 시 전체 워밍업
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void warmupOnStartup() {
        log.info("[Cache Warmer] === 서버 시작 전체 워밍업 시작 ===");
        long start = System.currentTimeMillis();

        warmSectorTrading();
        warmAiStrategy();
        warmConsecutiveBuys();
        warmInvestorSurge();

        // 장중이면 실시간 데이터도 워밍업
        if (isMarketHours()) {
            warmSmartMoneyRealtime();
            warmKisApiData();
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[Cache Warmer] === 서버 시작 전체 워밍업 완료 - {}ms ===", elapsed);
    }

    /**
     * Smart Money 실시간 (외국인/기관) - 이전 호출 종료 후 30초.
     * fixedDelay 사용: KIS 400ms 직렬화로 호출이 늘어질 때 누적 락업 방지
     */
    @Scheduled(fixedDelay = 30000)
    public void warmSmartMoneyRealtime() {
        if (!isMarketHours()) return;

        try {
            for (String type : new String[]{"FOREIGN", "INSTITUTION"}) {
                // 워머가 KIS 를 직접 호출 — getTopTradesRealtime() 는 Redis 만 보므로 자기 자신을 부르면
                // DB 데이터(전일 마감)가 다시 Redis 에 채워져 장중에도 어제자만 보였음.
                List<InvestorTradeDto> data = investorTradeService.refreshSmartMoneyFromKis(type, 20);
                if (data != null && !data.isEmpty()) {
                    redisCacheService.put(CACHE_SMART_MONEY, type, data, TTL_SMART_MONEY);
                }
            }
            log.debug("[Cache Warmer] Smart Money 실시간 워밍 완료");
        } catch (Exception e) {
            log.error("[Cache Warmer] Smart Money 워밍 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * KIS 투자자 매매동향 (매수/매도 전체) - 이전 호출 종료 후 5분
     */
    @Scheduled(fixedDelay = 300000)
    public void warmKisApiData() {
        if (!isMarketHours()) return;

        try {
            // 매수 상위
            Map<String, List<InvestorTradeDto>> buyTrades = investorTradeService.getAllInvestorTopTrades("BUY", 50);
            if (buyTrades != null && !buyTrades.isEmpty()) {
                redisCacheService.put(CACHE_INVESTOR_TREND, "allTopTrades_BUY", buyTrades, TTL_INVESTOR_TREND);
            }

            // 매도 상위
            Map<String, List<InvestorTradeDto>> sellTrades = investorTradeService.getAllInvestorTopTrades("SELL", 50);
            if (sellTrades != null && !sellTrades.isEmpty()) {
                redisCacheService.put(CACHE_INVESTOR_TREND, "allTopTrades_SELL", sellTrades, TTL_INVESTOR_TREND);
            }

            log.debug("[Cache Warmer] 투자자 매매동향 워밍 완료");
        } catch (Exception e) {
            log.error("[Cache Warmer] 투자자 매매동향 워밍 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 섹터 거래대금 (TODAY/MIN5/MIN30) - 이전 호출 종료 후 60초
     */
    @Scheduled(fixedDelay = 60000)
    public void warmSectorTrading() {
        if (!isMarketHours() && !isStartup()) return;

        try {
            for (TradingPeriod period : new TradingPeriod[]{TradingPeriod.TODAY, TradingPeriod.MIN_5, TradingPeriod.MIN_30}) {
                List<SectorTradingDto> data = sectorTradingService.getAllSectorTrading(period);
                if (data != null && !data.isEmpty()) {
                    redisCacheService.put(CACHE_SECTOR_TRADING, period.name(), data, TTL_SECTOR_TRADING);
                }
            }

            // 주도 섹터 분석
            LeadingSectorResult sectorResult = sectorAnalysisService.getLeadingSectorRanking();
            if (sectorResult != null) {
                redisCacheService.put(CACHE_SECTOR_ANALYSIS, "leading", sectorResult, TTL_SECTOR_TRADING);
            }

            log.debug("[Cache Warmer] 섹터 거래대금 워밍 완료");
        } catch (Exception e) {
            log.error("[Cache Warmer] 섹터 거래대금 워밍 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * AI 전략 스냅샷 - 이전 호출 종료 후 2분
     */
    @Scheduled(fixedDelay = 120000)
    public void warmAiStrategy() {
        if (!isMarketHours() && !isStartup()) return;
        try {
            AiAnalysisResponseDto analysis = aiStockAnalysisService.getAnalysis();
            if (analysis != null) {
                redisCacheService.put(CACHE_AI_STRATEGY, "snapshot", analysis, TTL_AI_STRATEGY);
            }
            log.debug("[Cache Warmer] AI 전략 스냅샷 워밍 완료");
        } catch (Exception e) {
            log.error("[Cache Warmer] AI 전략 스냅샷 워밍 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 연속 매수 분석 - 08:50, 16:05
     */
    @Scheduled(cron = "0 50 8 * * MON-FRI", zone = "Asia/Seoul")
    @Scheduled(cron = "0 5 16 * * MON-FRI", zone = "Asia/Seoul")
    public void warmConsecutiveBuys() {
        try {
            Map<String, List<ConsecutiveBuyDto>> data = investorTradeService.getAllConsecutiveBuyStocks(3);
            if (data != null && !data.isEmpty()) {
                redisCacheService.put(CACHE_CONSECUTIVE_BUY, "all_3", data, TTL_CONSECUTIVE_BUY);
            }
            log.info("[Cache Warmer] 연속 매수 분석 워밍 완료 - {}개 유형", data != null ? data.size() : 0);
        } catch (Exception e) {
            log.error("[Cache Warmer] 연속 매수 분석 워밍 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 섹터 기회 발굴 (주도 섹터 × 유망 종목) - 이전 호출 종료 후 2분.
     * 섹터 랭킹/스마트머니/시세 워머가 먼저 돌고 그 결과를 조합함.
     */
    @Scheduled(fixedDelay = 120000)
    public void warmSectorOpportunity() {
        if (!isMarketHours() && !isStartup()) return;
        try {
            // 기본 파라미터(top4 × pick3) 조합을 강제 재계산 → Redis 갱신
            redisCacheService.evict(SectorOpportunityService.CACHE_SECTOR_OPPORTUNITY, "top4_pick3");
            sectorOpportunityService.getSectorOpportunities(4, 3);
            log.debug("[Cache Warmer] 섹터 기회 발굴 워밍 완료");
        } catch (Exception e) {
            log.error("[Cache Warmer] 섹터 기회 발굴 워밍 실패: {}", e.getMessage(), e);
        }
    }

    // [제거] warmPriceMovers - 장중 실시간 급등/급락 위젯(SectionLiveMovers) 제거에 따라 워머 중단.
    //        marketIndicatorService.refreshPriceMoversFromKis() / /api/market/price-{rise,fall} 엔드포인트는
    //        다른 페이지에서도 사용 가능하므로 유지하고, 60초 폴링 워머만 끔.

    /**
     * 시장 상태(KOSPI/KOSDAQ/ADR) - 이전 호출 종료 후 60초.
     * 프론트 60초 폴링이 매번 네이버 크롤링 + ADR 계산을 트리거하던 부담 제거.
     * 장외에도 동작 — 사용자가 장 마감 후 들어와도 최신 데이터 보이도록.
     */
    @Scheduled(fixedDelay = 60000)
    public void warmMarketStatus() {
        try {
            marketTimingService.refreshMarketTimingToCache();
            log.debug("[Cache Warmer] 시장 상태 워밍 완료");
        } catch (Exception e) {
            log.warn("[Cache Warmer] 시장 상태 워밍 실패: {}", e.getMessage());
        }
    }

    /**
     * 수급 급증 종목 - 이전 호출 종료 후 10분.
     * <p>
     * 주의: getAllSurgeStocks 는 Redis 캐시 우선 조회라 여기서 호출하면 stale 무한 루프.
     * refreshAllSurgeStocksCache 가 캐시 우회하고 DB 에서 fresh compute → Redis put 까지 한 번에.
     */
    @Scheduled(fixedDelay = 600000)
    public void warmInvestorSurge() {
        if (!isMarketHours() && !isStartup()) return;

        try {
            investorSurgeService.refreshAllSurgeStocksCache();

            // common_30 은 getCommonSurgeStocks 가 getSurgeStocks(DB 직접) 두 번 호출이라 stale 루프 없음
            List<InvestorSurgeDto> commonSurge = investorSurgeService.getCommonSurgeStocks(new BigDecimal("30"));
            if (commonSurge != null && !commonSurge.isEmpty()) {
                redisCacheService.put(CACHE_INVESTOR_SURGE, "common_30", commonSurge, TTL_INVESTOR_SURGE);
            }

            log.debug("[Cache Warmer] 수급 급증 종목 워밍 완료");
        } catch (Exception e) {
            log.warn("[Cache Warmer] 수급 급증 종목 워밍 실패: {}", e.getMessage());
        }
    }

    // ===== 헬퍼 메서드 =====

    private boolean isMarketHours() {
        LocalTime now = LocalTime.now(KST);
        return !now.isBefore(MARKET_OPEN) && !now.isAfter(MARKET_CLOSE);
    }

    /**
     * 서버 시작 직후인지 판단 (시작 후 2분 이내)
     * - 장외에도 스타트업 워밍은 동작하게 하기 위함
     */
    private boolean isStartup() {
        return System.currentTimeMillis() - startupTime < 120_000;
    }

    private final long startupTime = System.currentTimeMillis();

    // ===== 외부에서 캐시 키 조회용 (InvestorTradeService 등에서 사용) =====

    public static String getCacheSmartMoney() { return CACHE_SMART_MONEY; }
    public static String getCacheInvestorTrend() { return CACHE_INVESTOR_TREND; }
    public static String getCacheConsecutiveBuy() { return CACHE_CONSECUTIVE_BUY; }
    public static String getCacheInvestorSurge() { return CACHE_INVESTOR_SURGE; }
    public static String getCacheSectorTrading() { return CACHE_SECTOR_TRADING; }
    public static String getCacheSectorAnalysis() { return CACHE_SECTOR_ANALYSIS; }
    public static String getCacheAiStrategy() { return CACHE_AI_STRATEGY; }
}
