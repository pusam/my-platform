package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.ChartCompareDto;
import com.myplatform.backend.dto.ChartCompareDto.ChartPoint;
import com.myplatform.backend.dto.StockPriceDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 지수 vs 종목 오버레이 차트 서비스
 * - 코스닥/코스피 지수와 종목의 시초가 대비 등락률 비교
 * - 상대강도 분석
 */
@Service
public class ChartCompareService {

    private static final Logger log = LoggerFactory.getLogger(ChartCompareService.class);

    // 지수 코드
    public static final String KOSPI = "0001";
    public static final String KOSDAQ = "1001";

    private final KoreaInvestmentService kisService;
    private final StockPriceService stockPriceService;
    private final RealTimeDataCache dataCache;

    // 분봉 캐시 (5분간 유효 - API 호출 빈도 제한)
    private final Map<String, CachedMinuteData> minuteCache = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = 5 * 60 * 1000;

    private static class CachedMinuteData {
        List<MinuteData> data;
        BigDecimal openPrice;
        long timestamp;
    }

    private static class MinuteData {
        String time;
        BigDecimal price;
        BigDecimal rate;  // 시초가 대비 등락률
    }

    public ChartCompareService(KoreaInvestmentService kisService,
                               StockPriceService stockPriceService,
                               RealTimeDataCache dataCache) {
        this.kisService = kisService;
        this.stockPriceService = stockPriceService;
        this.dataCache = dataCache;
    }

    /**
     * 지수 vs 종목 비교 차트 데이터 조회
     * @param stockCode 종목코드
     * @param indexCode 지수코드 (0001: 코스피, 1001: 코스닥)
     */
    public ChartCompareDto getCompareChart(String stockCode, String indexCode) {
        ChartCompareDto dto = new ChartCompareDto();
        dto.setStockCode(stockCode);
        dto.setIndexCode(indexCode);
        dto.setIndexName(KOSPI.equals(indexCode) ? "코스피" : "코스닥");
        dto.setFetchedAt(LocalDateTime.now());

        // 1. 종목 현재가 정보
        StockPriceDto stockPrice = stockPriceService.getStockPrice(stockCode);
        if (stockPrice != null) {
            dto.setStockName(stockPrice.getStockName());
            dto.setStockPrice(stockPrice.getCurrentPrice());
            dto.setStockChangeRate(stockPrice.getChangeRate());
        }

        // 2. 지수 분봉 데이터
        List<MinuteData> indexMinutes = getIndexMinuteData(indexCode);

        // 3. 종목 분봉 데이터
        List<MinuteData> stockMinutes = getStockMinuteData(stockCode);

        // 4. 오버레이 차트 데이터 생성
        List<ChartPoint> chartData = mergeChartData(indexMinutes, stockMinutes);
        dto.setChartData(chartData);

        // 5. 지수 현재 등락률
        if (!indexMinutes.isEmpty()) {
            MinuteData lastIndex = indexMinutes.get(indexMinutes.size() - 1);
            dto.setIndexPrice(lastIndex.price);
            dto.setIndexChangeRate(lastIndex.rate);
        }

        // 6. 상대강도 분석
        analyzeRelativeStrength(dto);

        return dto;
    }

    /**
     * 지수 분봉 데이터 조회
     */
    private List<MinuteData> getIndexMinuteData(String indexCode) {
        // 캐시 확인
        CachedMinuteData cached = minuteCache.get("IDX_" + indexCode);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_DURATION_MS) {
            return cached.data;
        }

        List<MinuteData> result = new ArrayList<>();

        if (kisService.isConfigured()) {
            try {
                JsonNode response = kisService.getIndexMinuteChart(indexCode);
                if (response != null && "0".equals(getTextValue(response, "rt_cd"))) {
                    JsonNode output = response.get("output2");
                    if (output != null && output.isArray()) {
                        BigDecimal openPrice = null;

                        // 역순으로 처리 (오래된 것부터)
                        List<JsonNode> items = new ArrayList<>();
                        output.forEach(items::add);
                        Collections.reverse(items);

                        for (JsonNode item : items) {
                            MinuteData md = new MinuteData();
                            md.time = getTextValue(item, "stck_cntg_hour");
                            if (md.time != null && md.time.length() >= 4) {
                                md.time = md.time.substring(0, 2) + ":" + md.time.substring(2, 4);
                            }
                            md.price = getBigDecimalValue(item, "bstp_nmix_prpr");

                            // 시초가 설정
                            if (openPrice == null && md.price != null) {
                                openPrice = md.price;
                            }

                            // 시초가 대비 등락률 계산
                            if (openPrice != null && openPrice.compareTo(BigDecimal.ZERO) > 0) {
                                md.rate = md.price.subtract(openPrice)
                                        .multiply(BigDecimal.valueOf(100))
                                        .divide(openPrice, 2, RoundingMode.HALF_UP);
                            } else {
                                md.rate = BigDecimal.ZERO;
                            }

                            result.add(md);
                        }

                        // 캐시 저장
                        CachedMinuteData cache = new CachedMinuteData();
                        cache.data = result;
                        cache.openPrice = openPrice;
                        cache.timestamp = System.currentTimeMillis();
                        minuteCache.put("IDX_" + indexCode, cache);
                    }
                }
            } catch (Exception e) {
                log.error("지수 분봉 파싱 실패: {}", e.getMessage());
            }
        }

        // API 미설정 또는 실패 시 빈 리스트 반환
        return result;
    }

    /**
     * 종목 분봉 데이터 조회
     */
    private List<MinuteData> getStockMinuteData(String stockCode) {
        // 캐시 확인
        CachedMinuteData cached = minuteCache.get("STK_" + stockCode);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_DURATION_MS) {
            return cached.data;
        }

        List<MinuteData> result = new ArrayList<>();

        if (kisService.isConfigured()) {
            try {
                JsonNode response = kisService.getStockMinuteChart(stockCode);
                if (response != null && "0".equals(getTextValue(response, "rt_cd"))) {
                    JsonNode output = response.get("output2");
                    if (output != null && output.isArray()) {
                        BigDecimal openPrice = null;

                        // 역순으로 처리
                        List<JsonNode> items = new ArrayList<>();
                        output.forEach(items::add);
                        Collections.reverse(items);

                        for (JsonNode item : items) {
                            MinuteData md = new MinuteData();
                            md.time = getTextValue(item, "stck_cntg_hour");
                            if (md.time != null && md.time.length() >= 4) {
                                md.time = md.time.substring(0, 2) + ":" + md.time.substring(2, 4);
                            }
                            md.price = getBigDecimalValue(item, "stck_prpr");

                            if (openPrice == null && md.price != null) {
                                openPrice = md.price;
                            }

                            if (openPrice != null && openPrice.compareTo(BigDecimal.ZERO) > 0) {
                                md.rate = md.price.subtract(openPrice)
                                        .multiply(BigDecimal.valueOf(100))
                                        .divide(openPrice, 2, RoundingMode.HALF_UP);
                            } else {
                                md.rate = BigDecimal.ZERO;
                            }

                            result.add(md);
                        }

                        // 캐시 저장
                        CachedMinuteData cache = new CachedMinuteData();
                        cache.data = result;
                        cache.openPrice = openPrice;
                        cache.timestamp = System.currentTimeMillis();
                        minuteCache.put("STK_" + stockCode, cache);
                    }
                }
            } catch (Exception e) {
                log.error("종목 분봉 파싱 실패: {}", e.getMessage());
            }
        }

        // API 미설정 또는 실패 시 빈 리스트 반환
        return result;
    }

    /**
     * 지수와 종목 데이터 병합
     */
    private List<ChartPoint> mergeChartData(List<MinuteData> indexData, List<MinuteData> stockData) {
        Map<String, ChartPoint> merged = new LinkedHashMap<>();

        // 지수 데이터 먼저
        for (MinuteData idx : indexData) {
            ChartPoint point = new ChartPoint();
            point.setTime(idx.time);
            point.setIndexRate(idx.rate);
            merged.put(idx.time, point);
        }

        // 종목 데이터 병합
        for (MinuteData stk : stockData) {
            ChartPoint point = merged.get(stk.time);
            if (point != null) {
                point.setStockRate(stk.rate);
                point.setGap(stk.rate.subtract(point.getIndexRate() != null ? point.getIndexRate() : BigDecimal.ZERO));
            } else {
                point = new ChartPoint();
                point.setTime(stk.time);
                point.setStockRate(stk.rate);
                point.setIndexRate(BigDecimal.ZERO);
                point.setGap(stk.rate);
                merged.put(stk.time, point);
            }
        }

        return new ArrayList<>(merged.values());
    }

    /**
     * 상대강도 분석
     */
    private void analyzeRelativeStrength(ChartCompareDto dto) {
        BigDecimal indexRate = dto.getIndexChangeRate() != null ? dto.getIndexChangeRate() : BigDecimal.ZERO;
        BigDecimal stockRate = dto.getStockChangeRate() != null ? dto.getStockChangeRate() : BigDecimal.ZERO;

        BigDecimal relativeStrength = stockRate.subtract(indexRate);
        dto.setRelativeStrength(relativeStrength);

        // 분석 로직
        // 지수가 하락하는데 종목이 버틴다 = STRONG (개쎈 놈)
        // 지수가 오르는데 종목이 못 따라간다 = WEAK (버려야 함)

        if (indexRate.compareTo(BigDecimal.valueOf(-0.5)) < 0) {
            // 지수 하락 중
            if (relativeStrength.compareTo(BigDecimal.valueOf(0.5)) > 0) {
                dto.setAnalysis("STRONG");
                dto.setAnalysisReason("🔥 지수는 " + indexRate.setScale(1, RoundingMode.HALF_UP) + "% 빠지는데 이 종목은 버티고 있음! 지수 반등 시 급등 가능성");
            } else if (relativeStrength.compareTo(BigDecimal.valueOf(-0.5)) < 0) {
                dto.setAnalysis("WEAK");
                dto.setAnalysisReason("📉 지수보다 더 빠지고 있음. 매도 고려");
            } else {
                dto.setAnalysis("NEUTRAL");
                dto.setAnalysisReason("지수와 비슷한 흐름");
            }
        } else if (indexRate.compareTo(BigDecimal.valueOf(0.5)) > 0) {
            // 지수 상승 중
            if (relativeStrength.compareTo(BigDecimal.valueOf(0.5)) > 0) {
                dto.setAnalysis("STRONG");
                dto.setAnalysisReason("🚀 지수보다 강하게 상승 중! 시장 주도주");
            } else if (relativeStrength.compareTo(BigDecimal.valueOf(-0.5)) < 0) {
                dto.setAnalysis("WEAK");
                dto.setAnalysisReason("⚠️ 지수는 오르는데 이 종목만 기어가고 있음. 버려야 할 종목!");
            } else {
                dto.setAnalysis("NEUTRAL");
                dto.setAnalysisReason("지수와 비슷한 흐름");
            }
        } else {
            // 지수 횡보
            if (relativeStrength.compareTo(BigDecimal.valueOf(1)) > 0) {
                dto.setAnalysis("STRONG");
                dto.setAnalysisReason("💪 지수 횡보 중에도 강세! 독자 상승 종목");
            } else if (relativeStrength.compareTo(BigDecimal.valueOf(-1)) < 0) {
                dto.setAnalysis("WEAK");
                dto.setAnalysisReason("😰 지수 횡보인데 혼자 빠지고 있음");
            } else {
                dto.setAnalysis("NEUTRAL");
                dto.setAnalysisReason("지수와 유사한 흐름");
            }
        }
    }

    private String getTextValue(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    private BigDecimal getBigDecimalValue(JsonNode node, String field) {
        if (!node.has(field)) return BigDecimal.ZERO;
        String value = node.get(field).asText().replace(",", "");
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
