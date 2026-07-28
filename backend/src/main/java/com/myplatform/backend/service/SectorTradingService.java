package com.myplatform.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.config.SectorStockConfig.SectorInfo;
import com.myplatform.backend.dto.SectorRotationDto;
import com.myplatform.backend.dto.SectorTradingDto;
import com.myplatform.backend.dto.SectorTradingDto.StockTradingInfo;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.core.util.DateTimeUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 거래대금 조회 기간
 */
enum TradingPeriod {
    TODAY(0, "오늘누적"),
    MIN_5(5, "5분파워"),
    MIN_30(30, "30분파워");

    private final int minutes;
    private final String displayName;

    TradingPeriod(int minutes, String displayName) {
        this.minutes = minutes;
        this.displayName = displayName;
    }

    public int getMinutes() { return minutes; }
    public String getDisplayName() { return displayName; }
}

/**
 * 섹터별 거래대금 조회 서비스
 *
 * [스냅샷 기반 아키텍처]
 * - 1분마다 모든 종목의 누적 거래대금을 스냅샷으로 저장
 * - 5분/30분 파워 = 현재 스냅샷 - N분 전 스냅샷
 * - 분봉 API 호출 없이 메모리에서 즉시 계산 (0.1초 이내)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SectorTradingService {

    private final SectorStockConfig sectorConfig;
    private final StockPriceService stockPriceService;
    private final StockStatusService stockStatusService;
    private final ThreadPoolTaskExecutor sectorTradingExecutor;
    private final RedisCacheService redisCacheService;
    private final MarketCalendarService marketCalendar;

    // ========== 스냅샷 저장소 ==========
    // Key: 종목코드, Value: 시간순 정렬된 누적 거래대금 (TreeMap)
    private final ConcurrentMap<String, TreeMap<LocalDateTime, BigDecimal>> tradingHistoryStore = new ConcurrentHashMap<>();

    // 최근 시세 캐시 (TODAY용)
    private volatile Map<String, StockPriceDto> latestPriceCache = new ConcurrentHashMap<>();
    private volatile LocalDateTime lastSnapshotTime = null;

    // 섹터별 계산 결과 캐시
    private final ConcurrentMap<TradingPeriod, List<SectorTradingDto>> cachedResultByPeriod = new ConcurrentHashMap<>();
    private final ConcurrentMap<TradingPeriod, LocalDateTime> lastCalculateTime = new ConcurrentHashMap<>();

    // 전일 섹터별 거래대금 캐시 (섹터코드 → 거래대금)
    private volatile Map<String, BigDecimal> yesterdayTradingValueCache = new ConcurrentHashMap<>();
    private volatile LocalDate yesterdayCacheDate = null;

    // 스냅샷 수집 동시 실행 방지 — KIS batch 가 100~140초 걸리는데 cron 이 3분이라
    // 새 사이클이 직전 사이클과 겹쳐 SectorTrading-1/2/3 가 동시 실행 → connection leak·KIS 큐 경합.
    // 진행 중이면 다음 사이클 스킵.
    private final java.util.concurrent.atomic.AtomicBoolean snapshotInProgress
            = new java.util.concurrent.atomic.AtomicBoolean(false);

    // 설정 — 정규장 시간 상수는 MarketCalendarService 가 단일 소스
    private static final LocalTime MARKET_OPEN = MarketCalendarService.MARKET_OPEN;
    private static final LocalTime MARKET_CLOSE = MarketCalendarService.MARKET_CLOSE;
    private static final int HISTORY_RETENTION_MINUTES = 40;  // 최근 40분 데이터만 유지

    // 휴장일 체크는 marketCalendar 위임 (다른 스케줄러도 같은 기준 사용)
    private boolean isMarketClosed() {
        return marketCalendar.isMarketClosed();
    }

    // ========== 초기화 ==========

    @PostConstruct
    public void initializeCache() {
        log.info("[섹터거래대금] ========== 스냅샷 기반 서비스 시작 ==========");

        // 컨테이너 시작 시 어제 워머가 남긴 stale L2 캐시 제거.
        // deploy 가 8AM 이후에 일어나면 그날 resetDailyCache 가 영구 미스되는 케이스 방어 —
        // 시작 시점에 항상 sectorTrading L2 비워두고 신선 데이터로 다시 채우게 함.
        try {
            for (TradingPeriod p : TradingPeriod.values()) {
                redisCacheService.evict(MarketCacheWarmerService.getCacheSectorTrading(), p.name());
            }
            log.info("[섹터거래대금] 시작 시 L2 캐시 evict 완료");
        } catch (Exception e) {
            // 메모리 캐시는 자동 비어있는 상태로 시작하지만 L2 evict 실패 시 stale 응답 가능 →
            // warn 이 아니라 error 로 승격해 운영 모니터링이 잡을 수 있게.
            log.error("[섹터거래대금] 시작 시 L2 evict 실패 — stale 응답 우려: {}", e.getMessage(), e);
        }

        // 장외 시간이면 초기 수집 스킵 (스케줄러가 장중에 자동 수집)
        if (isMarketClosed()) {
            log.info("[섹터거래대금] 휴장일 - 초기 스냅샷 수집 스킵");
            return;
        }
        LocalTime now = LocalTime.now();
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) {
            log.info("[섹터거래대금] 장외 시간 - 초기 스냅샷 수집 스킵 (스케줄러가 장중 자동 수집)");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                // 15초 대기 (서버 시작 직후 리소스 경합 방지)
                Thread.sleep(15000);
                log.info("[섹터거래대금] 초기 스냅샷 수집 시작...");
                collectSnapshot();
                log.info("[섹터거래대금] 초기 스냅샷 수집 완료 - {} 종목", tradingHistoryStore.size());
            } catch (Exception e) {
                log.error("[섹터거래대금] 초기화 실패: {}", e.getMessage(), e);
            }
        }, sectorTradingExecutor);
    }

    // ========== 스케줄러: 1분마다 스냅샷 수집 ==========

    /**
     * 매일 08:00 일별 캐시 초기화
     * - 프론트 currentPhaseKey 가 08:00부터 'during' 으로 전환돼 거래대금 탭이 노출되는데,
     *   스냅샷 스케줄러는 09:00부터 도는 탓에 08~09시 사이엔 어제 마지막(15:39경) 데이터가
     *   그대로 잔존하던 버그. 8시에 비워두면 9시 첫 스냅샷까지 빈 상태로 노출됨.
     * - 휴장일은 어제 데이터 유지 (collectSnapshot 의 isMarketClosed 분기와 동일 의도).
     */
    @Scheduled(scheduler = "cacheScheduler", cron = "0 0 8 * * MON-FRI", zone = "Asia/Seoul")
    public void resetDailyCache() {
        if (isMarketClosed()) return;
        log.info("[섹터거래대금] 08:00 일별 캐시 초기화 — 9시 첫 스냅샷 대기");
        tradingHistoryStore.clear();
        latestPriceCache = new ConcurrentHashMap<>();
        cachedResultByPeriod.clear();
        lastCalculateTime.clear();
        lastSnapshotTime = null;
        // L2 evict 는 키별 try/catch — 한 키 실패해도 나머지 진행. 실패는 error 로 승격.
        for (TradingPeriod p : TradingPeriod.values()) {
            try {
                redisCacheService.evict(MarketCacheWarmerService.getCacheSectorTrading(), p.name());
            } catch (Exception e) {
                log.error("[섹터거래대금] 08:00 L2 evict 실패 ({}): {} — 메모리만 reset 됐고 L2 stale 가능", p.name(), e.getMessage(), e);
            }
        }
    }

    /**
     * 매분 스냅샷 수집 (09:00~15:40, 평일)
     * - stockPriceService.getStockPrices()로 전체 종목 시세 조회
     * - accumulatedTradingValue를 시간별로 저장
     */
    @Scheduled(scheduler = "cacheScheduler", cron = "0 */3 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledSnapshotCollection() {
        // 휴장일(공휴일) — 정적 데이터에 3분마다 KIS 배치(사이클당 100~140초) 낭비 방지.
        // 휴장일 화면 표시는 on-demand 수집(calculateSectorTrading 캐시 미스 경로)이 담당.
        if (isMarketClosed()) {
            return;
        }
        LocalTime now = LocalTime.now();
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) {
            return;
        }

        // 진행 중 스킵은 collectSnapshot 내부 CAS 가드가 담당 — 온디맨드 경로(캐시 미스/forceRefresh/
        // initializeCache)도 같은 가드를 타야 KIS 배치(사이클당 100~140초) 중복 기동을 막는다.
        CompletableFuture.runAsync(() -> {
            try {
                collectSnapshot();
            } catch (Exception e) {
                log.error("[섹터거래대금] 스냅샷 수집 실패: {}", e.getMessage(), e);
            }
        }, sectorTradingExecutor);
    }

    /**
     * 스냅샷 수집 실행 — 전 호출 경로(크론·캐시 미스·forceRefresh·initializeCache) 공통의
     * 중복 실행 가드(CAS). 이전엔 크론만 가드를 세워 온디맨드 경로가 동시 기동 가능했다.
     */
    private void collectSnapshot() {
        if (!snapshotInProgress.compareAndSet(false, true)) {
            log.warn("[섹터거래대금] 수집이 이미 진행 중 — 중복 실행 스킵 (KIS 호출 부하·connection leak 방지)");
            return;
        }
        try {
            collectSnapshotInternal();
        } finally {
            snapshotInProgress.set(false);
        }
    }

    private void collectSnapshotInternal() {
        long startTime = System.currentTimeMillis();
        LocalDateTime snapshotTime = DateTimeUtil.kstNow();

        if (isMarketClosed()) {
            // 휴장일 on-demand 수집: KIS 가 주는 마지막 거래일 실측(누적거래대금)만 저장.
            // 가짜 거래대금(시총×0.1% 등) 생성은 하지 않는다 — 점검 수정 2026-06-11.
            log.info("[섹터거래대금] 휴장일 - 마지막 거래일 실측 데이터만 저장 (임시값 생성 안 함)");
        }

        // 1. 모든 종목 코드 수집 (거래정지/상폐 종목 제외)
        Set<String> allStockCodes = new HashSet<>();
        for (SectorInfo sector : sectorConfig.getAllSectors()) {
            allStockCodes.addAll(stockStatusService.filterActiveStocks(sector.getStockCodes()));
        }

        // 2. Batch로 시세 조회 (가볍고 빠름)
        Map<String, StockPriceDto> priceMap = stockPriceService.getStockPrices(new ArrayList<>(allStockCodes));

        if (priceMap.isEmpty()) {
            log.warn("[섹터거래대금] 시세 데이터 없음 - 스냅샷 스킵");
            return;
        }

        // 3. 스냅샷 저장
        int savedCount = 0;
        for (Map.Entry<String, StockPriceDto> entry : priceMap.entrySet()) {
            String stockCode = entry.getKey();
            StockPriceDto price = entry.getValue();

            BigDecimal accumulatedValue = resolveAccumulatedValue(price);

            if (accumulatedValue != null) {
                // TreeMap에 스냅샷 추가
                tradingHistoryStore
                        .computeIfAbsent(stockCode, k -> new TreeMap<>())
                        .put(snapshotTime, accumulatedValue);
                savedCount++;
            }
        }

        // 4. 오래된 데이터 정리 (40분 이전 삭제)
        LocalDateTime cutoffTime = snapshotTime.minusMinutes(HISTORY_RETENTION_MINUTES);
        int cleanedCount = 0;
        for (TreeMap<LocalDateTime, BigDecimal> history : tradingHistoryStore.values()) {
            while (!history.isEmpty() && history.firstKey().isBefore(cutoffTime)) {
                history.pollFirstEntry();
                cleanedCount++;
            }
        }

        // 5. 최신 시세 캐시 업데이트
        latestPriceCache = new ConcurrentHashMap<>(priceMap);
        lastSnapshotTime = snapshotTime;

        // 6. 섹터별 결과 캐시 갱신
        refreshAllPeriodCache();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[섹터거래대금] 스냅샷 수집 완료 - 저장: {}, 정리: {}, 소요: {}ms",
                savedCount, cleanedCount, elapsed);
    }

    /**
     * 스냅샷에 저장할 누적 거래대금 결정 — 실측만 (테스트 대상).
     *
     * <p>우선순위: KIS 누적거래대금 → 현재가×거래량(실측 기반 폴백). 둘 다 없으면 null
     * — 해당 종목은 스냅샷에서 제외한다. 과거엔 휴장일에 시가총액×0.1% / 현재가×10000
     * "임시값"을 거래대금으로 저장해 휴장일 섹터 랭킹이 사실상 시총 랭킹으로 위장되던
     * 버그가 있었음 (점검 수정 2026-06-11). 거래대금 위장 생성 금지.
     */
    static BigDecimal resolveAccumulatedValue(StockPriceDto price) {
        if (price == null) {
            return null;
        }
        BigDecimal accumulated = price.getAccumulatedTradingValue();
        if (accumulated != null && accumulated.compareTo(BigDecimal.ZERO) > 0) {
            return accumulated;
        }
        if (price.getCurrentPrice() != null && price.getVolume() != null) {
            BigDecimal computed = price.getCurrentPrice().multiply(price.getVolume());
            if (computed.compareTo(BigDecimal.ZERO) > 0) {
                return computed;
            }
        }
        return null;
    }

    /**
     * 모든 기간의 캐시 갱신
     */
    private void refreshAllPeriodCache() {
        for (TradingPeriod period : TradingPeriod.values()) {
            try {
                List<SectorTradingDto> result = calculateSectorTrading(period);
                cachedResultByPeriod.put(period, result);
                lastCalculateTime.put(period, DateTimeUtil.kstNow());
            } catch (Exception e) {
                log.error("[섹터거래대금] {} 캐시 갱신 실패: {}", period, e.getMessage());
            }
        }
    }

    /**
     * 캐시된 시세 데이터 반환 (SectorAnalysisService 등에서 재활용)
     * - 중복 batch fetch 방지
     */
    public Map<String, StockPriceDto> getCachedPriceMap() {
        return Collections.unmodifiableMap(latestPriceCache);
    }

    // ========== API 메서드 (캐시에서 즉시 반환) ==========

    public List<SectorTradingDto> getAllSectorTrading() {
        return getAllSectorTrading(TradingPeriod.TODAY);
    }

    public List<SectorTradingDto> getAllSectorTrading(TradingPeriod period) {
        // Redis L2 우선 — 워머가 60초마다 period 별로 저장
        List<SectorTradingDto> l2 = redisCacheService.get(
                MarketCacheWarmerService.getCacheSectorTrading(), period.name(),
                new TypeReference<List<SectorTradingDto>>() {});
        if (l2 != null && !l2.isEmpty()) {
            return l2;
        }

        List<SectorTradingDto> cached = cachedResultByPeriod.get(period);
        if (cached != null && !cached.isEmpty()) {
            log.debug("[섹터거래대금] {} 캐시 HIT - {} 섹터", period, cached.size());
            return cached;
        }

        // 캐시 없으면 즉시 계산
        log.info("[섹터거래대금] {} 캐시 MISS - 즉시 계산 (캐시 종목: {})", period, latestPriceCache.size());
        List<SectorTradingDto> result = calculateSectorTrading(period);
        if (!result.isEmpty()) {
            // 디버그: 첫 번째 섹터의 changeRate 확인
            SectorTradingDto first = result.get(0);
            log.info("[섹터거래대금] 결과 예시 - {}: changeRate={}, tradingValue={}, topStocks={}",
                    first.getSectorName(), first.getChangeRate(), first.getTotalTradingValue(),
                    first.getTopStocks() != null ? first.getTopStocks().size() : 0);
        }
        cachedResultByPeriod.put(period, result);
        lastCalculateTime.put(period, DateTimeUtil.kstNow());
        return result;
    }

    public List<SectorTradingDto> getAllSectorTradingByPeriod(String periodStr) {
        TradingPeriod period = TradingPeriod.TODAY;
        if ("MIN_5".equalsIgnoreCase(periodStr)) {
            period = TradingPeriod.MIN_5;
        } else if ("MIN_30".equalsIgnoreCase(periodStr)) {
            period = TradingPeriod.MIN_30;
        }
        return getAllSectorTrading(period);
    }

    // ========== 핵심: 스냅샷 기반 거래대금 계산 ==========

    /**
     * 섹터별 거래대금 계산 (메모리에서만)
     */
    private List<SectorTradingDto> calculateSectorTrading(TradingPeriod period) {
        if (latestPriceCache.isEmpty()) {
            // 캐시 없으면 빈 결과 즉시 반환 (HTTP 스레드에서 30초 블로킹 방지)
            // 백그라운드에서 비동기 수집 트리거 → 다음 요청 시 캐시 HIT
            log.warn("[섹터거래대금] 시세 캐시 없음 - 빈 결과 반환 (백그라운드 수집 트리거)");
            CompletableFuture.runAsync(() -> {
                try {
                    collectSnapshot();
                } catch (Exception e) {
                    log.error("[섹터거래대금] 백그라운드 수집 실패: {}", e.getMessage());
                }
            }, sectorTradingExecutor);
            return Collections.emptyList();
        }

        List<SectorTradingDto> results = new ArrayList<>();
        ConcurrentMap<String, BigDecimal> uniqueStockTradingValue = new ConcurrentHashMap<>();

        for (SectorInfo sector : sectorConfig.getAllSectors()) {
            SectorTradingDto dto = buildSectorDto(sector, period, uniqueStockTradingValue);
            if (dto != null) {
                results.add(dto);
            }
        }

        // 전체 거래대금 계산 (섹터별 합계의 총합 — 중복 종목 비례 배분되어 합산 ≤ 100%)
        BigDecimal totalAllSectors = results.stream()
                .map(SectorTradingDto::getTotalTradingValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 비율 계산
        if (totalAllSectors.compareTo(BigDecimal.ZERO) > 0) {
            for (SectorTradingDto dto : results) {
                BigDecimal percentage = dto.getTotalTradingValue()
                        .multiply(BigDecimal.valueOf(100))
                        .divide(totalAllSectors, 2, RoundingMode.HALF_UP);
                dto.setPercentage(percentage);
            }
        }

        // 거래대금 순 정렬
        results.sort((a, b) -> b.getTotalTradingValue().compareTo(a.getTotalTradingValue()));

        return results;
    }

    /**
     * 개별 섹터 DTO 빌드
     */
    private SectorTradingDto buildSectorDto(SectorInfo sector, TradingPeriod period,
                                             ConcurrentMap<String, BigDecimal> uniqueStockMap) {
        SectorTradingDto dto = new SectorTradingDto();
        dto.setSectorCode(sector.getCode());
        dto.setSectorName(sector.getName());
        dto.setColor(sector.getColor());
        dto.setStockCount(sector.getStockCodes().size());

        List<StockTradingInfo> stockInfos = new ArrayList<>();
        BigDecimal totalTradingValue = BigDecimal.ZERO;

        for (String stockCode : sector.getStockCodes()) {
            StockTradingInfo info = buildStockInfo(stockCode, period);
            if (info != null && info.getTradingValue() != null
                    && info.getTradingValue().compareTo(BigDecimal.ZERO) > 0) {
                stockInfos.add(info);
                totalTradingValue = totalTradingValue.add(info.getTradingValue());

                // 중복 제거용 Map에 추가
                if (uniqueStockMap != null) {
                    uniqueStockMap.merge(stockCode, info.getTradingValue(), BigDecimal::max);
                }
            }
        }

        // 섹터 평균 등락률 계산 (상위 종목 단순 평균)
        // 거래대금 순 정렬 후 상위 5개 종목의 등락률 단순 평균
        stockInfos.sort((a, b) -> b.getTradingValue().compareTo(a.getTradingValue()));
        List<StockTradingInfo> topForAvg = stockInfos.stream().limit(5).collect(Collectors.toList());

        BigDecimal changeRateSum = BigDecimal.ZERO;
        int validCount = 0;
        for (StockTradingInfo info : topForAvg) {
            if (info.getChangeRate() != null && info.getChangeRate().compareTo(BigDecimal.ZERO) != 0) {
                changeRateSum = changeRateSum.add(info.getChangeRate());
                validCount++;
            }
        }
        if (validCount > 0) {
            BigDecimal avgChangeRate = changeRateSum.divide(BigDecimal.valueOf(validCount), 2, RoundingMode.HALF_UP);
            dto.setChangeRate(avgChangeRate);
            log.debug("[섹터등락률] {} = {}% (상위 {}종목 평균)", sector.getName(), avgChangeRate, validCount);
        } else {
            dto.setChangeRate(BigDecimal.ZERO);
            log.debug("[섹터등락률] {} - 등락률 미산출 (장 외 또는 API 지연)", sector.getName());
        }

        // 거래대금 순 정렬 후 상위 5개
        stockInfos.sort((a, b) -> b.getTradingValue().compareTo(a.getTradingValue()));
        dto.setTopStocks(stockInfos.stream().limit(5).collect(Collectors.toList()));
        dto.setTotalTradingValue(totalTradingValue);

        return dto;
    }

    /**
     * 개별 종목 거래 정보 빌드 (스냅샷 기반)
     */
    private StockTradingInfo buildStockInfo(String stockCode, TradingPeriod period) {
        StockPriceDto price = latestPriceCache.get(stockCode);
        if (price == null) {
            return null;
        }

        StockTradingInfo info = new StockTradingInfo();
        info.setStockCode(stockCode);
        // price에서 종목명이 없으면 SectorStockConfig에서 조회
        String stockName = price.getStockName();
        if (stockName == null || stockName.isEmpty()) {
            stockName = sectorConfig.getStockName(stockCode);
        }
        info.setStockName(stockName);
        info.setCurrentPrice(price.getCurrentPrice());

        // changeRate 보완: 3단계 폴백
        BigDecimal changeRate = price.getChangeRate();

        // 1차: changePrice(전일대비)에서 재계산
        if ((changeRate == null || changeRate.compareTo(BigDecimal.ZERO) == 0)
                && price.getChangePrice() != null
                && price.getChangePrice().compareTo(BigDecimal.ZERO) != 0
                && price.getCurrentPrice() != null) {
            BigDecimal prevClose = price.getCurrentPrice().subtract(price.getChangePrice());
            if (prevClose.compareTo(BigDecimal.ZERO) > 0) {
                changeRate = price.getChangePrice()
                        .divide(prevClose, 2, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }
        }

        // 2차: 여전히 없으면 volume 기반 추정 또는 0 허용 (경고 로그는 debug로 낮춤)
        if (changeRate == null) {
            changeRate = BigDecimal.ZERO;
        }

        info.setChangeRate(changeRate);

        // 거래대금 계산
        BigDecimal tradingValue = calculateTradingValueFromSnapshot(stockCode, period, price);
        info.setTradingValue(tradingValue);

        return info;
    }

    /**
     * 스냅샷 기반 거래대금 계산
     *
     * - TODAY: 최신 누적 거래대금
     * - MIN_5: 현재값 - 5분 전 값
     * - MIN_30: 현재값 - 30분 전 값
     */
    private BigDecimal calculateTradingValueFromSnapshot(String stockCode, TradingPeriod period, StockPriceDto price) {
        // TODAY: 최신 누적 거래대금 그대로 반환
        if (period == TradingPeriod.TODAY) {
            if (price.getAccumulatedTradingValue() != null
                    && price.getAccumulatedTradingValue().compareTo(BigDecimal.ZERO) > 0) {
                return price.getAccumulatedTradingValue();
            }
            if (price.getCurrentPrice() != null && price.getVolume() != null) {
                return price.getCurrentPrice().multiply(price.getVolume());
            }
            return BigDecimal.ZERO;
        }

        // MIN_5, MIN_30: 스냅샷에서 차이 계산
        TreeMap<LocalDateTime, BigDecimal> history = tradingHistoryStore.get(stockCode);
        if (history == null || history.isEmpty()) {
            // 스냅샷 없으면 추정치 반환
            return estimateTradingValue(price, period.getMinutes());
        }

        // 현재값 (최신 스냅샷)
        Map.Entry<LocalDateTime, BigDecimal> latestEntry = history.lastEntry();
        if (latestEntry == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal currentValue = latestEntry.getValue();
        LocalDateTime currentTime = latestEntry.getKey();

        // N분 전 값 찾기 (floorEntry 사용)
        LocalDateTime targetTime = currentTime.minusMinutes(period.getMinutes());
        Map.Entry<LocalDateTime, BigDecimal> pastEntry = history.floorEntry(targetTime);

        if (pastEntry == null) {
            // N분 전 데이터가 없으면 가장 오래된 데이터 사용
            pastEntry = history.firstEntry();
            if (pastEntry == null || pastEntry.equals(latestEntry)) {
                // 데이터가 하나뿐이면 추정치 반환
                return estimateTradingValue(price, period.getMinutes());
            }
        }

        BigDecimal pastValue = pastEntry.getValue();

        // 차이 계산 (현재 - 과거)
        BigDecimal diff = currentValue.subtract(pastValue);

        // 음수 방지 (장 초반 또는 데이터 오류)
        if (diff.compareTo(BigDecimal.ZERO) < 0) {
            diff = BigDecimal.ZERO;
        }

        return diff;
    }

    /**
     * 스냅샷 부족 시 추정치 계산
     */
    private BigDecimal estimateTradingValue(StockPriceDto price, int periodMinutes) {
        BigDecimal todayValue = BigDecimal.ZERO;
        if (price.getAccumulatedTradingValue() != null
                && price.getAccumulatedTradingValue().compareTo(BigDecimal.ZERO) > 0) {
            todayValue = price.getAccumulatedTradingValue();
        } else if (price.getCurrentPrice() != null && price.getVolume() != null) {
            todayValue = price.getCurrentPrice().multiply(price.getVolume());
        }

        if (todayValue.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        // 장 시간 기준 비율 계산
        LocalTime now = LocalTime.now();
        long elapsedMinutes = java.time.Duration.between(MARKET_OPEN, now).toMinutes();
        if (elapsedMinutes < 1) {
            elapsedMinutes = 1;
        }

        // 비율: periodMinutes / elapsedMinutes
        BigDecimal ratio = BigDecimal.valueOf(periodMinutes)
                .divide(BigDecimal.valueOf(Math.max(elapsedMinutes, periodMinutes)), 6, RoundingMode.HALF_UP);

        if (ratio.compareTo(BigDecimal.ONE) > 0) {
            ratio = BigDecimal.ONE;
        }

        return todayValue.multiply(ratio).setScale(0, RoundingMode.HALF_UP);
    }

    // ========== 섹터 로테이션 감지 ==========

    /**
     * 장 마감 시 당일 섹터별 거래대금을 '전일 캐시'로 저장
     * - 매일 15:35에 실행 (장 마감 직전)
     */
    @Scheduled(scheduler = "cacheScheduler", cron = "0 35 15 * * MON-FRI", zone = "Asia/Seoul")
    public void saveYesterdaySnapshot() {
        if (isMarketClosed()) return;

        Map<String, BigDecimal> todayValues = new HashMap<>();
        for (SectorInfo sector : sectorConfig.getAllSectors()) {
            BigDecimal sectorTotal = BigDecimal.ZERO;
            for (String stockCode : sector.getStockCodes()) {
                StockPriceDto price = latestPriceCache.get(stockCode);
                if (price != null) {
                    BigDecimal val = price.getAccumulatedTradingValue();
                    if (val == null || val.compareTo(BigDecimal.ZERO) <= 0) {
                        if (price.getCurrentPrice() != null && price.getVolume() != null) {
                            val = price.getCurrentPrice().multiply(price.getVolume());
                        }
                    }
                    if (val != null && val.compareTo(BigDecimal.ZERO) > 0) {
                        sectorTotal = sectorTotal.add(val);
                    }
                }
            }
            if (sectorTotal.compareTo(BigDecimal.ZERO) > 0) {
                // 억 단위로 변환
                todayValues.put(sector.getCode(),
                        sectorTotal.divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP));
            }
        }

        if (!todayValues.isEmpty()) {
            yesterdayTradingValueCache = new ConcurrentHashMap<>(todayValues);
            yesterdayCacheDate = LocalDate.now();
            log.info("[섹터로테이션] 전일 거래대금 스냅샷 저장 완료 - {} 섹터", todayValues.size());
        }
    }

    /**
     * 섹터 로테이션 감지 - 전일 대비 자금 흐름 변화
     * @return 섹터별 유입/유출 변화율 및 방향
     */
    public List<SectorRotationDto> getSectorRotation() {
        List<SectorRotationDto> results = new ArrayList<>();

        // 1. 오늘 섹터별 거래대금 계산 (억 단위)
        Map<String, BigDecimal> todayValues = new HashMap<>();
        Map<String, BigDecimal> sectorChangeRates = new HashMap<>();
        // regime 판정용 — 상위 5개가 아닌 섹터 전 종목 raw 평균 (하락 종목 포함)
        Map<String, BigDecimal> sectorRawAvgRates = new HashMap<>();

        for (SectorInfo sector : sectorConfig.getAllSectors()) {
            BigDecimal sectorTotal = BigDecimal.ZERO;
            List<BigDecimal> changeRates = new ArrayList<>();

            for (String stockCode : sector.getStockCodes()) {
                StockPriceDto price = latestPriceCache.get(stockCode);
                if (price != null) {
                    BigDecimal val = price.getAccumulatedTradingValue();
                    if (val == null || val.compareTo(BigDecimal.ZERO) <= 0) {
                        if (price.getCurrentPrice() != null && price.getVolume() != null) {
                            val = price.getCurrentPrice().multiply(price.getVolume());
                        }
                    }
                    if (val != null && val.compareTo(BigDecimal.ZERO) > 0) {
                        sectorTotal = sectorTotal.add(val);
                    }
                    if (price.getChangeRate() != null) {
                        changeRates.add(price.getChangeRate());
                    }
                }
            }

            // 억 단위 변환
            BigDecimal todayInBillion = sectorTotal.divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP);
            todayValues.put(sector.getCode(), todayInBillion);

            // 섹터 평균 등락률 (상위 5개 종목 평균 — 주도주 표시용)
            if (!changeRates.isEmpty()) {
                // regime 판정용 raw 평균 먼저 (정렬 전, 하락 종목 포함 전체 평균)
                BigDecimal rawSum = BigDecimal.ZERO;
                for (BigDecimal r : changeRates) rawSum = rawSum.add(r);
                sectorRawAvgRates.put(sector.getCode(),
                        rawSum.divide(BigDecimal.valueOf(changeRates.size()), 2, RoundingMode.HALF_UP));

                changeRates.sort(Comparator.reverseOrder());
                BigDecimal sum = BigDecimal.ZERO;
                int count = Math.min(5, changeRates.size());
                for (int i = 0; i < count; i++) {
                    sum = sum.add(changeRates.get(i));
                }
                sectorChangeRates.put(sector.getCode(),
                        sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP));
            }
        }

        // 2. 전일 데이터 확인
        Map<String, BigDecimal> yesterdayValues = yesterdayTradingValueCache;
        boolean hasYesterdayData = !yesterdayValues.isEmpty();

        if (!hasYesterdayData) {
            log.info("[섹터로테이션] 전일 캐시 없음 - 종목별 평균 등락률 기반으로 자금 흐름 판단");
        }

        // 3. 변화율 계산 및 DTO 생성
        for (SectorInfo sector : sectorConfig.getAllSectors()) {
            String code = sector.getCode();
            BigDecimal today = todayValues.getOrDefault(code, BigDecimal.ZERO);

            if (today.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal changeRate;
            BigDecimal changeAmount;
            BigDecimal yesterday;

            if (hasYesterdayData) {
                // 전일 대비 거래대금 변화율
                yesterday = yesterdayValues.getOrDefault(code, BigDecimal.ZERO);
                changeAmount = today.subtract(yesterday);
                changeRate = yesterday.compareTo(BigDecimal.ZERO) > 0
                        ? changeAmount.multiply(new BigDecimal("100")).divide(yesterday, 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
            } else {
                // 전일 데이터 없으면 섹터 평균 등락률을 자금 흐름 지표로 사용
                changeRate = sectorChangeRates.getOrDefault(code, BigDecimal.ZERO);
                yesterday = BigDecimal.ZERO;
                changeAmount = BigDecimal.ZERO;
            }

            // 4. 방향 분류: >1% INFLOW, <-1% OUTFLOW, else NEUTRAL (전일 없을 때는 등락률 기준)
            BigDecimal threshold = hasYesterdayData ? new BigDecimal("10") : new BigDecimal("1");
            String direction;
            if (changeRate.compareTo(threshold) > 0) {
                direction = "INFLOW";
            } else if (changeRate.compareTo(threshold.negate()) < 0) {
                direction = "OUTFLOW";
            } else {
                direction = "NEUTRAL";
            }

            results.add(SectorRotationDto.builder()
                    .sectorCode(code)
                    .sectorName(sector.getName())
                    .todayTradingValue(today)
                    .yesterdayTradingValue(yesterday)
                    .changeRate(changeRate)
                    .changeAmount(changeAmount)
                    .flowDirection(direction)
                    .avgChangeRate(sectorChangeRates.getOrDefault(code, BigDecimal.ZERO))
                    .rawAvgChangeRate(sectorRawAvgRates.getOrDefault(code, BigDecimal.ZERO))
                    .build());
        }

        // 5. 절대 변화율 크기순 정렬
        results.sort((a, b) -> b.getChangeRate().abs().compareTo(a.getChangeRate().abs()));

        log.info("[섹터로테이션] 조회 완료 - {} 섹터 (INFLOW: {}, OUTFLOW: {}, NEUTRAL: {})",
                results.size(),
                results.stream().filter(r -> "INFLOW".equals(r.getFlowDirection())).count(),
                results.stream().filter(r -> "OUTFLOW".equals(r.getFlowDirection())).count(),
                results.stream().filter(r -> "NEUTRAL".equals(r.getFlowDirection())).count());

        return results;
    }

    // ========== 관리용 메서드 ==========

    public void forceRefresh() {
        log.info("[섹터거래대금] 수동 갱신 요청");
        CompletableFuture.runAsync(this::collectSnapshot, sectorTradingExecutor);
    }

    public void clearCache() {
        log.info("[섹터거래대금] 캐시 초기화");
        tradingHistoryStore.clear();
        cachedResultByPeriod.clear();
        latestPriceCache = new ConcurrentHashMap<>();
    }

    public SectorTradingDto getSectorDetail(String sectorCode) {
        return getSectorDetail(sectorCode, TradingPeriod.TODAY);
    }

    public SectorTradingDto getSectorDetail(String sectorCode, TradingPeriod period) {
        SectorInfo sector = sectorConfig.getSector(sectorCode);
        if (sector == null) {
            return null;
        }
        return buildSectorDto(sector, period, null);
    }

    public Map<String, Object> getCacheStatus() {
        Map<String, Object> status = new HashMap<>();

        // 휴장일 여부
        boolean marketClosed = isMarketClosed();
        status.put("marketClosed", marketClosed);
        status.put("marketStatus", marketClosed ? "휴장 (주말/공휴일)" : "정상 거래일");

        // 스냅샷 저장소 상태
        status.put("snapshotStockCount", tradingHistoryStore.size());
        status.put("lastSnapshotTime", lastSnapshotTime);

        // 각 종목의 스냅샷 개수 통계
        int totalSnapshots = tradingHistoryStore.values().stream()
                .mapToInt(TreeMap::size)
                .sum();
        status.put("totalSnapshotCount", totalSnapshots);

        // 기간별 캐시 상태
        Map<String, Object> periodStatus = new HashMap<>();
        for (TradingPeriod period : TradingPeriod.values()) {
            Map<String, Object> ps = new HashMap<>();
            List<SectorTradingDto> cached = cachedResultByPeriod.get(period);
            ps.put("hasCachedData", cached != null && !cached.isEmpty());
            ps.put("sectorCount", cached != null ? cached.size() : 0);
            ps.put("lastCalculateTime", lastCalculateTime.get(period));
            periodStatus.put(period.name(), ps);
        }
        status.put("byPeriod", periodStatus);

        return status;
    }

    /**
     * 섹터 설정 조회 (외부 서비스에서 섹터-종목 매핑 참조용)
     */
    public SectorStockConfig getSectorConfig() {
        return sectorConfig;
    }
}
