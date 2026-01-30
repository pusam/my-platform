package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.StockShortData;
import com.myplatform.backend.repository.StockShortDataRepository;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 공매도/대차잔고 데이터 수집 서비스
 *
 * 데이터 소스:
 * 1. 한국거래소 (KRX) - 공매도 거래량/잔고
 * 2. 금융투자협회 (KOFIA) - 대차잔고
 * 3. 한국투자증권 API - 보조 데이터
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShortSellingDataCollector {

    private final StockShortDataRepository shortDataRepository;
    private final KoreaInvestmentService koreaInvestmentService;
    private final NaverFinanceCrawler naverFinanceCrawler;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter KRX_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // KRX 정보데이터시스템 API (공공데이터 활용)
    @Value("${krx.api.base-url:https://data.krx.co.kr}")
    private String krxBaseUrl;

    @Value("${kofia.api.base-url:https://freesis.kofia.or.kr}")
    private String kofiaBaseUrl;

    /**
     * 장 마감 후 자동 수집 (평일 19:00)
     * - KRX 공매도/대차잔고 데이터는 18:00에 갱신되지 않는 경우가 있음
     * - 안정적인 데이터 수집을 위해 19:00로 설정
     */
    @Scheduled(cron = "0 0 19 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void scheduledDailyCollection() {
        LocalDate today = LocalDate.now();

        if (isWeekend(today)) {
            return;
        }

        log.info("=== 공매도/대차잔고 데이터 자동 수집 시작 ===");

        // 오늘 데이터가 이미 있는지 확인
        boolean hasData = shortDataRepository.existsByStockCodeAndTradeDate("005930", today);

        if (hasData) {
            log.info("오늘({}) 데이터가 이미 존재합니다. 기존 데이터 삭제 후 재수집합니다.", today);
            shortDataRepository.deleteByTradeDate(today);
        }

        int collected = collectShortSellingData(today);
        log.info("=== 공매도/대차잔고 데이터 자동 수집 완료: {}건 ===", collected);
    }

    /**
     * 특정 일자의 공매도/대차잔고 데이터 수집
     */
    @Transactional
    public int collectShortSellingData(LocalDate tradeDate) {
        log.info("공매도/대차잔고 데이터 수집 시작: {}", tradeDate);

        List<StockShortData> collectedData = new ArrayList<>();

        try {
            // 1. 대차잔고 데이터 수집 (KOFIA)
            Map<String, StockShortData> loanBalanceData = collectLoanBalanceData(tradeDate);
            log.info("대차잔고 데이터 수집: {}건", loanBalanceData.size());

            // 2. 공매도 거래량 데이터 수집 (KRX)
            Map<String, ShortVolumeData> shortVolumeData = collectShortVolumeData(tradeDate);
            log.info("공매도 거래량 데이터 수집: {}건", shortVolumeData.size());

            // 3. 주가 데이터로 보강 (한국투자증권 API)
            enrichWithPriceData(loanBalanceData);

            // 4. 공매도 거래량과 대차잔고 데이터 병합
            for (Map.Entry<String, StockShortData> entry : loanBalanceData.entrySet()) {
                String stockCode = entry.getKey();
                StockShortData data = entry.getValue();

                // 공매도 거래량 데이터 병합
                ShortVolumeData volumeData = shortVolumeData.get(stockCode);
                if (volumeData != null) {
                    data.setShortVolume(volumeData.shortVolume);
                    data.setShortTradingValue(volumeData.shortTradingValue);
                    data.setShortRatio(volumeData.shortRatio);
                }

                collectedData.add(data);
            }

            // 5. DB 저장
            if (!collectedData.isEmpty()) {
                shortDataRepository.saveAll(collectedData);
                log.info("공매도/대차잔고 데이터 저장 완료: {}건", collectedData.size());
            }

        } catch (Exception e) {
            log.error("공매도/대차잔고 데이터 수집 실패: {}", e.getMessage(), e);
        }

        return collectedData.size();
    }

    /**
     * 공매도/대차잔고 데이터 수집
     *
     * 데이터 소스 우선순위:
     * 1. 네이버 금융 공매도 데이터 (필수)
     * 2. 대차잔고 데이터 (선택 - 404시 스킵)
     * 3. 한국투자증권 API로 주가 보강
     */
    private Map<String, StockShortData> collectLoanBalanceData(LocalDate tradeDate) {
        Map<String, StockShortData> result = new HashMap<>();

        try {
            List<String> targetStocks = getTargetStockCodes();

            for (String stockCode : targetStocks) {
                try {
                    // 1. 네이버 금융 공매도 데이터 조회 (필수)
                    StockShortData data = fetchShortSellingFromNaver(stockCode, tradeDate);

                    // 2. 공매도 데이터 없으면 KIS API로 기본 데이터 생성
                    if (data == null) {
                        data = fetchLoanBalanceFromKis(stockCode, tradeDate);
                    }

                    // 3. 대차잔고 데이터 추가 시도 (실패해도 OK)
                    if (data != null && naverFinanceCrawler.isLendingPageAvailable()) {
                        enrichWithLoanBalanceData(data, stockCode, tradeDate);
                    }

                    if (data != null) {
                        result.put(stockCode, data);
                    }

                    // API 호출 제한 방지
                    Thread.sleep(100);

                } catch (Exception e) {
                    log.debug("종목 {} 데이터 조회 실패: {}", stockCode, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("공매도 데이터 수집 실패: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 네이버 금융에서 공매도 데이터 조회 (대차잔고 없이 공매도만)
     */
    private StockShortData fetchShortSellingFromNaver(String stockCode, LocalDate tradeDate) {
        try {
            // 공매도 데이터 조회
            List<NaverFinanceCrawler.ShortSellingData> shortDataList =
                    naverFinanceCrawler.crawlShortSellingData(stockCode, 10);

            // 해당 날짜의 데이터 찾기
            for (NaverFinanceCrawler.ShortSellingData shortData : shortDataList) {
                if (shortData.getTradeDate().equals(tradeDate)) {
                    return StockShortData.builder()
                            .stockCode(stockCode)
                            .stockName(getStockNameFromCode(stockCode))
                            .tradeDate(tradeDate)
                            .closePrice(shortData.getClosePrice())
                            .shortVolume(shortData.getShortVolume())
                            .shortTradingValue(shortData.getShortTradingValue())
                            .shortRatio(shortData.getShortRatio())
                            .volume(shortData.getTotalVolume())
                            .build();
                }
            }

            log.debug("네이버 금융에서 {} 날짜의 공매도 데이터를 찾지 못함: {}", tradeDate, stockCode);
            return null;

        } catch (Exception e) {
            log.debug("네이버 금융 공매도 조회 실패 [{}]: {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * 대차잔고 데이터로 보강 (선택적)
     */
    private void enrichWithLoanBalanceData(StockShortData data, String stockCode, LocalDate tradeDate) {
        try {
            List<NaverFinanceCrawler.LoanBalanceData> loanDataList =
                    naverFinanceCrawler.crawlLoanBalanceData(stockCode, 7);

            for (NaverFinanceCrawler.LoanBalanceData loanData : loanDataList) {
                if (loanData.getTradeDate().equals(tradeDate)) {
                    data.setLoanBalanceQuantity(loanData.getLoanBalance());
                    data.setLoanBalanceValue(loanData.getLoanBalanceValue());
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("대차잔고 보강 실패 [{}]: {}", stockCode, e.getMessage());
        }
    }


    /**
     * 한국투자증권 API를 통한 개별 종목 대차잔고 조회
     */
    private StockShortData fetchLoanBalanceFromKis(String stockCode, LocalDate tradeDate) {
        if (!koreaInvestmentService.isConfigured()) {
            return null;
        }

        try {
            // 주식 기본 정보 조회
            JsonNode stockInfo = koreaInvestmentService.getStockPrice(stockCode);
            if (stockInfo == null || !isSuccessResponse(stockInfo)) {
                return null;
            }

            JsonNode output = stockInfo.get("output");
            if (output == null) {
                return null;
            }

            // 데이터 추출
            String stockName = getJsonString(output, "stck_shrn_iscd", stockCode);
            // 종목명은 별도 API에서 가져올 수 있음
            BigDecimal closePrice = getJsonBigDecimal(output, "stck_prpr");
            BigDecimal changeRate = getJsonBigDecimal(output, "prdy_ctrt");
            BigDecimal volume = getJsonBigDecimal(output, "acml_vol");

            // 대차잔고 관련 데이터 (실제로는 별도 API 필요)
            // 한국투자증권 API에서는 대차잔고 직접 제공하지 않음
            // KRX/KOFIA 데이터와 병합 필요

            return StockShortData.builder()
                    .stockCode(stockCode)
                    .stockName(getStockNameFromCode(stockCode))
                    .tradeDate(tradeDate)
                    .closePrice(closePrice)
                    .changeRate(changeRate)
                    .volume(volume)
                    .build();

        } catch (Exception e) {
            log.debug("종목 {} KIS 조회 실패: {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * 공매도 거래량 데이터 수집 (KRX)
     *
     * KRX 정보데이터시스템 API 호출
     * - 공매도 종합 정보 (공매도 거래량, 거래대금, 비중)
     */
    private Map<String, ShortVolumeData> collectShortVolumeData(LocalDate tradeDate) {
        Map<String, ShortVolumeData> result = new HashMap<>();
        String dateStr = tradeDate.format(DATE_FORMATTER);

        try {
            // KRX 공매도 데이터 API 호출
            // 실제 API 연동 시 아래 형태로 호출
            /*
            String url = krxBaseUrl + "/comm/bldAttendant/getJsonData.cmd";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("bld", "dbms/MDC/STAT/srt/MDCSTAT30101");
            params.add("trdDd", dateStr);
            params.add("mktId", "STK");  // KOSPI

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode output = root.get("output");

                if (output != null && output.isArray()) {
                    for (JsonNode item : output) {
                        String stockCode = getJsonString(item, "ISU_SRT_CD", "");
                        if (stockCode.length() >= 6) {
                            stockCode = stockCode.substring(1); // A005930 -> 005930

                            ShortVolumeData data = new ShortVolumeData();
                            data.shortVolume = getJsonBigDecimal(item, "CVSRTSELL_TRDVOL");
                            data.shortTradingValue = getJsonBigDecimal(item, "CVSRTSELL_TRDVAL");
                            data.shortRatio = getJsonBigDecimal(item, "CVSRTSELL_WT");

                            result.put(stockCode, data);
                        }
                    }
                }
            }
            */

            // 현재는 더미 데이터 또는 KIS API 활용
            log.info("KRX 공매도 데이터 수집 (API 연동 필요)");

        } catch (Exception e) {
            log.error("공매도 거래량 데이터 수집 실패: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 주가 데이터로 보강
     */
    private void enrichWithPriceData(Map<String, StockShortData> dataMap) {
        if (!koreaInvestmentService.isConfigured()) {
            log.warn("한국투자증권 API 미설정 - 주가 데이터 보강 건너뜀");
            return;
        }

        int enriched = 0;
        for (Map.Entry<String, StockShortData> entry : dataMap.entrySet()) {
            String stockCode = entry.getKey();
            StockShortData data = entry.getValue();

            try {
                if (data.getClosePrice() == null) {
                    JsonNode priceInfo = koreaInvestmentService.getStockPrice(stockCode);
                    if (priceInfo != null && isSuccessResponse(priceInfo)) {
                        JsonNode output = priceInfo.get("output");
                        if (output != null) {
                            data.setClosePrice(getJsonBigDecimal(output, "stck_prpr"));
                            data.setChangeRate(getJsonBigDecimal(output, "prdy_ctrt"));
                            data.setVolume(getJsonBigDecimal(output, "acml_vol"));
                            enriched++;
                        }
                    }
                    Thread.sleep(50);  // API 호출 제한
                }
            } catch (Exception e) {
                log.debug("주가 보강 실패 [{}]: {}", stockCode, e.getMessage());
            }
        }

        log.info("주가 데이터 보강 완료: {}건", enriched);
    }

    /**
     * 수집 대상 종목 코드 목록
     * - KOSPI 200 + KOSDAQ 150 대표 종목
     */
    private List<String> getTargetStockCodes() {
        // 실제로는 DB 또는 설정에서 관리
        return Arrays.asList(
                // KOSPI 대형주
                "005930", "000660", "035420", "005380", "006400",
                "035720", "051910", "068270", "028260", "105560",
                "012330", "055550", "066570", "003550", "017670",
                "018260", "034730", "086790", "096770", "032830",
                // KOSPI 중형주
                "009150", "003490", "011200", "010130", "024110",
                "011170", "000270", "010140", "001040", "003670",
                // KOSDAQ
                "247540", "091990", "357780", "068760", "035900",
                "293490", "328130", "263750", "036570", "112040"
        );
    }

    /**
     * 종목코드로 종목명 조회
     */
    private String getStockNameFromCode(String stockCode) {
        // 실제로는 DB 또는 API에서 조회
        Map<String, String> stockNames = new HashMap<>();
        stockNames.put("005930", "삼성전자");
        stockNames.put("000660", "SK하이닉스");
        stockNames.put("035420", "NAVER");
        stockNames.put("005380", "현대차");
        stockNames.put("006400", "삼성SDI");
        stockNames.put("035720", "카카오");
        stockNames.put("051910", "LG화학");
        stockNames.put("068270", "셀트리온");
        stockNames.put("028260", "삼성물산");
        stockNames.put("105560", "KB금융");
        // ... 추가 종목

        return stockNames.getOrDefault(stockCode, stockCode);
    }

    /**
     * 수동 데이터 수집 API 호출용
     */
    public Map<String, Object> collectManually(LocalDate tradeDate) {
        Map<String, Object> result = new HashMap<>();

        try {
            int collected = collectShortSellingData(tradeDate);
            result.put("success", true);
            result.put("collectedCount", collected);
            result.put("tradeDate", tradeDate.toString());
            result.put("message", String.format("%s 공매도/대차잔고 데이터 %d건 수집 완료", tradeDate, collected));
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "수집 실패: " + e.getMessage());
        }

        return result;
    }

    /**
     * 과거 데이터 일괄 수집 (백필)
     */
    @Transactional
    public int collectHistoricalData(LocalDate startDate, LocalDate endDate) {
        log.info("과거 데이터 수집 시작: {} ~ {}", startDate, endDate);

        int totalCollected = 0;
        LocalDate current = startDate;

        while (!current.isAfter(endDate)) {
            if (!isWeekend(current)) {
                try {
                    // 기존 데이터 확인
                    if (!shortDataRepository.existsByStockCodeAndTradeDate("005930", current)) {
                        int collected = collectShortSellingData(current);
                        totalCollected += collected;
                        log.info("{}: {}건 수집", current, collected);

                        // API 호출 제한 방지
                        Thread.sleep(1000);
                    } else {
                        log.info("{}: 데이터 이미 존재", current);
                    }
                } catch (Exception e) {
                    log.error("{} 수집 실패: {}", current, e.getMessage());
                }
            }
            current = current.plusDays(1);
        }

        log.info("과거 데이터 수집 완료: 총 {}건", totalCollected);
        return totalCollected;
    }

    // ========== 유틸리티 메서드 ==========

    private boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private boolean isSuccessResponse(JsonNode response) {
        return response.has("rt_cd") && "0".equals(response.get("rt_cd").asText());
    }

    private String getJsonString(JsonNode node, String fieldName, String defaultValue) {
        if (node.has(fieldName) && !node.get(fieldName).isNull()) {
            return node.get(fieldName).asText().trim();
        }
        return defaultValue;
    }

    private BigDecimal getJsonBigDecimal(JsonNode node, String fieldName) {
        if (node.has(fieldName) && !node.get(fieldName).isNull()) {
            String value = node.get(fieldName).asText().replace(",", "").trim();
            if (value.isEmpty() || value.equals("-")) {
                return BigDecimal.ZERO;
            }
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * 공매도 거래량 데이터 임시 클래스
     */
    private static class ShortVolumeData {
        BigDecimal shortVolume;
        BigDecimal shortTradingValue;
        BigDecimal shortRatio;
    }
}
