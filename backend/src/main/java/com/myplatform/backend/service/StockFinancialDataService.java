package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
     */
    private Map<String, BigDecimal> getFinancialRatios(String token, String stockCode) {
        Map<String, BigDecimal> ratios = new HashMap<>();

        try {
            // 국내주식 재무비율 조회 API (FHKST66430200)
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
}
