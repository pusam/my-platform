package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.config.SectorStockConfig.SectorInfo;
import com.myplatform.backend.dto.SectorTradingDto;
import com.myplatform.backend.dto.SectorTradingDto.StockTradingInfo;
import com.myplatform.backend.dto.StockPriceDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 섹터별 거래대금 조회 서비스
 */
@Service
public class SectorTradingService {

    private static final Logger log = LoggerFactory.getLogger(SectorTradingService.class);

    private final SectorStockConfig sectorConfig;
    private final StockPriceService stockPriceService;
    private final ExecutorService executorService;

    // 캐시 (5분간 유효)
    private List<SectorTradingDto> cachedSectorData;
    private long cacheTimestamp = 0;
    private static final long CACHE_DURATION_MS = 5 * 60 * 1000; // 5분

    public SectorTradingService(SectorStockConfig sectorConfig, StockPriceService stockPriceService) {
        this.sectorConfig = sectorConfig;
        this.stockPriceService = stockPriceService;
        this.executorService = Executors.newFixedThreadPool(10);
    }

    /**
     * 모든 섹터의 거래대금 조회
     */
    public List<SectorTradingDto> getAllSectorTrading() {
        // 캐시 확인
        if (cachedSectorData != null && System.currentTimeMillis() - cacheTimestamp < CACHE_DURATION_MS) {
            return cachedSectorData;
        }

        List<SectorTradingDto> results = new ArrayList<>();
        BigDecimal totalAllSectors = BigDecimal.ZERO;

        // 각 섹터별 데이터 수집
        for (SectorInfo sector : sectorConfig.getAllSectors()) {
            SectorTradingDto dto = getSectorTradingData(sector);
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
        cachedSectorData = results;
        cacheTimestamp = System.currentTimeMillis();

        return results;
    }

    /**
     * 특정 섹터의 거래대금 상세 조회
     */
    public SectorTradingDto getSectorDetail(String sectorCode) {
        SectorInfo sector = sectorConfig.getSector(sectorCode);
        if (sector == null) {
            return null;
        }
        return getSectorTradingData(sector);
    }

    /**
     * 섹터의 거래대금 데이터 조회
     */
    private SectorTradingDto getSectorTradingData(SectorInfo sector) {
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
            futures.add(executorService.submit(() -> fetchStockTradingInfo(stockCode)));
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
    private StockTradingInfo fetchStockTradingInfo(String stockCode) {
        try {
            StockPriceDto price = stockPriceService.getStockPrice(stockCode);
            if (price == null) {
                return null;
            }

            StockTradingInfo info = new StockTradingInfo();
            info.setStockCode(stockCode);
            info.setStockName(price.getStockName());
            info.setCurrentPrice(price.getCurrentPrice());
            info.setChangeRate(price.getChangeRate());

            // 거래대금 = 현재가 * 거래량
            BigDecimal tradingValue = BigDecimal.ZERO;
            if (price.getCurrentPrice() != null && price.getVolume() != null) {
                tradingValue = price.getCurrentPrice().multiply(price.getVolume());
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
        cachedSectorData = null;
        cacheTimestamp = 0;
    }
}
