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

    // 기간별 캐시 (1분간 유효 - 실시간성 강화)
    private final Map<TradingPeriod, List<SectorTradingDto>> cachedSectorData = new ConcurrentHashMap<>();
    private final Map<TradingPeriod, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = 60 * 1000; // 1분 (주식 시장 실시간성 반영)

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

        // ========== [개선 1] 중복 종목 제거를 위한 Unique Stock 추적 ==========
        // 삼성전자가 '반도체'와 '통신' 두 섹터에 포함되면 전체 합계에서 두 번 더해지는 Double Counting 방지
        // 모든 종목을 추적 (상위 5개만이 아닌 전체 종목)
        Map<String, BigDecimal> uniqueStockTradingValue = new HashMap<>();

        // 각 섹터별 데이터 수집 (uniqueStockMap 전달하여 모든 종목 추적)
        for (SectorInfo sector : sectorConfig.getAllSectors()) {
            SectorTradingDto dto = getSectorTradingData(sector, period, uniqueStockTradingValue);
            if (dto != null) {
                results.add(dto);
            }
        }

        // 전체 시장 거래대금 = Unique 종목들의 거래대금 합 (중복 제거됨)
        BigDecimal totalAllSectors = uniqueStockTradingValue.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 비율 계산 (각 섹터의 비율은 섹터 자체 합계 기준 유지)
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

        log.debug("섹터 거래대금 조회 완료 - 전체: {}억, Unique 종목 수: {}",
                totalAllSectors.divide(BigDecimal.valueOf(100_000_000), 0, RoundingMode.HALF_UP),
                uniqueStockTradingValue.size());

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
        return getSectorTradingData(sector, period, null);
    }

    /**
     * 섹터의 거래대금 데이터 조회 (Unique Stock 추적 지원)
     *
     * @param sector 섹터 정보
     * @param period 조회 기간
     * @param uniqueStockMap 중복 제거용 Map (null이면 추적 안 함)
     */
    private SectorTradingDto getSectorTradingData(SectorInfo sector, TradingPeriod period,
                                                   Map<String, BigDecimal> uniqueStockMap) {
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

                    // [개선 1] Unique Stock 추적 - 전체 시장 합계용
                    if (uniqueStockMap != null && info.getStockCode() != null) {
                        uniqueStockMap.merge(
                                info.getStockCode(),
                                info.getTradingValue(),
                                BigDecimal::max  // 동일 종목이 여러 섹터에 있으면 큰 값 유지
                        );
                    }
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
     *
     * [개선 3] 390분 추정 로직 제거 - 장 초반/점심시간 왜곡 방지
     * [개선 4] 거래대금 계산 우선순위:
     *   1순위: API 제공 누적 거래대금 (accumulatedTradingValue)
     *   2순위: 현재가 * 거래량 계산 (장 막판 왜곡 가능성 있음)
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
                // ========== [개선 4] 오늘 누적 거래대금 ==========
                // 1순위: API에서 직접 제공하는 누적 거래대금 사용
                if (price.getAccumulatedTradingValue() != null
                        && price.getAccumulatedTradingValue().compareTo(BigDecimal.ZERO) > 0) {
                    tradingValue = price.getAccumulatedTradingValue();
                }
                // 2순위: 현재가 * 누적거래량 (장 막판 가격 변동 시 왜곡 가능)
                else if (price.getCurrentPrice() != null && price.getVolume() != null) {
                    tradingValue = price.getCurrentPrice().multiply(price.getVolume());
                    log.trace("종목 [{}] 거래대금 계산식 사용 (accumulatedTradingValue 미제공)", stockCode);
                }
            } else {
                // ========== [개선 3] 5분/30분 파워: 분봉 기반 거래대금만 사용 ==========
                // 390분 추정 로직 완전 제거 - 장 초반/점심시간 왜곡이 심함
                BigDecimal minuteTradingValue = stockPriceService.getTradingValueForMinutes(stockCode, period.getMinutes());
                if (minuteTradingValue != null && minuteTradingValue.compareTo(BigDecimal.ZERO) > 0) {
                    tradingValue = minuteTradingValue;
                } else {
                    // 분봉 데이터 미지원 시 추정하지 않고 0 반환 (정확한 데이터만 사용)
                    tradingValue = BigDecimal.ZERO;
                    log.debug("종목 [{}] {}분 분봉 데이터 미지원 - 거래대금 0 처리", stockCode, period.getMinutes());
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
