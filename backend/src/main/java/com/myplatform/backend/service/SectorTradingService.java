package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.config.SectorStockConfig.SectorInfo;
import com.myplatform.backend.dto.SectorTradingDto;
import com.myplatform.backend.dto.SectorTradingDto.StockTradingInfo;
import com.myplatform.backend.dto.StockPriceDto;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.concurrent.TimeoutException;

/**
 * 거래대금 조회 기간
 */
enum TradingPeriod {
    TODAY(0, "오늘누적"),      // 오늘 누적 거래대금
    MIN_5(5, "5분파워"),       // 최근 5분 거래대금
    MIN_30(30, "30분파워");    // 최근 30분 거래대금

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
 * [백그라운드 캐싱 패턴]
 * - 사용자 요청 시 API 호출 없이 메모리 캐시에서 즉시 반환 (0.1초 이내)
 * - 백그라운드에서 1분마다 자동 갱신 (평일 09:00~15:40)
 * - 서버 시작 시 자동 초기화
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SectorTradingService {

    private final SectorStockConfig sectorConfig;
    private final StockPriceService stockPriceService;
    private final ThreadPoolTaskExecutor sectorTradingExecutor;
    private final AsyncCrawlerService asyncCrawlerService;

    // ========== 백그라운드 캐싱 ==========
    // 메모리 캐시 (volatile로 가시성 보장)
    private volatile List<SectorTradingDto> cachedSectorData = null;
    private volatile LocalDateTime lastUpdateTime = null;

    // 갱신 중복 방지 플래그
    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);

    // 장 시간 설정 (테스트용으로 23:00까지 확장)
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(23, 0);  // TODO: 운영 시 15:40으로 변경

    // ========== 초기화 ==========

    /**
     * 서버 시작 시 캐시 초기화
     */
    @PostConstruct
    public void initializeCache() {
        log.info("[섹터거래대금] 서버 시작 - 캐시 초기화 시작");

        // 비동기로 초기화 (서버 시작 지연 방지)
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(5000);  // 다른 서비스 초기화 대기
                refreshCacheInternal();
                log.info("[섹터거래대금] 초기 캐시 로드 완료 - {} 섹터",
                        cachedSectorData != null ? cachedSectorData.size() : 0);
            } catch (Exception e) {
                log.error("[섹터거래대금] 초기 캐시 로드 실패: {}", e.getMessage());
            }
        }, sectorTradingExecutor);
    }

    // ========== 스케줄러 (백그라운드 갱신) ==========

    /**
     * 평일 09:00~15:40, 1분마다 캐시 갱신
     * - 장 시간에만 실행
     * - 중복 실행 방지
     */
    @Scheduled(cron = "0 */1 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledCacheRefresh() {
        // 장 시간 체크
        LocalTime now = LocalTime.now();
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) {
            return;
        }

        // 중복 실행 방지
        if (!isRefreshing.compareAndSet(false, true)) {
            log.debug("[섹터거래대금] 이미 갱신 중 - 스킵");
            return;
        }

        try {
            log.debug("[섹터거래대금] 스케줄 캐시 갱신 시작");
            refreshCacheInternal();
        } finally {
            isRefreshing.set(false);
        }
    }

    /**
     * 수동 캐시 갱신 (관리자용)
     */
    public void forceRefresh() {
        log.info("[섹터거래대금] 수동 캐시 갱신 요청");
        if (isRefreshing.compareAndSet(false, true)) {
            try {
                refreshCacheInternal();
            } finally {
                isRefreshing.set(false);
            }
        }
    }

    // ========== API 메서드 (캐시에서 즉시 반환) ==========

    /**
     * 모든 섹터의 거래대금 조회 (기본: 오늘 누적)
     * - 캐시에서 즉시 반환 (0.1초 이내)
     * - 캐시가 없으면 최초 1회 수집
     */
    public List<SectorTradingDto> getAllSectorTrading() {
        return getAllSectorTrading(TradingPeriod.TODAY);
    }

    /**
     * 모든 섹터의 거래대금 조회 (기간별)
     *
     * [백그라운드 캐싱 + 방어 로직]
     * - API 호출 없이 메모리 캐시에서 즉시 반환
     * - 캐시가 비어있으면 동기적으로 강제 수집 (빈 화면 방지)
     * - 다른 쓰레드가 갱신 중이면 완료될 때까지 대기
     */
    public List<SectorTradingDto> getAllSectorTrading(TradingPeriod period) {
        // 1. 캐시가 있으면 즉시 반환 (0.1초 이내)
        if (cachedSectorData != null && !cachedSectorData.isEmpty()) {
            log.debug("[섹터거래대금] 캐시 반환 - {} 섹터, 마지막 갱신: {}",
                    cachedSectorData.size(), lastUpdateTime);
            return cachedSectorData;
        }

        // 2. 캐시가 비어있음 - 동기적으로 강제 수집
        log.info("[섹터거래대금] 캐시 없음 - 동기 수집 시작 (빈 화면 방지)");

        // 2-1. 다른 쓰레드가 갱신 중인지 확인
        if (isRefreshing.compareAndSet(false, true)) {
            // 내가 갱신 담당
            try {
                log.info("[섹터거래대금] 동기 수집 시작 (이 쓰레드가 담당)");
                refreshCacheInternal();
                log.info("[섹터거래대금] 동기 수집 완료 - {} 섹터",
                        cachedSectorData != null ? cachedSectorData.size() : 0);
            } finally {
                isRefreshing.set(false);
            }
        } else {
            // 다른 쓰레드가 갱신 중 - 완료될 때까지 대기 (최대 30초)
            log.info("[섹터거래대금] 다른 쓰레드가 갱신 중 - 완료 대기...");
            int waitCount = 0;
            int maxWait = 60;  // 최대 30초 (500ms * 60)
            while (isRefreshing.get() && waitCount < maxWait) {
                try {
                    Thread.sleep(500);
                    waitCount++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("[섹터거래대금] 대기 완료 ({}ms) - 캐시 상태: {}",
                    waitCount * 500, cachedSectorData != null ? cachedSectorData.size() + "섹터" : "없음");
        }

        // 3. 여전히 캐시가 없으면 빈 리스트 반환 (극히 드문 경우)
        if (cachedSectorData == null || cachedSectorData.isEmpty()) {
            log.warn("[섹터거래대금] 동기 수집 후에도 캐시 없음 - 빈 리스트 반환");
            return Collections.emptyList();
        }

        return cachedSectorData;
    }

    // ========== 내부 캐시 갱신 로직 ==========

    /**
     * 실제 API 호출 및 캐시 갱신
     * - 상세 로그로 진행 상황 추적
     */
    private void refreshCacheInternal() {
        long startTime = System.currentTimeMillis();
        TradingPeriod period = TradingPeriod.TODAY;

        log.info("[섹터거래대금] ========== refreshCacheInternal 시작 ==========");

        try {
            // 1. 모든 종목 코드 수집
            Set<String> allStockCodes = new HashSet<>();
            List<SectorInfo> allSectors = sectorConfig.getAllSectors();
            log.info("[섹터거래대금] [1/5] 섹터 설정 로드 - {} 섹터", allSectors.size());

            for (SectorInfo sector : allSectors) {
                allStockCodes.addAll(sector.getStockCodes());
            }
            log.info("[섹터거래대금] [2/5] 전체 종목 수집 - {} 종목", allStockCodes.size());

            // 2. Batch로 한 번에 시세 조회
            log.info("[섹터거래대금] [3/5] Batch 시세 조회 시작...");
            Map<String, StockPriceDto> stockPriceMap = stockPriceService.getStockPrices(new ArrayList<>(allStockCodes));
            log.info("[섹터거래대금] [3/5] Batch 시세 조회 완료 - 요청: {}, 응답: {}", allStockCodes.size(), stockPriceMap.size());

            if (stockPriceMap.isEmpty()) {
                log.error("[섹터거래대금] 시세 데이터가 비어있음! API 호출 실패 가능성");
            }

            // 분봉 데이터 (크롤링 중이면 스킵)
            Map<String, BigDecimal> minuteTradingValueMap = new HashMap<>();

            // 중복 종목 제거용 Map
            ConcurrentMap<String, BigDecimal> uniqueStockTradingValue = new ConcurrentHashMap<>();

            // 4. 섹터별 병렬 처리
            log.info("[섹터거래대금] [4/5] 섹터별 DTO 빌드 시작 (병렬)...");
            List<CompletableFuture<SectorTradingDto>> futures = sectorConfig.getAllSectors().stream()
                    .map(sector -> CompletableFuture.supplyAsync(
                            () -> buildSectorTradingDto(sector, period, stockPriceMap, uniqueStockTradingValue, minuteTradingValueMap),
                            sectorTradingExecutor
                    ))
                    .collect(Collectors.toList());

            // 모든 섹터 처리 완료 대기 (타임아웃 30초로 증가)
            List<SectorTradingDto> results = futures.stream()
                    .map(future -> {
                        try {
                            return future.get(30, TimeUnit.SECONDS);
                        } catch (TimeoutException e) {
                            log.error("[섹터거래대금] 섹터 처리 타임아웃 (30초 초과)");
                            return null;
                        } catch (Exception e) {
                            log.error("[섹터거래대금] 섹터 처리 실패: {}", e.getMessage(), e);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            log.info("[섹터거래대금] [4/5] 섹터별 DTO 빌드 완료 - {} 섹터 성공", results.size());

            // 5. 전체 시장 거래대금 (중복 제거)
            BigDecimal totalAllSectors = uniqueStockTradingValue.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            log.info("[섹터거래대금] [5/5] 결과 정리 - 전체 거래대금: {}억, 고유 종목: {}",
                    totalAllSectors.divide(BigDecimal.valueOf(100_000_000), 0, RoundingMode.HALF_UP),
                    uniqueStockTradingValue.size());

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

            // 캐시 갱신 (volatile 쓰기)
            this.cachedSectorData = results;
            this.lastUpdateTime = LocalDateTime.now();

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[섹터거래대금] ========== refreshCacheInternal 완료 ==========");
            log.info("[섹터거래대금] 결과: {} 섹터, {}억원, 소요: {}ms",
                    results.size(),
                    totalAllSectors.divide(BigDecimal.valueOf(100_000_000), 0, RoundingMode.HALF_UP),
                    elapsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[섹터거래대금] ========== refreshCacheInternal 실패 ==========");
            log.error("[섹터거래대금] 에러 발생 ({}ms 경과): {}", elapsed, e.getMessage());
            log.error("[섹터거래대금] 스택 트레이스:", e);
        }
    }

    /**
     * 문자열로 기간 조회 (컨트롤러용)
     */
    public List<SectorTradingDto> getAllSectorTradingByPeriod(String periodStr) {
        TradingPeriod period = TradingPeriod.TODAY;
        if ("MIN_5".equalsIgnoreCase(periodStr)) {
            period = TradingPeriod.MIN_5;
        } else if ("MIN_30".equalsIgnoreCase(periodStr)) {
            period = TradingPeriod.MIN_30;
        }
        return getAllSectorTrading(period);
    }

    /**
     * 특정 섹터의 거래대금 상세 조회
     */
    public SectorTradingDto getSectorDetail(String sectorCode) {
        return getSectorDetail(sectorCode, TradingPeriod.TODAY);
    }

    /**
     * 특정 섹터의 거래대금 상세 조회 (기간별)
     */
    public SectorTradingDto getSectorDetail(String sectorCode, TradingPeriod period) {
        SectorInfo sector = sectorConfig.getSector(sectorCode);
        if (sector == null) {
            return null;
        }

        // 해당 섹터 종목들만 Batch 조회
        Map<String, StockPriceDto> stockPriceMap = stockPriceService.getStockPrices(sector.getStockCodes());

        // 분봉 데이터도 Batch 조회 (MIN_5, MIN_30인 경우)
        Map<String, BigDecimal> minuteTradingValueMap = new HashMap<>();
        if (period != TradingPeriod.TODAY) {
            minuteTradingValueMap = stockPriceService.getTradingValueForMinutesBatch(
                    sector.getStockCodes(), period.getMinutes());
        }

        return buildSectorTradingDto(sector, period, stockPriceMap, null, minuteTradingValueMap);
    }

    /**
     * 섹터 거래대금 DTO 빌드
     *
     * @param sector 섹터 정보
     * @param period 조회 기간
     * @param stockPriceMap 미리 조회한 시세 Map
     * @param uniqueStockMap 중복 제거용 Map (전체 조회 시 사용, null이면 추적 안 함)
     * @param minuteTradingValueMap 미리 조회한 분봉 거래대금 Map (MIN_5, MIN_30용)
     */
    private SectorTradingDto buildSectorTradingDto(
            SectorInfo sector,
            TradingPeriod period,
            Map<String, StockPriceDto> stockPriceMap,
            ConcurrentMap<String, BigDecimal> uniqueStockMap,
            Map<String, BigDecimal> minuteTradingValueMap
    ) {
        SectorTradingDto dto = new SectorTradingDto();
        dto.setSectorCode(sector.getCode());
        dto.setSectorName(sector.getName());
        dto.setColor(sector.getColor());
        dto.setStockCount(sector.getStockCodes().size());

        List<StockTradingInfo> stockInfos = new ArrayList<>();
        BigDecimal totalTradingValue = BigDecimal.ZERO;

        for (String stockCode : sector.getStockCodes()) {
            StockTradingInfo info = buildStockTradingInfo(stockCode, period, stockPriceMap, minuteTradingValueMap);
            if (info != null && info.getTradingValue() != null) {
                stockInfos.add(info);
                totalTradingValue = totalTradingValue.add(info.getTradingValue());

                // Unique Stock 추적 (ConcurrentMap으로 thread-safe)
                if (uniqueStockMap != null) {
                    uniqueStockMap.merge(
                            stockCode,
                            info.getTradingValue(),
                            BigDecimal::max
                    );
                }
            }
        }

        // 거래대금 순 정렬 후 상위 5개
        stockInfos.sort((a, b) -> b.getTradingValue().compareTo(a.getTradingValue()));
        dto.setTopStocks(stockInfos.stream().limit(5).collect(Collectors.toList()));
        dto.setTotalTradingValue(totalTradingValue);

        return dto;
    }

    /**
     * 개별 종목 거래 정보 빌드 (미리 조회된 시세 Map 사용)
     */
    private StockTradingInfo buildStockTradingInfo(
            String stockCode,
            TradingPeriod period,
            Map<String, StockPriceDto> stockPriceMap,
            Map<String, BigDecimal> minuteTradingValueMap
    ) {
        StockPriceDto price = stockPriceMap.get(stockCode);
        if (price == null) {
            return null;
        }

        StockTradingInfo info = new StockTradingInfo();
        info.setStockCode(stockCode);

        // 종목명 설정
        String stockName = price.getStockName();
        if (stockName == null || stockName.isEmpty()) {
            stockName = sectorConfig.getStockName(stockCode);
        }
        info.setStockName(stockName);
        info.setCurrentPrice(price.getCurrentPrice());
        info.setChangeRate(price.getChangeRate());

        // 거래대금 계산 (분봉 데이터 미리 조회된 것 사용)
        BigDecimal tradingValue = calculateTradingValue(stockCode, period, price, minuteTradingValueMap);
        info.setTradingValue(tradingValue);

        return info;
    }

    /**
     * 거래대금 계산 (분봉 데이터는 미리 조회된 Map 사용)
     */
    private BigDecimal calculateTradingValue(
            String stockCode,
            TradingPeriod period,
            StockPriceDto price,
            Map<String, BigDecimal> minuteTradingValueMap
    ) {
        if (period == TradingPeriod.TODAY) {
            // 1순위: API 제공 누적 거래대금
            if (price.getAccumulatedTradingValue() != null
                    && price.getAccumulatedTradingValue().compareTo(BigDecimal.ZERO) > 0) {
                return price.getAccumulatedTradingValue();
            }
            // 2순위: 현재가 * 거래량
            if (price.getCurrentPrice() != null && price.getVolume() != null) {
                return price.getCurrentPrice().multiply(price.getVolume());
            }
        } else {
            // 5분/30분 파워: 미리 조회된 분봉 거래대금 사용 (N+1 문제 해결)
            BigDecimal minuteTradingValue = minuteTradingValueMap != null
                    ? minuteTradingValueMap.get(stockCode)
                    : null;
            if (minuteTradingValue != null && minuteTradingValue.compareTo(BigDecimal.ZERO) > 0) {
                return minuteTradingValue;
            }

            // 폴백: 분봉 데이터 없으면 오늘 누적 거래대금 사용
            if (price.getAccumulatedTradingValue() != null
                    && price.getAccumulatedTradingValue().compareTo(BigDecimal.ZERO) > 0) {
                return price.getAccumulatedTradingValue();
            }
            if (price.getCurrentPrice() != null && price.getVolume() != null) {
                return price.getCurrentPrice().multiply(price.getVolume());
            }
        }

        return BigDecimal.ZERO;
    }

    /**
     * 캐시 초기화
     */
    public void clearCache() {
        log.info("[섹터거래대금] 캐시 초기화");
        this.cachedSectorData = null;
        this.lastUpdateTime = null;
    }

    /**
     * 캐시 상태 조회 (관리자용)
     */
    public Map<String, Object> getCacheStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("hasCachedData", cachedSectorData != null && !cachedSectorData.isEmpty());
        status.put("sectorCount", cachedSectorData != null ? cachedSectorData.size() : 0);
        status.put("lastUpdateTime", lastUpdateTime);
        status.put("isRefreshing", isRefreshing.get());
        return status;
    }
}
