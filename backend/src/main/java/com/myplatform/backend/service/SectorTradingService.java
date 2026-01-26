package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.config.SectorStockConfig.SectorInfo;
import com.myplatform.backend.dto.SectorTradingDto;
import com.myplatform.backend.dto.SectorTradingDto.StockTradingInfo;
import com.myplatform.backend.dto.StockPriceDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

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
 */
@Service
public class SectorTradingService {

    private static final Logger log = LoggerFactory.getLogger(SectorTradingService.class);

    private final SectorStockConfig sectorConfig;
    private final StockPriceService stockPriceService;
    private final ExecutorService executorService;

    // 기간별 캐시 (5분간 유효)
    private final Map<TradingPeriod, List<SectorTradingDto>> cachedSectorData = new ConcurrentHashMap<>();
    private final Map<TradingPeriod, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = 5 * 60 * 1000; // 5분

    public SectorTradingService(SectorStockConfig sectorConfig, StockPriceService stockPriceService) {
        this.sectorConfig = sectorConfig;
        this.stockPriceService = stockPriceService;
        this.executorService = Executors.newFixedThreadPool(10);
    }

    /**
     * 모든 섹터의 거래대금 조회 (기본: 오늘 누적)
     */
    public List<SectorTradingDto> getAllSectorTrading() {
        return getAllSectorTrading(TradingPeriod.TODAY);
    }

    /**
     * 모든 섹터의 거래대금 조회 (기간별)
     * @param period 조회 기간 (TODAY, MIN_5, MIN_30)
     */
    public List<SectorTradingDto> getAllSectorTrading(TradingPeriod period) {
        // 캐시 확인
        Long cacheTime = cacheTimestamps.get(period);
        if (cacheTime != null && System.currentTimeMillis() - cacheTime < CACHE_DURATION_MS) {
            List<SectorTradingDto> cached = cachedSectorData.get(period);
            if (cached != null) {
                return cached;
            }
        }

        List<SectorTradingDto> results = new ArrayList<>();
        BigDecimal totalAllSectors = BigDecimal.ZERO;

        // 각 섹터별 데이터 수집
        for (SectorInfo sector : sectorConfig.getAllSectors()) {
            SectorTradingDto dto = getSectorTradingData(sector, period);
            if (dto != null) {
                results.add(dto);
                totalAllSectors = totalAllSectors.add(dto.getTotalTradingValue());
            }
        }

        // 비율 계산
        if (totalAllSectors.compareTo(BigDecimal.ZERO) > 0) {
            for (SectorTradingDto dto : results) {
                BigDecimal percentage = dto.getTotalTradingValue()
                        .multiply(BigDecimal.valueOf(100))
                        .divide(totalAllSectors, 2, RoundingMode.HALF_UP);
                dto.setPercentage(percentage);
            }
        }

        // 거래대금 순으로 정렬
        results.sort((a, b) -> b.getTotalTradingValue().compareTo(a.getTotalTradingValue()));

        // 캐시 저장
        cachedSectorData.put(period, results);
        cacheTimestamps.put(period, System.currentTimeMillis());

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
        return getSectorTradingData(sector, period);
    }

    /**
     * 섹터의 거래대금 데이터 조회
     */
    private SectorTradingDto getSectorTradingData(SectorInfo sector, TradingPeriod period) {
        SectorTradingDto dto = new SectorTradingDto();
        dto.setSectorCode(sector.getCode());
        dto.setSectorName(sector.getName());
        dto.setColor(sector.getColor());
        dto.setStockCount(sector.getStockCodes().size());

        List<StockTradingInfo> stockInfos = new ArrayList<>();
        BigDecimal totalTradingValue = BigDecimal.ZERO;

        // 종목별 시세 조회 (병렬 처리)
        List<Future<StockTradingInfo>> futures = new ArrayList<>();
        for (String stockCode : sector.getStockCodes()) {
            futures.add(executorService.submit(() -> fetchStockTradingInfo(stockCode, period)));
        }

        // 결과 수집
        for (Future<StockTradingInfo> future : futures) {
            try {
                StockTradingInfo info = future.get(10, TimeUnit.SECONDS);
                if (info != null && info.getTradingValue() != null) {
                    stockInfos.add(info);
                    totalTradingValue = totalTradingValue.add(info.getTradingValue());
                }
            } catch (Exception e) {
                log.warn("종목 정보 조회 실패: {}", e.getMessage());
            }
        }

        // 거래대금 순 정렬 후 상위 5개만
        stockInfos.sort((a, b) -> b.getTradingValue().compareTo(a.getTradingValue()));
        dto.setTopStocks(stockInfos.stream().limit(5).collect(Collectors.toList()));
        dto.setTotalTradingValue(totalTradingValue);

        return dto;
    }

    /**
     * 개별 종목의 거래 정보 조회
     */
    private StockTradingInfo fetchStockTradingInfo(String stockCode, TradingPeriod period) {
        try {
            StockPriceDto price = stockPriceService.getStockPrice(stockCode);
            if (price == null) {
                return null;
            }

            StockTradingInfo info = new StockTradingInfo();
            info.setStockCode(stockCode);
            // API에서 종목명을 못 가져오면 SectorStockConfig의 매핑 사용
            String stockName = price.getStockName();
            if (stockName == null || stockName.isEmpty()) {
                stockName = sectorConfig.getStockName(stockCode);
            }
            info.setStockName(stockName);
            info.setCurrentPrice(price.getCurrentPrice());
            info.setChangeRate(price.getChangeRate());

            BigDecimal tradingValue = BigDecimal.ZERO;

            if (period == TradingPeriod.TODAY) {
                // 오늘 누적: 현재가 * 누적거래량
                if (price.getCurrentPrice() != null && price.getVolume() != null) {
                    tradingValue = price.getCurrentPrice().multiply(price.getVolume());
                }
            } else {
                // 5분/30분 파워: 분봉 기반 거래대금
                BigDecimal minuteTradingValue = stockPriceService.getTradingValueForMinutes(stockCode, period.getMinutes());
                if (minuteTradingValue != null) {
                    tradingValue = minuteTradingValue;
                } else {
                    // API 미지원 시 누적 거래대금의 비율로 추정
                    if (price.getCurrentPrice() != null && price.getVolume() != null) {
                        BigDecimal totalValue = price.getCurrentPrice().multiply(price.getVolume());
                        // 장 운영시간 약 390분 기준 비율 추정
                        tradingValue = totalValue.multiply(BigDecimal.valueOf(period.getMinutes()))
                                .divide(BigDecimal.valueOf(390), 0, RoundingMode.HALF_UP);
                    }
                }
            }

            info.setTradingValue(tradingValue);
            return info;
        } catch (Exception e) {
            log.error("종목 거래정보 조회 실패 [{}]: {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * 캐시 초기화
     */
    public void clearCache() {
        cachedSectorData.clear();
        cacheTimestamps.clear();
    }

    /**
     * 서비스 종료 시 ExecutorService 정리
     */
    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("SectorTradingService ExecutorService 종료");
    }
}
