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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 주식 재무 데이터 수집 서비스
 * - KIS API를 통해 PER, PBR, ROE 등 재무 지표 수집
 * - 투자자 매매 상위 종목 기준으로 수집
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class StockFinancialDataService {

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
     * 매일 장 마감 후 재무 데이터 수집 (18:00)
     */
    @Scheduled(cron = "0 0 18 * * MON-FRI")
    public void collectDailyFinancialData() {
        log.info("재무 데이터 일일 수집 시작");
        collectFinancialDataFromTopStocks();
    }

    /**
     * 외국인/기관 순매수 상위 종목의 재무 데이터 수집
     */
    public Map<String, Integer> collectFinancialDataFromTopStocks() {
        Map<String, Integer> result = new HashMap<>();
        Set<String> collectedCodes = new HashSet<>();
        int successCount = 0;
        int failCount = 0;

        try {
            // 외국인 순매수 상위 종목
            JsonNode foreignBuy = koreaInvestmentService.getForeignNetBuyTop();
            if (foreignBuy != null && foreignBuy.has("output")) {
                for (JsonNode item : foreignBuy.get("output")) {
                    String stockCode = item.has("mksc_shrn_iscd") ? item.get("mksc_shrn_iscd").asText() : null;
                    if (stockCode != null && !collectedCodes.contains(stockCode)) {
                        collectedCodes.add(stockCode);
                    }
                }
            }

            // 기관 순매수 상위 종목
            Thread.sleep(500);
            JsonNode instBuy = koreaInvestmentService.getInstitutionNetBuyTop();
            if (instBuy != null && instBuy.has("output")) {
                for (JsonNode item : instBuy.get("output")) {
                    String stockCode = item.has("mksc_shrn_iscd") ? item.get("mksc_shrn_iscd").asText() : null;
                    if (stockCode != null && !collectedCodes.contains(stockCode)) {
                        collectedCodes.add(stockCode);
                    }
                }
            }

            log.info("수집 대상 종목 수: {}", collectedCodes.size());

            // 각 종목별 재무 데이터 수집
            for (String stockCode : collectedCodes) {
                try {
                    Thread.sleep(200); // API 호출 간격
                    if (collectStockFinancialData(stockCode)) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                } catch (Exception e) {
                    log.error("종목 {} 재무 데이터 수집 실패: {}", stockCode, e.getMessage());
                    failCount++;
                }
            }

        } catch (Exception e) {
            log.error("재무 데이터 수집 중 오류", e);
        }

        result.put("total", collectedCodes.size());
        result.put("success", successCount);
        result.put("fail", failCount);
        log.info("재무 데이터 수집 완료 - 성공: {}, 실패: {}", successCount, failCount);

        return result;
    }

    /**
     * 단일 종목 재무 데이터 수집
     */
    public boolean collectStockFinancialData(String stockCode) {
        try {
            String token = koreaInvestmentService.getAccessToken();
            if (token == null) {
                log.error("토큰 발급 실패");
                return false;
            }

            // 1. 주식 현재가 조회 (PER, PBR, EPS 등)
            JsonNode priceData = koreaInvestmentService.getStockPrice(stockCode);
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

            // 시장 구분 (코스피/코스닥)
            String market = "KOSPI";
            if (stockCode.startsWith("A") || stockCode.length() == 6) {
                String firstChar = stockCode.substring(0, 1);
                // 코스닥은 보통 3으로 시작하는 경우가 많지만, 정확한 구분은 별도 API 필요
            }

            BigDecimal currentPrice = parseBigDecimal(output.path("stck_prpr").asText());
            BigDecimal marketCap = parseBigDecimal(output.path("hts_avls").asText())
                    .divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP); // 억원 단위
            BigDecimal per = parseBigDecimal(output.path("per").asText());
            BigDecimal pbr = parseBigDecimal(output.path("pbr").asText());
            BigDecimal eps = parseBigDecimal(output.path("eps").asText());

            // 2. 재무비율 조회 API 호출 (ROE, 영업이익률 등)
            Thread.sleep(100);
            Map<String, BigDecimal> financialRatios = getFinancialRatios(token, stockCode);

            BigDecimal roe = financialRatios.getOrDefault("roe", BigDecimal.ZERO);
            BigDecimal operatingMargin = financialRatios.getOrDefault("operatingMargin", BigDecimal.ZERO);
            BigDecimal netMargin = financialRatios.getOrDefault("netMargin", BigDecimal.ZERO);
            BigDecimal debtRatio = financialRatios.getOrDefault("debtRatio", BigDecimal.ZERO);

            // EPS 성장률이 있으면 PEG 계산
            BigDecimal epsGrowth = financialRatios.getOrDefault("epsGrowth", BigDecimal.ZERO);
            BigDecimal peg = null;
            if (per != null && per.compareTo(BigDecimal.ZERO) > 0 &&
                epsGrowth != null && epsGrowth.compareTo(BigDecimal.ZERO) > 0) {
                peg = per.divide(epsGrowth, 2, RoundingMode.HALF_UP);
            }

            // 기존 데이터 조회 또는 신규 생성
            LocalDate today = LocalDate.now();
            Optional<StockFinancialData> existingOpt = stockFinancialDataRepository
                    .findByStockCodeAndReportDate(stockCode, today);

            StockFinancialData financialData;
            if (existingOpt.isPresent()) {
                financialData = existingOpt.get();
            } else {
                financialData = new StockFinancialData();
                financialData.setStockCode(stockCode);
                financialData.setReportDate(today);
            }

            financialData.setStockName(stockName);
            financialData.setMarket(market);
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
     * 재무비율 조회 (KIS API)
     * - FHKST66430200: 재무비율 조회
     * - FHKST66430300: 손익계산서 조회 (순이익 등)
     */
    private Map<String, BigDecimal> getFinancialRatios(String token, String stockCode) {
        Map<String, BigDecimal> ratios = new HashMap<>();

        try {
            // 1. 국내주식 재무비율 조회 API (FHKST66430200)
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
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
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
                        // 성장률 관련
                        ratios.put("revenueGrowth", parseBigDecimal(latest.path("sls_cagr").asText()));
                        ratios.put("profitGrowth", parseBigDecimal(latest.path("ntin_cagr").asText()));
                    }
                }
            }

            // 2. 손익계산서 조회 API (FHKST66430300) - 순이익 금액
            Thread.sleep(100);
            String incomeUrl = baseUrl + "/uapi/domestic-stock/v1/finance/income-statement"
                    + "?FID_DIV_CLS_CODE=0"
                    + "&fid_cond_mrkt_div_code=J"
                    + "&fid_input_iscd=" + stockCode;

            headers.set("tr_id", "FHKST66430300");
            request = new HttpEntity<>(headers);
            response = restTemplate.exchange(incomeUrl, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if ("0".equals(root.path("rt_cd").asText())) {
                    JsonNode output = root.get("output");
                    if (output != null && output.isArray() && output.size() > 0) {
                        JsonNode latest = output.get(0);
                        // 당기순이익 (억원 단위로 저장)
                        BigDecimal netIncomeRaw = parseBigDecimal(latest.path("thtr_ntin").asText());
                        if (netIncomeRaw.compareTo(BigDecimal.ZERO) != 0) {
                            // 백만원 단위 -> 억원 단위 변환 (API 응답이 백만원 단위일 경우)
                            BigDecimal netIncome = netIncomeRaw.divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                            ratios.put("netIncome", netIncome);
                        }
                        // 매출액
                        BigDecimal revenue = parseBigDecimal(latest.path("sale_account").asText());
                        if (revenue.compareTo(BigDecimal.ZERO) != 0) {
                            ratios.put("revenue", revenue.divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
                        }
                        // 영업이익
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

    /**
     * 문자열을 BigDecimal로 파싱
     */
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
     * 수동으로 재무 데이터 수집 트리거
     */
    public Map<String, Integer> collectManually() {
        return collectFinancialDataFromTopStocks();
    }

    /**
     * 특정 종목 재무 데이터 수동 수집
     */
    public boolean collectSingleStock(String stockCode) {
        return collectStockFinancialData(stockCode);
    }

    /**
     * 수집된 재무 데이터 건수 조회
     */
    public long getDataCount() {
        return stockFinancialDataRepository.count();
    }

    /**
     * 전체 재무 데이터 삭제 후 재수집
     */
    public Map<String, Object> deleteAndRecollect() {
        Map<String, Object> result = new HashMap<>();

        // 기존 데이터 삭제
        long deletedCount = stockFinancialDataRepository.count();
        stockFinancialDataRepository.deleteAll();
        result.put("deleted", deletedCount);

        // 재수집
        Map<String, Integer> collectResult = collectFinancialDataFromTopStocks();
        result.putAll(collectResult);

        return result;
    }

    // ========== 전 종목 재무 데이터 수집 ==========

    /**
     * 전 종목 재무 데이터 수집
     * - StockShortData 테이블에 있는 모든 종목 대상
     * - Rate Limit 고려하여 종목당 300ms 대기
     * - ROE가 없으면 (EPS / BPS) * 100으로 계산
     * - 영업이익률은 null로 저장 (별도 크롤링 필요)
     *
     * @return 수집 결과 (total, success, fail, elapsedTime)
     */
    public Map<String, Object> collectAllStocksFinancialData() {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        log.info("========== 전 종목 재무 데이터 수집 시작 ==========");

        // 1. 전 종목 코드 조회
        List<String> allStockCodes = stockShortDataRepository.findDistinctStockCodes();
        int totalCount = allStockCodes.size();
        log.info("수집 대상 종목 수: {}", totalCount);

        if (totalCount == 0) {
            result.put("success", false);
            result.put("message", "수집할 종목이 없습니다. StockShortData 테이블에 데이터가 필요합니다.");
            return result;
        }

        int successCount = 0;
        int failCount = 0;
        int progressInterval = Math.max(totalCount / 20, 1); // 5% 단위 진행률 로깅

        // 2. 종목별 재무 데이터 수집
        for (int i = 0; i < allStockCodes.size(); i++) {
            String stockCode = allStockCodes.get(i);

            try {
                // Rate Limit: 종목당 300ms 대기
                if (i > 0) {
                    Thread.sleep(300);
                }

                boolean collected = collectStockFinancialDataSimple(stockCode);
                if (collected) {
                    successCount++;
                } else {
                    failCount++;
                }

                // 진행률 로깅
                if ((i + 1) % progressInterval == 0 || i == totalCount - 1) {
                    int progress = (int) (((i + 1) * 100.0) / totalCount);
                    log.info("진행률: {}/{} ({}%) - 성공: {}, 실패: {}",
                            i + 1, totalCount, progress, successCount, failCount);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("수집 중단됨");
                break;
            } catch (Exception e) {
                log.error("종목 {} 수집 실패: {}", stockCode, e.getMessage());
                failCount++;
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        log.info("========== 전 종목 재무 데이터 수집 완료 ==========");
        log.info("총 {}개 종목 중 성공: {}, 실패: {}, 소요시간: {}초",
                totalCount, successCount, failCount, elapsedTime / 1000);

        result.put("success", true);
        result.put("total", totalCount);
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("elapsedSeconds", elapsedTime / 1000);
        result.put("message", String.format("전 종목 수집 완료 (성공: %d, 실패: %d)", successCount, failCount));

        return result;
    }

    /**
     * 단일 종목 재무 데이터 수집 (간소화 버전)
     * - PER, PBR, EPS, BPS만 수집
     * - ROE가 없으면 (EPS / BPS) * 100으로 계산
     * - 영업이익률은 null로 저장
     *
     * @param stockCode 종목코드
     * @return 성공 여부
     */
    private boolean collectStockFinancialDataSimple(String stockCode) {
        try {
            // 주식 현재가 조회
            JsonNode priceData = koreaInvestmentService.getStockPrice(stockCode);
            if (priceData == null || !"0".equals(priceData.path("rt_cd").asText())) {
                log.debug("주식 현재가 조회 실패: {}", stockCode);
                return false;
            }

            JsonNode output = priceData.get("output");
            if (output == null) {
                return false;
            }

            // 종목명: API에서 가져오고, 없으면 StockShortData에서 조회
            String stockName = output.path("hts_kor_isnm").asText("");
            if (stockName.isEmpty()) {
                // StockShortData에서 종목명 조회
                stockName = stockShortDataRepository.findByStockCodeOrderByTradeDateDesc(stockCode, PageRequest.of(0, 1))
                        .stream()
                        .findFirst()
                        .map(s -> s.getStockName())
                        .orElse(stockCode);
            }

            // 시장 구분: 코스피/코스닥 구분
            // 코스닥 종목은 보통 A로 시작하거나 6자리 코드가 1, 2로 시작하지 않음
            String market = "KOSPI";
            String rprs_mrkt_kor_name = output.path("rprs_mrkt_kor_name").asText("");
            if (rprs_mrkt_kor_name.contains("코스닥") || rprs_mrkt_kor_name.contains("KOSDAQ")) {
                market = "KOSDAQ";
            } else if (stockCode.startsWith("3") || stockCode.startsWith("4") || stockCode.startsWith("9")) {
                // 코스닥 종목코드는 주로 3, 4, 9로 시작
                market = "KOSDAQ";
            }

            // 현재가
            BigDecimal currentPrice = parseBigDecimal(output.path("stck_prpr").asText());

            // 시가총액: hts_avls는 이미 억원 단위이거나, stck_hgpr(상장주식수) * 현재가로 계산
            BigDecimal marketCapRaw = parseBigDecimal(output.path("hts_avls").asText());
            BigDecimal marketCap;

            if (marketCapRaw.compareTo(BigDecimal.ZERO) > 0) {
                // hts_avls가 있으면 사용 (이미 억원 단위일 수 있음)
                // 값이 매우 크면(조 단위 이상) 원 단위로 판단하여 억원으로 변환
                if (marketCapRaw.compareTo(new BigDecimal("100000000")) > 0) {
                    // 원 단위 -> 억원 단위 변환
                    marketCap = marketCapRaw.divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP);
                } else {
                    // 이미 억원 단위
                    marketCap = marketCapRaw;
                }
            } else {
                // 시가총액이 없으면 상장주식수 * 현재가로 계산
                BigDecimal lstgStcn = parseBigDecimal(output.path("lstn_stcn").asText()); // 상장주식수
                if (lstgStcn.compareTo(BigDecimal.ZERO) > 0 && currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                    marketCap = lstgStcn.multiply(currentPrice)
                            .divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP);
                } else {
                    marketCap = BigDecimal.ZERO;
                }
            }

            // PER, PBR, EPS, BPS
            BigDecimal per = parseBigDecimal(output.path("per").asText());
            BigDecimal pbr = parseBigDecimal(output.path("pbr").asText());
            BigDecimal eps = parseBigDecimal(output.path("eps").asText());
            BigDecimal bps = parseBigDecimal(output.path("bps").asText());

            // ROE 계산: (EPS / BPS) * 100
            // KIS API에서 ROE를 직접 제공하지 않으므로 계산
            BigDecimal roe = BigDecimal.ZERO;
            if (bps != null && bps.compareTo(BigDecimal.ZERO) > 0 && eps != null) {
                roe = eps.divide(bps, 6, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
            }

            // 재무비율 API 호출 (epsGrowth, netIncome, profitGrowth 등)
            String token = koreaInvestmentService.getAccessToken();
            Map<String, BigDecimal> financialRatios = new HashMap<>();
            if (token != null) {
                try {
                    Thread.sleep(100); // API 호출 간격
                    financialRatios = getFinancialRatios(token, stockCode);
                } catch (Exception e) {
                    log.debug("재무비율 조회 실패 [{}]: {}", stockCode, e.getMessage());
                }
            }

            // ROE가 API에서 제공되면 사용
            BigDecimal roeFromApi = financialRatios.get("roe");
            if (roeFromApi != null && roeFromApi.compareTo(BigDecimal.ZERO) > 0) {
                roe = roeFromApi;
            }

            // EPS 성장률과 PEG
            BigDecimal epsGrowth = financialRatios.getOrDefault("epsGrowth", null);
            BigDecimal peg = null;
            if (per != null && per.compareTo(BigDecimal.ZERO) > 0 &&
                epsGrowth != null && epsGrowth.compareTo(BigDecimal.ZERO) > 0) {
                peg = per.divide(epsGrowth, 2, RoundingMode.HALF_UP);
            }

            // 순이익 관련
            BigDecimal netIncome = financialRatios.getOrDefault("netIncome", null);
            BigDecimal profitGrowth = financialRatios.getOrDefault("profitGrowth", null);
            BigDecimal revenueGrowth = financialRatios.getOrDefault("revenueGrowth", null);
            BigDecimal revenue = financialRatios.getOrDefault("revenue", null);
            BigDecimal operatingProfit = financialRatios.getOrDefault("operatingProfit", null);

            // Entity 저장/업데이트
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
            // 영업이익률은 null로 저장 (별도 크롤링 필요)
            financialData.setOperatingMargin(null);

            stockFinancialDataRepository.save(financialData);
            log.debug("재무 데이터 저장: {} ({})", stockName, stockCode);
            return true;

        } catch (Exception e) {
            log.debug("재무 데이터 수집 실패 [{}]: {}", stockCode, e.getMessage());
            return false;
        }
    }

    /**
     * 특정 종목 목록의 재무 데이터 수집
     * - 선택적으로 특정 종목들만 수집할 때 사용
     *
     * @param stockCodes 수집할 종목 코드 목록
     * @return 수집 결과
     */
    public Map<String, Object> collectStocksFinancialData(List<String> stockCodes) {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        log.info("지정 종목 재무 데이터 수집 시작: {}개", stockCodes.size());

        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < stockCodes.size(); i++) {
            String stockCode = stockCodes.get(i);

            try {
                if (i > 0) {
                    Thread.sleep(300);
                }

                if (collectStockFinancialDataSimple(stockCode)) {
                    successCount++;
                } else {
                    failCount++;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                failCount++;
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;

        result.put("success", true);
        result.put("total", stockCodes.size());
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("elapsedSeconds", elapsedTime / 1000);

        return result;
    }
}
