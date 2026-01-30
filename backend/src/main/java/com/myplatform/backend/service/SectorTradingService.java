package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.config.SectorStockConfig.SectorInfo;
import com.myplatform.backend.dto.SectorTradingDto;
import com.myplatform.backend.dto.SectorTradingDto.StockTradingInfo;
import com.myplatform.backend.dto.StockPriceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

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
 * [개선 사항]
 * 1. N+1 문제 해결: Batch 조회로 API 호출 최소화
 * 2. 스레드 풀 관리: Spring TaskExecutor 사용 (모니터링, Graceful Shutdown)
 * 3. 캐싱 현대화: @Cacheable + Caffeine (자동 TTL 관리)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SectorTradingService {

    private final SectorStockConfig sectorConfig;
    private final StockPriceService stockPriceService;
    private final ThreadPoolTaskExecutor sectorTradingExecutor;
    private final AsyncCrawlerService asyncCrawlerService;

    /**
     * 모든 섹터의 거래대금 조회 (기본: 오늘 누적)
     */
    public List<SectorTradingDto> getAllSectorTrading() {
        return getAllSectorTrading(TradingPeriod.TODAY);
    }

    /**
     * 모든 섹터의 거래대금 조회 (기간별)
     *
     * [개선 3] @Cacheable 적용 - 1분 TTL 자동 관리
     * - 캐시 키: period.name() (TODAY, MIN_5, MIN_30)
     * - 캐시 설정: CacheConfig에서 관리
     */
    @Cacheable(value = "sectorTrading", key = "#period.name()")
    public List<SectorTradingDto> getAllSectorTrading(TradingPeriod period) {
        log.info("섹터 거래대금 조회 시작 - period: {}", period.getDisplayName());
        long startTime = System.currentTimeMillis();

        // ========== [개선 1] 모든 종목 코드를 먼저 수집 ==========
        Set<String> allStockCodes = new HashSet<>();
        for (SectorInfo sector : sectorConfig.getAllSectors()) {
            allStockCodes.addAll(sector.getStockCodes());
        }

        // ========== [개선 1] Batch로 한 번에 시세 조회 (N+1 해결) ==========
        Map<String, StockPriceDto> stockPriceMap = stockPriceService.getStockPrices(new ArrayList<>(allStockCodes));
        log.debug("Batch 시세 조회 완료 - 요청: {}, 응답: {}", allStockCodes.size(), stockPriceMap.size());

        // ========== [개선 3] 분봉 데이터 Batch 조회 (MIN_5, MIN_30) ==========
        Map<String, BigDecimal> minuteTradingValueMap = new HashMap<>();
        boolean minuteDataAvailable = true;
        if (period != TradingPeriod.TODAY) {
            // 크롤링 중이면 분봉 조회 스킵 (Rate Limit 방지)
            if (asyncCrawlerService.isAnyTaskRunning()) {
                log.warn("크롤링 작업 진행 중 - 분봉 데이터 조회 스킵, 오늘 누적 데이터 사용");
                minuteDataAvailable = false;
            } else {
                minuteTradingValueMap = stockPriceService.getTradingValueForMinutesBatch(
                        new ArrayList<>(allStockCodes), period.getMinutes());
                log.debug("분봉 거래대금 Batch 조회 완료 - 요청: {}, 응답: {}", allStockCodes.size(), minuteTradingValueMap.size());

                // 분봉 데이터가 거의 없으면 (50% 미만) 오늘 누적 데이터 사용
                if (minuteTradingValueMap.size() < allStockCodes.size() / 2) {
                    log.warn("분봉 데이터 부족 ({}/{}) - 오늘 누적 데이터 사용",
                            minuteTradingValueMap.size(), allStockCodes.size());
                    minuteDataAvailable = false;
                    minuteTradingValueMap.clear();
                }
            }
        }

        // ========== 중복 종목 제거용 Map (Thread-safe) ==========
        ConcurrentMap<String, BigDecimal> uniqueStockTradingValue = new ConcurrentHashMap<>();

        // ========== [개선 2] Spring TaskExecutor로 섹터별 병렬 처리 ==========
        final Map<String, BigDecimal> finalMinuteTradingValueMap = minuteTradingValueMap;
        List<CompletableFuture<SectorTradingDto>> futures = sectorConfig.getAllSectors().stream()
                .map(sector -> CompletableFuture.supplyAsync(
                        () -> buildSectorTradingDto(sector, period, stockPriceMap, uniqueStockTradingValue, finalMinuteTradingValueMap),
                        sectorTradingExecutor
                ))
                .collect(Collectors.toList());

        // 모든 섹터 처리 완료 대기
        List<SectorTradingDto> results = futures.stream()
                .map(future -> {
                    try {
                        return future.get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.error("섹터 데이터 처리 실패: {}", e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 전체 시장 거래대금 (중복 제거)
        BigDecimal totalAllSectors = uniqueStockTradingValue.values().stream()
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

        long elapsed = System.currentTimeMillis() - startTime;
        String dataSource = (period == TradingPeriod.TODAY || !minuteDataAvailable) ? "누적" : "분봉";
        log.info("섹터 거래대금 조회 완료 - 전체: {}억, Unique 종목: {}, 소요: {}ms, 데이터: {}",
                totalAllSectors.divide(BigDecimal.valueOf(100_000_000), 0, RoundingMode.HALF_UP),
                uniqueStockTradingValue.size(), elapsed, dataSource);

        return results;
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
     * 캐시 초기화 (모든 섹터 거래대금 캐시)
     */
    @CacheEvict(value = "sectorTrading", allEntries = true)
    public void clearCache() {
        log.info("섹터 거래대금 캐시 초기화");
    }
}
