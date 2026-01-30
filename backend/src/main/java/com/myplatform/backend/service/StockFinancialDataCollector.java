package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockShortDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 주식 재무 데이터 수집 컴포넌트
 * - 각 종목별 수집을 독립적인 트랜잭션으로 처리
 * - API 호출 실패 시 최대 3회 재시도
 * - StockFinancialDataService에서 호출
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockFinancialDataCollector {

    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_DELAY_MS = 500;

    private final StockFinancialDataRepository stockFinancialDataRepository;
    private final StockShortDataRepository stockShortDataRepository;
    private final KoreaInvestmentService koreaInvestmentService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${kis.api.base-url:https://openapi.koreainvestment.com:9443}")
    private String baseUrl;

    @Value("${kis.api.app-key:}")
    private String appKey;

    @Value("${kis.api.app-secret:}")
    private String appSecret;

    /**
     * 단일 종목 재무 데이터 수집 (독립 트랜잭션)
     * - REQUIRES_NEW: 항상 새 트랜잭션 시작
     * - 실패해도 다른 종목에 영향 없음
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean collectStockFinancialData(String stockCode) {
        try {
            String token = koreaInvestmentService.getAccessToken();
            if (token == null) {
                log.error("토큰 발급 실패");
                return false;
            }

            // 재시도 로직 적용된 현재가 조회
            JsonNode priceData = getStockPriceWithRetry(stockCode);
            if (priceData == null || !"0".equals(priceData.path("rt_cd").asText())) {
                log.warn("주식 현재가 조회 실패: {}", stockCode);
                return false;
            }

            JsonNode output = priceData.get("output");
            if (output == null) {
                return false;
            }

            String stockName = output.path("hts_kor_isnm").asText("");
            if (stockName.isEmpty()) {
                stockName = stockCode;
            }

            String market = "KOSPI";

            BigDecimal currentPrice = parseBigDecimal(output.path("stck_prpr").asText());
            BigDecimal marketCap = parseBigDecimal(output.path("hts_avls").asText())
                    .divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP);
            BigDecimal per = parseBigDecimal(output.path("per").asText());
            BigDecimal pbr = parseBigDecimal(output.path("pbr").asText());
            BigDecimal eps = parseBigDecimal(output.path("eps").asText());

            // 재무비율 조회
            Thread.sleep(100);
            Map<String, BigDecimal> financialRatios = getFinancialRatios(token, stockCode);

            BigDecimal roe = financialRatios.getOrDefault("roe", BigDecimal.ZERO);
            BigDecimal netMargin = financialRatios.getOrDefault("netMargin", BigDecimal.ZERO);
            BigDecimal debtRatio = financialRatios.getOrDefault("debtRatio", BigDecimal.ZERO);
            BigDecimal revenue = financialRatios.getOrDefault("revenue", null);
            BigDecimal operatingProfit = financialRatios.getOrDefault("operatingProfit", null);

            // 영업이익률: API에서 가져오거나, operatingProfit/revenue로 계산
            BigDecimal operatingMargin = financialRatios.getOrDefault("operatingMargin", null);
            if ((operatingMargin == null || operatingMargin.compareTo(BigDecimal.ZERO) == 0)
                    && operatingProfit != null && revenue != null
                    && revenue.compareTo(BigDecimal.ZERO) > 0) {
                operatingMargin = operatingProfit.divide(revenue, 6, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
            }

            BigDecimal epsGrowth = financialRatios.getOrDefault("epsGrowth", BigDecimal.ZERO);
            BigDecimal peg = null;
            if (per != null && per.compareTo(BigDecimal.ZERO) > 0 &&
                epsGrowth != null && epsGrowth.compareTo(BigDecimal.ZERO) > 0) {
                peg = per.divide(epsGrowth, 2, RoundingMode.HALF_UP);
            }

            LocalDate today = LocalDate.now();
            StockFinancialData financialData = stockFinancialDataRepository
                    .findByStockCodeAndReportDate(stockCode, today)
                    .orElse(new StockFinancialData());

            financialData.setStockCode(stockCode);
            financialData.setStockName(stockName);
            financialData.setMarket(market);
            financialData.setReportDate(today);
            financialData.setCurrentPrice(currentPrice);
            financialData.setMarketCap(marketCap);
            financialData.setPer(per);
            financialData.setPbr(pbr);
            financialData.setEps(eps);
            financialData.setRoe(roe);
            financialData.setOperatingMargin(operatingMargin);
            financialData.setNetMargin(netMargin);
            financialData.setDebtRatio(debtRatio);
            financialData.setEpsGrowth(epsGrowth);
            financialData.setPeg(peg);

            stockFinancialDataRepository.save(financialData);
            log.debug("재무 데이터 저장 완료: {} ({})", stockName, stockCode);
            return true;

        } catch (Exception e) {
            log.error("재무 데이터 수집 실패 [{}]: {}", stockCode, e.getMessage());
            return false;
        }
    }

    /**
     * 단일 종목 재무 데이터 수집 - 간소화 버전 (독립 트랜잭션)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean collectStockFinancialDataSimple(String stockCode) {
        try {
            // 재시도 로직 적용된 현재가 조회
            JsonNode priceData = getStockPriceWithRetry(stockCode);
            if (priceData == null || !"0".equals(priceData.path("rt_cd").asText())) {
                log.debug("주식 현재가 조회 실패: {}", stockCode);
                return false;
            }

            JsonNode output = priceData.get("output");
            if (output == null) {
                return false;
            }

            String stockName = output.path("hts_kor_isnm").asText("");
            if (stockName.isEmpty()) {
                stockName = stockShortDataRepository.findByStockCodeOrderByTradeDateDesc(stockCode, PageRequest.of(0, 1))
                        .stream()
                        .findFirst()
                        .map(s -> s.getStockName())
                        .orElse(stockCode);
            }

            String market = "KOSPI";
            String rprs_mrkt_kor_name = output.path("rprs_mrkt_kor_name").asText("");
            if (rprs_mrkt_kor_name.contains("코스닥") || rprs_mrkt_kor_name.contains("KOSDAQ")) {
                market = "KOSDAQ";
            } else if (stockCode.startsWith("3") || stockCode.startsWith("4") || stockCode.startsWith("9")) {
                market = "KOSDAQ";
            }

            BigDecimal currentPrice = parseBigDecimal(output.path("stck_prpr").asText());

            BigDecimal marketCapRaw = parseBigDecimal(output.path("hts_avls").asText());
            BigDecimal marketCap;

            if (marketCapRaw.compareTo(BigDecimal.ZERO) > 0) {
                if (marketCapRaw.compareTo(new BigDecimal("100000000")) > 0) {
                    marketCap = marketCapRaw.divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP);
                } else {
                    marketCap = marketCapRaw;
                }
            } else {
                BigDecimal lstgStcn = parseBigDecimal(output.path("lstn_stcn").asText());
                if (lstgStcn.compareTo(BigDecimal.ZERO) > 0 && currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                    marketCap = lstgStcn.multiply(currentPrice)
                            .divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP);
                } else {
                    marketCap = BigDecimal.ZERO;
                }
            }

            BigDecimal per = parseBigDecimal(output.path("per").asText());
            BigDecimal pbr = parseBigDecimal(output.path("pbr").asText());
            BigDecimal eps = parseBigDecimal(output.path("eps").asText());
            BigDecimal bps = parseBigDecimal(output.path("bps").asText());

            BigDecimal roe = BigDecimal.ZERO;
            if (bps != null && bps.compareTo(BigDecimal.ZERO) > 0 && eps != null) {
                roe = eps.divide(bps, 6, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
            }

            String token = koreaInvestmentService.getAccessToken();
            Map<String, BigDecimal> financialRatios = new HashMap<>();
            if (token != null) {
                try {
                    Thread.sleep(100);
                    financialRatios = getFinancialRatios(token, stockCode);
                } catch (Exception e) {
                    log.debug("재무비율 조회 실패 [{}]: {}", stockCode, e.getMessage());
                }
            }

            BigDecimal roeFromApi = financialRatios.get("roe");
            if (roeFromApi != null && roeFromApi.compareTo(BigDecimal.ZERO) > 0) {
                roe = roeFromApi;
            }

            BigDecimal epsGrowth = financialRatios.getOrDefault("epsGrowth", null);
            BigDecimal peg = null;
            if (per != null && per.compareTo(BigDecimal.ZERO) > 0 &&
                epsGrowth != null && epsGrowth.compareTo(BigDecimal.ZERO) > 0) {
                peg = per.divide(epsGrowth, 2, RoundingMode.HALF_UP);
            }

            BigDecimal netIncome = financialRatios.getOrDefault("netIncome", null);
            BigDecimal profitGrowth = financialRatios.getOrDefault("profitGrowth", null);
            BigDecimal revenueGrowth = financialRatios.getOrDefault("revenueGrowth", null);
            BigDecimal revenue = financialRatios.getOrDefault("revenue", null);
            BigDecimal operatingProfit = financialRatios.getOrDefault("operatingProfit", null);

            // 영업이익률: API에서 가져오거나, operatingProfit/revenue로 계산
            BigDecimal operatingMargin = financialRatios.getOrDefault("operatingMargin", null);
            if ((operatingMargin == null || operatingMargin.compareTo(BigDecimal.ZERO) == 0)
                    && operatingProfit != null && revenue != null
                    && revenue.compareTo(BigDecimal.ZERO) > 0) {
                // 영업이익률 = (영업이익 / 매출액) * 100
                operatingMargin = operatingProfit.divide(revenue, 6, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
            }

            LocalDate today = LocalDate.now();
            StockFinancialData financialData = stockFinancialDataRepository
                    .findByStockCodeAndReportDate(stockCode, today)
                    .orElse(new StockFinancialData());

            financialData.setStockCode(stockCode);
            financialData.setStockName(stockName);
            financialData.setMarket(market);
            financialData.setReportDate(today);
            financialData.setCurrentPrice(currentPrice);
            financialData.setMarketCap(marketCap);
            financialData.setPer(per);
            financialData.setPbr(pbr);
            financialData.setEps(eps);
            financialData.setBps(bps);
            financialData.setRoe(roe);
            financialData.setEpsGrowth(epsGrowth);
            financialData.setPeg(peg);
            financialData.setNetIncome(netIncome);
            financialData.setProfitGrowth(profitGrowth);
            financialData.setRevenueGrowth(revenueGrowth);
            financialData.setRevenue(revenue);
            financialData.setOperatingProfit(operatingProfit);
            financialData.setOperatingMargin(operatingMargin);

            stockFinancialDataRepository.save(financialData);
            log.debug("재무 데이터 저장: {} ({})", stockName, stockCode);
            return true;

        } catch (Exception e) {
            log.debug("재무 데이터 수집 실패 [{}]: {}", stockCode, e.getMessage());
            return false;
        }
    }

    /**
     * 재무비율 조회 (KIS API)
     */
    public Map<String, BigDecimal> getFinancialRatios(String token, String stockCode) {
        Map<String, BigDecimal> ratios = new HashMap<>();

        try {
            String url = baseUrl + "/uapi/domestic-stock/v1/finance/financial-ratio"
                    + "?FID_DIV_CLS_CODE=0"
                    + "&fid_cond_mrkt_div_code=J"
                    + "&fid_input_iscd=" + stockCode;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("authorization", "Bearer " + token);
            headers.set("appkey", appKey);
            headers.set("appsecret", appSecret);
            headers.set("tr_id", "FHKST66430200");
            headers.set("custtype", "P");

            HttpEntity<String> request = new HttpEntity<>(headers);
            // 재시도 로직 적용
            ResponseEntity<String> response = executeWithRetry(url, request);

            if (response != null && response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if ("0".equals(root.path("rt_cd").asText())) {
                    JsonNode output = root.get("output");
                    if (output != null && output.isArray() && output.size() > 0) {
                        JsonNode latest = output.get(0);
                        ratios.put("roe", parseBigDecimal(latest.path("roe_val").asText()));
                        ratios.put("operatingMargin", parseBigDecimal(latest.path("bsop_prfi_inrt").asText()));
                        ratios.put("netMargin", parseBigDecimal(latest.path("ntin_inrt").asText()));
                        ratios.put("debtRatio", parseBigDecimal(latest.path("lblt_rate").asText()));
                        ratios.put("epsGrowth", parseBigDecimal(latest.path("eps_cagr").asText()));
                        ratios.put("revenueGrowth", parseBigDecimal(latest.path("sls_cagr").asText()));
                        ratios.put("profitGrowth", parseBigDecimal(latest.path("ntin_cagr").asText()));
                    }
                }
            }

            Thread.sleep(100);
            String incomeUrl = baseUrl + "/uapi/domestic-stock/v1/finance/income-statement"
                    + "?FID_DIV_CLS_CODE=0"
                    + "&fid_cond_mrkt_div_code=J"
                    + "&fid_input_iscd=" + stockCode;

            headers.set("tr_id", "FHKST66430300");
            request = new HttpEntity<>(headers);
            // 재시도 로직 적용
            response = executeWithRetry(incomeUrl, request);

            if (response != null && response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if ("0".equals(root.path("rt_cd").asText())) {
                    JsonNode output = root.get("output");
                    if (output != null && output.isArray() && output.size() > 0) {
                        JsonNode latest = output.get(0);
                        BigDecimal netIncomeRaw = parseBigDecimal(latest.path("thtr_ntin").asText());
                        if (netIncomeRaw.compareTo(BigDecimal.ZERO) != 0) {
                            BigDecimal netIncome = netIncomeRaw.divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                            ratios.put("netIncome", netIncome);
                        }
                        BigDecimal revenue = parseBigDecimal(latest.path("sale_account").asText());
                        if (revenue.compareTo(BigDecimal.ZERO) != 0) {
                            ratios.put("revenue", revenue.divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
                        }
                        BigDecimal operatingProfit = parseBigDecimal(latest.path("bsop_prti").asText());
                        if (operatingProfit.compareTo(BigDecimal.ZERO) != 0) {
                            ratios.put("operatingProfit", operatingProfit.divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("재무비율 조회 실패 [{}]: {}", stockCode, e.getMessage());
        }

        return ratios;
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isEmpty() || "-".equals(value)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 과거 데이터 기반 성장률 계산 및 업데이트
     * - 전년 동기 대비 성장률 계산 (YoY)
     * - epsGrowth, profitGrowth, revenueGrowth 계산
     * - PEG = PER / epsGrowth 계산
     *
     * @return 업데이트된 종목 수
     */
    @Transactional
    public int calculateAndUpdateGrowthRates() {
        log.info("성장률 계산 시작...");

        // 최신 데이터가 있는 모든 종목 조회
        LocalDate today = LocalDate.now();
        LocalDate oneYearAgo = today.minusYears(1);

        // 최근 30일 내 데이터가 있는 종목들
        List<StockFinancialData> recentStocks = stockFinancialDataRepository
                .findAllRecentData(today.minusDays(30));

        // 종목별로 그룹화
        Map<String, List<StockFinancialData>> stockDataMap = recentStocks.stream()
                .collect(java.util.stream.Collectors.groupingBy(StockFinancialData::getStockCode));

        int updatedCount = 0;

        for (Map.Entry<String, List<StockFinancialData>> entry : stockDataMap.entrySet()) {
            String stockCode = entry.getKey();
            List<StockFinancialData> dataList = entry.getValue();

            if (dataList.isEmpty()) continue;

            // 최신 데이터
            StockFinancialData latest = dataList.get(0);

            // 1년 전 데이터 찾기
            StockFinancialData yearAgoData = findYearAgoData(stockCode, latest.getReportDate());

            if (yearAgoData == null) {
                // 1년 전 데이터 없으면 이전 분기 데이터로 대체 (최소 2개 이상 데이터 필요)
                if (dataList.size() >= 2) {
                    yearAgoData = dataList.get(dataList.size() - 1);
                } else {
                    continue;
                }
            }

            boolean updated = false;

            // EPS 성장률 계산
            if ((latest.getEpsGrowth() == null || latest.getEpsGrowth().compareTo(BigDecimal.ZERO) == 0)
                    && latest.getEps() != null && yearAgoData.getEps() != null
                    && yearAgoData.getEps().compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal epsGrowth = calculateGrowthRate(latest.getEps(), yearAgoData.getEps());
                if (epsGrowth != null) {
                    latest.setEpsGrowth(epsGrowth);
                    updated = true;
                }
            }

            // 순이익 성장률 계산
            if ((latest.getProfitGrowth() == null || latest.getProfitGrowth().compareTo(BigDecimal.ZERO) == 0)
                    && latest.getNetIncome() != null && yearAgoData.getNetIncome() != null
                    && yearAgoData.getNetIncome().compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal profitGrowth = calculateGrowthRate(latest.getNetIncome(), yearAgoData.getNetIncome());
                if (profitGrowth != null) {
                    latest.setProfitGrowth(profitGrowth);
                    updated = true;
                }
            }

            // 매출 성장률 계산
            if ((latest.getRevenueGrowth() == null || latest.getRevenueGrowth().compareTo(BigDecimal.ZERO) == 0)
                    && latest.getRevenue() != null && yearAgoData.getRevenue() != null
                    && yearAgoData.getRevenue().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal revenueGrowth = calculateGrowthRate(latest.getRevenue(), yearAgoData.getRevenue());
                if (revenueGrowth != null) {
                    latest.setRevenueGrowth(revenueGrowth);
                    updated = true;
                }
            }

            // PEG 계산 (epsGrowth가 없으면 profitGrowth로 대체)
            if ((latest.getPeg() == null || latest.getPeg().compareTo(BigDecimal.ZERO) == 0)
                    && latest.getPer() != null && latest.getPer().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal growthForPeg = latest.getEpsGrowth();
                if (growthForPeg == null || growthForPeg.compareTo(BigDecimal.ZERO) <= 0) {
                    growthForPeg = latest.getProfitGrowth();
                }
                if (growthForPeg != null && growthForPeg.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal peg = latest.getPer().divide(growthForPeg, 2, RoundingMode.HALF_UP);
                    latest.setPeg(peg);
                    updated = true;
                }
            }

            if (updated) {
                stockFinancialDataRepository.save(latest);
                updatedCount++;
                log.debug("성장률 업데이트: {} - epsGrowth: {}, profitGrowth: {}, peg: {}",
                        stockCode, latest.getEpsGrowth(), latest.getProfitGrowth(), latest.getPeg());
            }
        }

        log.info("성장률 계산 완료 - 업데이트: {}건", updatedCount);
        return updatedCount;
    }

    /**
     * 1년 전 데이터 조회
     */
    private StockFinancialData findYearAgoData(String stockCode, LocalDate currentDate) {
        LocalDate targetDate = currentDate.minusYears(1);
        // 1년 전 ±30일 범위에서 조회
        LocalDate minDate = targetDate.minusDays(30);
        LocalDate maxDate = targetDate.plusDays(30);

        List<StockFinancialData> historicalData = stockFinancialDataRepository
                .findByStockCodeOrderByReportDateDesc(stockCode);

        return historicalData.stream()
                .filter(d -> !d.getReportDate().isBefore(minDate) && !d.getReportDate().isAfter(maxDate))
                .findFirst()
                .orElse(null);
    }

    /**
     * 성장률 계산 (YoY)
     * @return (current - previous) / |previous| * 100
     */
    private BigDecimal calculateGrowthRate(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        try {
            return current.subtract(previous)
                    .divide(previous.abs(), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /**
     * 주식 현재가 조회 (재시도 로직 포함)
     * - 최대 3회 재시도
     * - 재시도 간 500ms 대기
     */
    private JsonNode getStockPriceWithRetry(String stockCode) {
        int retryCount = 0;
        Exception lastException = null;

        while (retryCount < MAX_RETRY_COUNT) {
            try {
                JsonNode result = koreaInvestmentService.getStockPrice(stockCode);
                if (result != null) {
                    return result;
                }
                log.debug("주식 현재가 조회 결과 null [{}], 재시도 {}/{}", stockCode, retryCount + 1, MAX_RETRY_COUNT);
            } catch (Exception e) {
                lastException = e;
                log.debug("주식 현재가 조회 실패 [{}], 재시도 {}/{}: {}",
                        stockCode, retryCount + 1, MAX_RETRY_COUNT, e.getMessage());
            }

            retryCount++;
            if (retryCount < MAX_RETRY_COUNT) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (lastException != null) {
            log.warn("주식 현재가 조회 최종 실패 [{}]: {}", stockCode, lastException.getMessage());
        }
        return null;
    }

    /**
     * 재무비율 조회 (재시도 로직 포함)
     * - 최대 3회 재시도
     */
    private ResponseEntity<String> executeWithRetry(String url, HttpEntity<String> request) {
        int retryCount = 0;
        Exception lastException = null;

        while (retryCount < MAX_RETRY_COUNT) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
                if (response.getStatusCode() == HttpStatus.OK) {
                    return response;
                }
                log.debug("API 호출 실패 응답: {}, 재시도 {}/{}", response.getStatusCode(), retryCount + 1, MAX_RETRY_COUNT);
            } catch (Exception e) {
                lastException = e;
                log.debug("API 호출 실패, 재시도 {}/{}: {}", retryCount + 1, MAX_RETRY_COUNT, e.getMessage());
            }

            retryCount++;
            if (retryCount < MAX_RETRY_COUNT) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (lastException != null) {
            log.warn("API 호출 최종 실패: {}", lastException.getMessage());
        }
        return null;
    }
}
