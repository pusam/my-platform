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
    private final ThreadPoolTaskExecutor sectorTradingExecutor;

    // ========== 스냅샷 저장소 ==========
    // Key: 종목코드, Value: 시간순 정렬된 누적 거래대금 (TreeMap)
    private final ConcurrentMap<String, TreeMap<LocalDateTime, BigDecimal>> tradingHistoryStore = new ConcurrentHashMap<>();

    // 최근 시세 캐시 (TODAY용)
    private volatile Map<String, StockPriceDto> latestPriceCache = new ConcurrentHashMap<>();
    private volatile LocalDateTime lastSnapshotTime = null;

    // 섹터별 계산 결과 캐시
    private final ConcurrentMap<TradingPeriod, List<SectorTradingDto>> cachedResultByPeriod = new ConcurrentHashMap<>();
    private final ConcurrentMap<TradingPeriod, LocalDateTime> lastCalculateTime = new ConcurrentHashMap<>();

    // 설정
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 40);  // 운영: 15:40
    private static final int HISTORY_RETENTION_MINUTES = 40;  // 최근 40분 데이터만 유지

    // 한국 공휴일 (고정)
    private static final Set<MonthDay> KOREA_FIXED_HOLIDAYS = Set.of(
            MonthDay.of(1, 1),   // 신정
            MonthDay.of(3, 1),   // 삼일절
            MonthDay.of(5, 5),   // 어린이날
            MonthDay.of(6, 6),   // 현충일
            MonthDay.of(8, 15),  // 광복절
            MonthDay.of(10, 3),  // 개천절
            MonthDay.of(10, 9),  // 한글날
            MonthDay.of(12, 25)  // 크리스마스
    );

    // 휴장일 여부 확인
    private boolean isMarketClosed() {
        LocalDate today = LocalDate.now();
        DayOfWeek dayOfWeek = today.getDayOfWeek();

        // 주말 체크
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return true;
        }

        // 고정 공휴일 체크
        MonthDay todayMonthDay = MonthDay.from(today);
        return KOREA_FIXED_HOLIDAYS.contains(todayMonthDay);
    }

    // ========== 초기화 ==========

    @PostConstruct
    public void initializeCache() {
        log.info("[섹터거래대금] ========== 스냅샷 기반 서비스 시작 ==========");

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(3000);
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
     * 매분 스냅샷 수집 (09:00~15:40, 평일)
     * - stockPriceService.getStockPrices()로 전체 종목 시세 조회
     * - accumulatedTradingValue를 시간별로 저장
     */
    @Scheduled(cron = "0 */1 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledSnapshotCollection() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                collectSnapshot();
            } catch (Exception e) {
                log.error("[섹터거래대금] 스냅샷 수집 실패: {}", e.getMessage());
            }
        }, sectorTradingExecutor);
    }

    /**
     * 스냅샷 수집 실행
     */
    private void collectSnapshot() {
        long startTime = System.currentTimeMillis();
        LocalDateTime snapshotTime = LocalDateTime.now();
        boolean isClosedDay = isMarketClosed();

        if (isClosedDay) {
            log.info("[섹터거래대금] 휴장일 - 마지막 거래일 데이터 유지");
        }

        // 1. 모든 종목 코드 수집
        Set<String> allStockCodes = new HashSet<>();
        for (SectorInfo sector : sectorConfig.getAllSectors()) {
            allStockCodes.addAll(sector.getStockCodes());
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

            BigDecimal accumulatedValue = price.getAccumulatedTradingValue();
            if (accumulatedValue == null || accumulatedValue.compareTo(BigDecimal.ZERO) <= 0) {
                // 누적 거래대금이 없으면 현재가 * 거래량으로 계산
                if (price.getCurrentPrice() != null && price.getVolume() != null) {
                    accumulatedValue = price.getCurrentPrice().multiply(price.getVolume());
                }
            }

            // 휴장일에는 거래대금이 0이어도 현재가 기준으로 임시값 저장 (UI 표시용)
            if (isClosedDay && (accumulatedValue == null || accumulatedValue.compareTo(BigDecimal.ZERO) <= 0)) {
                if (price.getCurrentPrice() != null) {
                    // 휴장일: 시가총액의 0.1%를 임시 거래대금으로 사용 (정렬/표시용)
                    BigDecimal marketCap = price.getMarketCap();
                    if (marketCap != null && marketCap.compareTo(BigDecimal.ZERO) > 0) {
                        accumulatedValue = marketCap.multiply(new BigDecimal("0.001"));
                    } else {
                        // 시가총액도 없으면 현재가 * 10000 (임시값)
                        accumulatedValue = price.getCurrentPrice().multiply(new BigDecimal("10000"));
                    }
                }
            }

            if (accumulatedValue != null && accumulatedValue.compareTo(BigDecimal.ZERO) > 0) {
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
     * 모든 기간의 캐시 갱신
     */
    private void refreshAllPeriodCache() {
        for (TradingPeriod period : TradingPeriod.values()) {
            try {
                List<SectorTradingDto> result = calculateSectorTrading(period);
                cachedResultByPeriod.put(period, result);
                lastCalculateTime.put(period, LocalDateTime.now());
            } catch (Exception e) {
                log.error("[섹터거래대금] {} 캐시 갱신 실패: {}", period, e.getMessage());
            }
        }
    }

    // ========== API 메서드 (캐시에서 즉시 반환) ==========

    public List<SectorTradingDto> getAllSectorTrading() {
        return getAllSectorTrading(TradingPeriod.TODAY);
    }

    public List<SectorTradingDto> getAllSectorTrading(TradingPeriod period) {
        List<SectorTradingDto> cached = cachedResultByPeriod.get(period);
        if (cached != null && !cached.isEmpty()) {
            log.debug("[섹터거래대금] {} 캐시 HIT - {} 섹터", period, cached.size());
            return cached;
        }

        // 캐시 없으면 즉시 계산
        log.info("[섹터거래대금] {} 캐시 MISS - 즉시 계산", period);
        List<SectorTradingDto> result = calculateSectorTrading(period);
        cachedResultByPeriod.put(period, result);
        lastCalculateTime.put(period, LocalDateTime.now());
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
            log.warn("[섹터거래대금] 시세 캐시 없음 - 즉시 수집 시도");
            try {
                collectSnapshot();
            } catch (Exception e) {
                log.error("[섹터거래대금] 즉시 수집 실패: {}", e.getMessage());
            }
            if (latestPriceCache.isEmpty()) {
                log.warn("[섹터거래대금] 즉시 수집 후에도 캐시 없음 - 빈 결과 반환");
                return Collections.emptyList();
            }
        }

        List<SectorTradingDto> results = new ArrayList<>();
        ConcurrentMap<String, BigDecimal> uniqueStockTradingValue = new ConcurrentHashMap<>();

        for (SectorInfo sector : sectorConfig.getAllSectors()) {
            SectorTradingDto dto = buildSectorDto(sector, period, uniqueStockTradingValue);
            if (dto != null) {
                results.add(dto);
            }
        }

        // 전체 거래대금 계산 (중복 제거)
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

        // 섹터 평균 등락률 계산 (거래대금 가중 평균)
        BigDecimal weightedChangeSum = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        for (StockTradingInfo info : stockInfos) {
            if (info.getChangeRate() != null && info.getTradingValue() != null) {
                weightedChangeSum = weightedChangeSum.add(
                        info.getChangeRate().multiply(info.getTradingValue()));
                weightSum = weightSum.add(info.getTradingValue());
            }
        }
        if (weightSum.compareTo(BigDecimal.ZERO) > 0) {
            dto.setChangeRate(weightedChangeSum.divide(weightSum, 2, java.math.RoundingMode.HALF_UP));
        } else {
            dto.setChangeRate(BigDecimal.ZERO);
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
        info.setChangeRate(price.getChangeRate());

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
}
