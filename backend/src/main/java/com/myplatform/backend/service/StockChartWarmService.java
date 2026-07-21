package com.myplatform.backend.service;

import com.myplatform.backend.entity.BotTradingPosition;
import com.myplatform.backend.entity.StockWatchlist;
import com.myplatform.backend.repository.BotTradingPositionRepository;
import com.myplatform.backend.repository.StockWatchlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 장 마감 후 인기종목 차트 프리워밍 — 200봉 차트(KIS 일봉 페이지네이션 ~4콜)는 첫 로딩이 비싸다.
 * 장 마감 후 일봉이 확정되면 사용자가 자주 여는 종목(관심종목 + 봇 보유)의 차트를 미리 Redis L2 에
 * 채워, 저녁 첫 조회를 즉시 응답시킨다({@link StockDetailCacheService#getCachedChartData} 장외 우선 읽기).
 *
 * <p><b>Redis 필수</b>({@code cache.redis.enabled=true}) — Caffeine(2분 TTL)만으론 장외 지속 불가라
 * MarketCacheWarmerService 와 동일하게 게이팅. 롤백 = {@code chart.warm.enabled=false}(온디맨드는 유지).
 * 선정은 DB 조회(cheap), 워밍만 KIS 비용 — 상한(기본 20종목)으로 호출 수 bound.
 */
@Service
@ConditionalOnProperty(name = "cache.redis.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class StockChartWarmService {

    private final StockDetailCacheService cacheService;
    private final StockWatchlistRepository watchlistRepository;
    private final BotTradingPositionRepository botPositionRepository;

    @Value("${chart.warm.enabled:true}")
    private boolean warmEnabled;
    @Value("${chart.warm.max-stocks:20}")
    private int maxStocks;

    /**
     * 장 마감 후 프리워밍 — 평일 20:10(NXT 애프터마켓 20:00 종료 직후, 일봉 확정).
     * 관심종목 ∪ 봇 보유 종목을 상한까지 워밍. best-effort, KIS 페이지네이션은 rate limiter 로 직렬화.
     */
    // 풀 명시 — 미지정이면 @Primary taskScheduler(트레이딩 전용 슬롯)로 떨어져 설계 의도 위반(SchedulingConfig 주석).
    // 차트 프리워밍은 캐시 워밍 성격이라 cacheScheduler.
    @Scheduled(scheduler = "cacheScheduler", cron = "0 10 20 * * MON-FRI", zone = "Asia/Seoul")
    public void warmPopularCharts() {
        if (!warmEnabled) return;
        List<String> targets = collectTargets();
        if (targets.isEmpty()) {
            log.info("[Chart Warm] 워밍 대상 없음(관심종목·봇 보유 비어있음)");
            return;
        }
        int ok = 0;
        for (String code : targets) {
            if (cacheService.refreshWarmChart(code)) ok++;
        }
        log.info("[Chart Warm] 인기종목 차트 프리워밍 완료 - {}/{}종목 (Redis L2)", ok, targets.size());
    }

    /** 관리 수동 트리거(QA) — 스케줄과 동일 로직. */
    public int warmNow() {
        List<String> targets = collectTargets();
        int ok = 0;
        for (String code : targets) {
            if (cacheService.refreshWarmChart(code)) ok++;
        }
        log.info("[Chart Warm] 수동 워밍 완료 - {}/{}종목", ok, targets.size());
        return ok;
    }

    /** 워밍 대상 수집 = 관심종목 ∪ 봇 보유(dedup·상한). DB 조회만(cheap). */
    private List<String> collectTargets() {
        List<String> watchCodes = new ArrayList<>();
        try {
            for (StockWatchlist w : watchlistRepository.findByIsActiveTrue()) {
                if (w.getStockCode() != null) watchCodes.add(w.getStockCode());
            }
        } catch (Exception e) {
            log.warn("[Chart Warm] 관심종목 조회 실패: {}", e.getMessage());
        }
        List<String> positionCodes = new ArrayList<>();
        try {
            for (BotTradingPosition p : botPositionRepository.findAll()) {
                if (p.getStockCode() != null) positionCodes.add(p.getStockCode());
            }
        } catch (Exception e) {
            log.warn("[Chart Warm] 봇 포지션 조회 실패: {}", e.getMessage());
        }
        return selectWarmTargets(watchCodes, positionCodes, maxStocks);
    }

    /**
     * 워밍 대상 선정 — 관심종목 우선 + 봇 보유 합집합, 6자리 코드만, dedup, 상한. 순수 함수(테스트 대상).
     * 관심종목을 앞에 둬 상한에 걸릴 때 사용자 관심을 우선 채운다.
     */
    static List<String> selectWarmTargets(List<String> watchCodes, List<String> positionCodes, int cap) {
        Set<String> ordered = new LinkedHashSet<>();
        for (String c : safe(watchCodes)) if (isValidCode(c)) ordered.add(c);
        for (String c : safe(positionCodes)) if (isValidCode(c)) ordered.add(c);
        int limit = cap <= 0 ? 20 : cap;
        List<String> out = new ArrayList<>();
        for (String c : ordered) {
            if (out.size() >= limit) break;
            out.add(c);
        }
        return out;
    }

    private static boolean isValidCode(String c) {
        return c != null && c.matches("\\d{6}");
    }

    private static List<String> safe(List<String> l) {
        return l == null ? List.of() : l;
    }
}
