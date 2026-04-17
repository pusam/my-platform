package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.StockDetailDto.*;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Supplier;

/**
 * StockDetailService의 캐시 메서드를 별도 빈으로 분리
 * → self-invocation 문제 해결 (@Cacheable 프록시 정상 동작)
 */
@Service
public class StockDetailCacheService {

    private final KoreaInvestmentService kisService;
    private final VwapService vwapService;

    public StockDetailCacheService(KoreaInvestmentService kisService, VwapService vwapService) {
        this.kisService = kisService;
        this.vwapService = vwapService;
    }

    @Cacheable(value = "stockDetailChart", key = "#stockCode")
    public ChartData getCachedChartData(String stockCode) {
        return fetchChartData(stockCode);
    }

    @Cacheable(value = "stockDetailFinancial", key = "#stockCode")
    public FinancialInfo getCachedFinancialInfo(String stockCode) {
        return fetchFinancialInfo(stockCode);
    }

    /**
     * Supplier 기반 래퍼 — 호출측(StockDetailService) 로직을 유지하면서
     * 캐시 프록시만 빌려 쓰기 위해 사용. key=#stockCode로만 캐시됨.
     */
    @Cacheable(value = "stockDetailRisk", key = "#stockCode")
    public RiskInfo getCachedRiskInfo(String stockCode, Supplier<RiskInfo> supplier) {
        return supplier.get();
    }

    @Cacheable(value = "stockDetailPeer", key = "#stockCode")
    public Map<String, Object> getCachedPeerData(String stockCode, Supplier<Map<String, Object>> supplier) {
        return supplier.get();
    }

    @Cacheable(value = "stockDetailAi", key = "#stockCode")
    public AiAnalysis getCachedAiAnalysis(String stockCode, Supplier<AiAnalysis> supplier) {
        return supplier.get();
    }

    /**
     * 차트 데이터 조회 (KIS 일봉 + MA + BB + VWAP)
     */
    private ChartData fetchChartData(String stockCode) {
        try {
            JsonNode dailyData = kisService.getDailyPrices(stockCode, 150);
            if (dailyData == null) return null;

            JsonNode output2 = dailyData.get("output2");
            if (output2 == null || !output2.isArray()) return null;

            java.util.List<CandlePoint> allCandles = new java.util.ArrayList<>();
            java.util.List<VolumePoint> allVolumes = new java.util.ArrayList<>();
            java.util.List<java.math.BigDecimal> allCloses = new java.util.ArrayList<>();

            for (JsonNode item : output2) {
                String date = item.has("stck_bsop_date") ? item.get("stck_bsop_date").asText() : "";
                java.math.BigDecimal close = parseBigDecimal(item.get("stck_clpr"));

                allCandles.add(CandlePoint.builder()
                        .date(date)
                        .open(parseBigDecimal(item.get("stck_oprc")))
                        .high(parseBigDecimal(item.get("stck_hgpr")))
                        .low(parseBigDecimal(item.get("stck_lwpr")))
                        .close(close)
                        .build());

                allVolumes.add(VolumePoint.builder()
                        .date(date)
                        .volume(parseLong(item.get("acml_vol")))
                        .build());

                allCloses.add(close);
            }

            int displayCount = Math.min(60, allCandles.size());
            java.util.List<CandlePoint> candles = allCandles.subList(0, displayCount);
            java.util.List<VolumePoint> volumes = allVolumes.subList(0, displayCount);

            java.math.BigDecimal ma5 = calculateMA(allCloses, 5);
            java.math.BigDecimal ma20 = calculateMA(allCloses, 20);
            java.math.BigDecimal ma60 = calculateMA(allCloses, 60);

            java.util.List<java.math.BigDecimal> maLine5 = calculateMALine(allCloses, 5, displayCount);
            java.util.List<java.math.BigDecimal> maLine20 = calculateMALine(allCloses, 20, displayCount);
            java.util.List<java.math.BigDecimal> maLine60 = calculateMALine(allCloses, 60, displayCount);
            java.util.List<java.math.BigDecimal> maLine120 = calculateMALine(allCloses, 120, displayCount);

            // 볼린저밴드
            java.util.List<java.math.BigDecimal> bbUpper = new java.util.ArrayList<>();
            java.util.List<java.math.BigDecimal> bbLower = new java.util.ArrayList<>();
            for (int i = 0; i < displayCount; i++) {
                if (i + 20 <= allCloses.size()) {
                    java.util.List<java.math.BigDecimal> window = allCloses.subList(i, i + 20);
                    java.math.BigDecimal mean = window.stream().reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                            .divide(java.math.BigDecimal.valueOf(20), 2, java.math.RoundingMode.HALF_UP);
                    double variance = window.stream()
                            .mapToDouble(p -> Math.pow(p.subtract(mean).doubleValue(), 2))
                            .average().orElse(0);
                    java.math.BigDecimal stdDev = java.math.BigDecimal.valueOf(Math.sqrt(variance)).setScale(2, java.math.RoundingMode.HALF_UP);
                    bbUpper.add(mean.add(stdDev.multiply(java.math.BigDecimal.valueOf(2))));
                    bbLower.add(mean.subtract(stdDev.multiply(java.math.BigDecimal.valueOf(2))));
                } else {
                    bbUpper.add(null);
                    bbLower.add(null);
                }
            }

            // VWAP
            java.math.BigDecimal vwap = null;
            try {
                var vwapResult = vwapService.calculateVwap(stockCode);
                if (vwapResult != null) vwap = vwapResult.getVwap();
            } catch (Exception e) { /* skip */ }

            return ChartData.builder()
                    .candles(candles).volumes(volumes)
                    .ma5(ma5).ma20(ma20).ma60(ma60).vwap(vwap)
                    .maLine5(maLine5).maLine20(maLine20).maLine60(maLine60).maLine120(maLine120)
                    .bbUpper(bbUpper).bbLower(bbLower)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 재무 정보 조회 (KIS PER/PBR/EPS + 시가총액)
     * Quick용 경량 버전 — FnGuide/네이버 크롤링 제외
     */
    private FinancialInfo fetchFinancialInfo(String stockCode) {
        try {
            JsonNode priceData = kisService.getStockPrice(stockCode);
            if (priceData == null || !"0".equals(priceData.path("rt_cd").asText())) return null;

            JsonNode output = priceData.get("output");
            if (output == null) return null;

            return FinancialInfo.builder()
                    .per(parseBigDecimal(output.get("per")))
                    .pbr(parseBigDecimal(output.get("pbr")))
                    .eps(parseBigDecimal(output.get("eps")))
                    .bps(parseBigDecimal(output.get("bps")))
                    .marketCap(parseLong(output.get("hts_avls")))
                    .foreignOwnership(parseBigDecimal(output.get("hts_frgn_ehrt")))
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    // ========== 유틸리티 (StockDetailService에서 복사) ==========

    private java.math.BigDecimal parseBigDecimal(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            String text = node.asText().trim();
            if (text.isEmpty()) return null;
            return new java.math.BigDecimal(text);
        } catch (Exception e) { return null; }
    }

    private Long parseLong(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try { return Long.parseLong(node.asText().trim()); }
        catch (Exception e) { return null; }
    }

    private java.math.BigDecimal calculateMA(java.util.List<java.math.BigDecimal> prices, int period) {
        if (prices == null || prices.size() < period) return null;
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        for (int i = 0; i < period; i++) sum = sum.add(prices.get(i));
        return sum.divide(java.math.BigDecimal.valueOf(period), 2, java.math.RoundingMode.HALF_UP);
    }

    private java.util.List<java.math.BigDecimal> calculateMALine(java.util.List<java.math.BigDecimal> allCloses, int period, int displayCount) {
        java.util.List<java.math.BigDecimal> line = new java.util.ArrayList<>();
        for (int i = 0; i < displayCount; i++) {
            if (i + period <= allCloses.size()) {
                java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
                for (int j = i; j < i + period; j++) sum = sum.add(allCloses.get(j));
                line.add(sum.divide(java.math.BigDecimal.valueOf(period), 2, java.math.RoundingMode.HALF_UP));
            } else { line.add(null); }
        }
        return line;
    }
}
