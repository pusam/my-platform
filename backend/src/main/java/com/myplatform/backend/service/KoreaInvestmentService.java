package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 한국투자증권 Open API 서비스
 * - OAuth 토큰 발급 및 관리
 * - API 호출 공통 기능
 */
@Service
public class KoreaInvestmentService {

    private static final Logger log = LoggerFactory.getLogger(KoreaInvestmentService.class);

    @Value("${kis.api.app-key:}")
    private String appKey;

    @Value("${kis.api.app-secret:}")
    private String appSecret;

    @Value("${kis.api.base-url:https://openapi.koreainvestment.com:9443}")
    private String baseUrl;

    @Value("${kis.api.account-prefix:}")
    private String accountPrefix;  // CANO (계좌번호 앞 8자리)

    @Value("${kis.api.account-suffix:01}")
    private String accountSuffix;  // ACNT_PRDT_CD (계좌번호 뒤 2자리)

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // 토큰 캐시
    private String accessToken;
    private LocalDateTime tokenExpireTime;

    public KoreaInvestmentService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * API 설정이 유효한지 확인
     */
    public boolean isConfigured() {
        return appKey != null && !appKey.isEmpty()
            && appSecret != null && !appSecret.isEmpty();
    }

    /**
     * Access Token 발급
     * - 토큰 유효시간: 24시간
     * - 만료 1시간 전에 갱신
     */
    public synchronized String getAccessToken() {
        // 토큰이 유효하면 재사용
        if (accessToken != null && tokenExpireTime != null
            && LocalDateTime.now().isBefore(tokenExpireTime.minusHours(1))) {
            return accessToken;
        }

        if (!isConfigured()) {
            log.warn("한국투자증권 API 키가 설정되지 않았습니다.");
            return null;
        }

        try {
            String url = baseUrl + "/oauth2/tokenP";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = new HashMap<>();
            body.put("grant_type", "client_credentials");
            body.put("appkey", appKey);
            body.put("appsecret", appSecret);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());

                if (root.has("access_token")) {
                    accessToken = root.get("access_token").asText();
                    // 토큰 만료시간 설정 (24시간)
                    tokenExpireTime = LocalDateTime.now().plusHours(24);
                    log.info("한국투자증권 Access Token 발급 성공");
                    return accessToken;
                } else {
                    String errorMsg = root.has("msg") ? root.get("msg").asText() : "Unknown error";
                    log.error("토큰 발급 실패: {}", errorMsg);
                }
            }
        } catch (Exception e) {
            log.error("한국투자증권 토큰 발급 실패: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 주식 현재가 조회
     * @param stockCode 종목코드 (6자리)
     * @return API 응답 JsonNode
     */
    public JsonNode getStockPrice(String stockCode) {
        String token = getAccessToken();
        if (token == null) {
            return null;
        }

        try {
            // 국내주식 현재가 조회 API
            String url = baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-price"
                    + "?FID_COND_MRKT_DIV_CODE=J"  // J: 주식, ETF, ETN
                    + "&FID_INPUT_ISCD=" + stockCode;

            HttpHeaders headers = createHeaders(token, "FHKST01010100");
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return objectMapper.readTree(response.getBody());
            }
        } catch (Exception e) {
            log.error("주식 현재가 조회 실패 [{}]: {}", stockCode, e.getMessage());
        }

        return null;
    }

    /**
     * 주식 기본 정보 조회 (종목명 등)
     * @param stockCode 종목코드
     * @return API 응답 JsonNode
     */
    public JsonNode getStockInfo(String stockCode) {
        String token = getAccessToken();
        if (token == null) {
            return null;
        }

        try {
            // 상품기본정보 조회 API
            String url = baseUrl + "/uapi/domestic-stock/v1/quotations/search-stock-info"
                    + "?PDNO=" + stockCode
                    + "&PRDT_TYPE_CD=300";  // 300: 주식

            HttpHeaders headers = createHeaders(token, "CTPF1002R");
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return objectMapper.readTree(response.getBody());
            }
        } catch (Exception e) {
            log.error("주식 기본정보 조회 실패 [{}]: {}", stockCode, e.getMessage());
        }

        return null;
    }

    /**
     * API 호출용 공통 헤더 생성
     */
    private HttpHeaders createHeaders(String token, String trId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("authorization", "Bearer " + token);
        headers.set("appkey", appKey);
        headers.set("appsecret", appSecret);
        headers.set("tr_id", trId);  // 거래ID
        headers.set("custtype", "P");  // 개인
        return headers;
    }

    /**
     * 투자자별 매매동향 조회 (외국인, 기관, 개인 순매수)
     * @param stockCode 종목코드 (6자리)
     * @return API 응답 JsonNode
     */
    public JsonNode getInvestorTrading(String stockCode) {
        String token = getAccessToken();
        if (token == null) {
            return null;
        }

        try {
            // 주식현재가 투자자 API (FHKST01010900)
            String url = baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-investor"
                    + "?FID_COND_MRKT_DIV_CODE=J"
                    + "&FID_INPUT_ISCD=" + stockCode;

            HttpHeaders headers = createHeaders(token, "FHKST01010900");
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return objectMapper.readTree(response.getBody());
            }
        } catch (Exception e) {
            log.error("투자자별 매매동향 조회 실패 [{}]: {}", stockCode, e.getMessage());
        }

        return null;
    }

    /**
     * 프로그램 매매 추이 조회
     * @param stockCode 종목코드 (6자리)
     * @return API 응답 JsonNode
     */
    public JsonNode getProgramTrading(String stockCode) {
        String token = getAccessToken();
        if (token == null) {
            return null;
        }

        try {
            // 주식현재가 프로그램매매 API (FHKST01010700)
            String url = baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-daily-programtrade"
                    + "?FID_COND_MRKT_DIV_CODE=J"
                    + "&FID_INPUT_ISCD=" + stockCode;

            HttpHeaders headers = createHeaders(token, "FHKST01010700");
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return objectMapper.readTree(response.getBody());
            }
        } catch (Exception e) {
            log.error("프로그램 매매 조회 실패 [{}]: {}", stockCode, e.getMessage());
        }

        return null;
    }

    /**
     * 지수 현재가 조회 (코스피, 코스닥)
     * @param indexCode 지수코드 (0001: 코스피, 1001: 코스닥)
     * @return API 응답 JsonNode
     */
    public JsonNode getIndexPrice(String indexCode) {
        String token = getAccessToken();
        if (token == null) {
            return null;
        }

        try {
            // 국내주식 업종기간별시세 (지수 조회)
            String url = baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-index-price"
                    + "?FID_COND_MRKT_DIV_CODE=U"  // U: 업종
                    + "&FID_INPUT_ISCD=" + indexCode;

            HttpHeaders headers = createHeaders(token, "FHPUP02100000");
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return objectMapper.readTree(response.getBody());
            }
        } catch (Exception e) {
            log.error("지수 현재가 조회 실패 [{}]: {}", indexCode, e.getMessage());
        }

        return null;
    }

    /**
     * 지수 분봉 데이터 조회
     * @param indexCode 지수코드
     * @return API 응답 JsonNode
     */
    public JsonNode getIndexMinuteChart(String indexCode) {
        String token = getAccessToken();
        if (token == null) {
            return null;
        }

        try {
            // 국내주식 업종분봉조회
            String url = baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-index-timeprice"
                    + "?FID_COND_MRKT_DIV_CODE=U"
                    + "&FID_INPUT_ISCD=" + indexCode
                    + "&FID_INPUT_HOUR_1=300";  // 300분

            HttpHeaders headers = createHeaders(token, "FHPUP02110200");
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return objectMapper.readTree(response.getBody());
            }
        } catch (Exception e) {
            log.error("지수 분봉 조회 실패 [{}]: {}", indexCode, e.getMessage());
        }

        return null;
    }

    /**
     * 주식 분봉 데이터 조회
     * @param stockCode 종목코드
     * @return API 응답 JsonNode
     */
    public JsonNode getStockMinuteChart(String stockCode) {
        String token = getAccessToken();
        if (token == null) {
            return null;
        }

        try {
            // 주식 당일 분봉 조회
            String url = baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice"
                    + "?FID_COND_MRKT_DIV_CODE=J"
                    + "&FID_INPUT_ISCD=" + stockCode
                    + "&FID_INPUT_HOUR_1=300"   // 300분
                    + "&FID_PW_DATA_INCU_YN=N";

            HttpHeaders headers = createHeaders(token, "FHKST03010200");
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return objectMapper.readTree(response.getBody());
            }
        } catch (Exception e) {
            log.error("주식 분봉 조회 실패 [{}]: {}", stockCode, e.getMessage());
        }

        return null;
    }

    /**
     * 앱키 반환 (외부에서 필요시)
     */
    public String getAppKey() {
        return appKey;
    }

    /**
     * 앱시크릿 반환 (외부에서 필요시)
     */
    public String getAppSecret() {
        return appSecret;
    }

    /**
     * 국내기관_외국인 매매종목가집계 조회
     * 외국인/기관 순매수/순매도 상위 종목 조회
     *
     * @param investorType 투자자 구분 (1=외국인, 2=기관계)
     * @param isBuy true=순매수상위, false=순매도상위
     * @param sortByAmount true=금액정렬, false=수량정렬
     * @return API 응답 JsonNode
     */
    public JsonNode getForeignInstitutionTotal(String investorType, boolean isBuy, boolean sortByAmount) {
        String token = getAccessToken();
        if (token == null) {
            log.error("토큰 발급 실패로 API 호출 불가");
            return null;
        }

        try {
            // 국내기관_외국인 매매종목가집계 API (FHPTJ04400000)
            String url = baseUrl + "/uapi/domestic-stock/v1/quotations/foreign-institution-total"
                    + "?FID_COND_MRKT_DIV_CODE=V"
                    + "&FID_COND_SCR_DIV_CODE=16449"
                    + "&FID_INPUT_ISCD=0000"  // 전체
                    + "&FID_DIV_CLS_CODE=" + (sortByAmount ? "1" : "0")  // 0=수량, 1=금액
                    + "&FID_RANK_SORT_CLS_CODE=" + (isBuy ? "0" : "1")   // 0=순매수상위, 1=순매도상위
                    + "&FID_ETC_CLS_CODE=" + investorType;  // 1=외국인, 2=기관계

            log.info("KIS API 호출: {}", url);

            HttpHeaders headers = createHeaders(token, "FHPTJ04400000");
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            log.info("KIS API 응답 상태: {}", response.getStatusCode());

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode result = objectMapper.readTree(response.getBody());
                log.info("KIS API 응답: rt_cd={}, output 크기={}",
                        result.has("rt_cd") ? result.get("rt_cd").asText() : "없음",
                        result.has("output") && result.get("output").isArray() ? result.get("output").size() : 0);
                return result;
            } else {
                log.error("KIS API 응답 실패: status={}, body={}", response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.error("외국인/기관 매매종목 조회 실패 [투자자:{}, 매수:{}]: {}",
                    investorType, isBuy, e.getMessage(), e);
        }

        return null;
    }

    /**
     * 외국인 순매수 상위 종목 조회 (편의 메서드)
     */
    public JsonNode getForeignNetBuyTop() {
        return getForeignInstitutionTotal("1", true, true);
    }

    /**
     * 외국인 순매도 상위 종목 조회 (편의 메서드)
     */
    public JsonNode getForeignNetSellTop() {
        return getForeignInstitutionTotal("1", false, true);
    }

    /**
     * 기관 순매수 상위 종목 조회 (편의 메서드)
     */
    public JsonNode getInstitutionNetBuyTop() {
        return getForeignInstitutionTotal("2", true, true);
    }

    /**
     * 기관 순매도 상위 종목 조회 (편의 메서드)
     */
    public JsonNode getInstitutionNetSellTop() {
        return getForeignInstitutionTotal("2", false, true);
    }

    /**
     * 주식 일봉 데이터 조회 (기술적 분석용)
     * KIS API FHKST03010100 - 국내주식기간별시세(일/주/월/년)
     *
     * @param stockCode 종목코드 (6자리)
     * @param days 조회할 일수 (최대 100일)
     * @return API 응답 JsonNode (output2에 일봉 데이터 배열)
     */
    public JsonNode getDailyPrices(String stockCode, int days) {
        String token = getAccessToken();
        if (token == null) {
            log.error("토큰 발급 실패로 일봉 조회 불가");
            return null;
        }

        try {
            // 시작일/종료일 계산
            java.time.LocalDate endDate = java.time.LocalDate.now();
            java.time.LocalDate startDate = endDate.minusDays(days + 30);  // 여유있게 조회
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");

            // 국내주식기간별시세 API (FHKST03010100)
            String url = baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"
                    + "?FID_COND_MRKT_DIV_CODE=J"           // J: 주식
                    + "&FID_INPUT_ISCD=" + stockCode         // 종목코드
                    + "&FID_INPUT_DATE_1=" + startDate.format(formatter)  // 시작일
                    + "&FID_INPUT_DATE_2=" + endDate.format(formatter)    // 종료일
                    + "&FID_PERIOD_DIV_CODE=D"              // D: 일봉
                    + "&FID_ORG_ADJ_PRC=0";                 // 0: 수정주가 미반영

            log.debug("일봉 조회 API 호출: stockCode={}, days={}", stockCode, days);

            HttpHeaders headers = createHeaders(token, "FHKST03010100");
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode result = objectMapper.readTree(response.getBody());

                // 성공 여부 확인
                String rtCd = result.has("rt_cd") ? result.get("rt_cd").asText() : "";
                if (!"0".equals(rtCd)) {
                    String msg = result.has("msg1") ? result.get("msg1").asText() : "Unknown error";
                    log.warn("일봉 조회 실패 [{}]: {}", stockCode, msg);
                    return null;
                }

                log.debug("일봉 조회 성공 [{}]: {} 건", stockCode,
                        result.has("output2") && result.get("output2").isArray() ?
                                result.get("output2").size() : 0);
                return result;
            }
        } catch (Exception e) {
            log.error("일봉 조회 실패 [{}]: {}", stockCode, e.getMessage());
        }

        return null;
    }

    /**
     * 일봉 데이터에서 종가 리스트 추출 (편의 메서드)
     * @param stockCode 종목코드
     * @param days 조회할 일수
     * @return 종가 리스트 (최신순) - 조회 실패시 빈 리스트
     */
    public java.util.List<java.math.BigDecimal> getDailyClosePrices(String stockCode, int days) {
        java.util.List<java.math.BigDecimal> prices = new java.util.ArrayList<>();

        JsonNode response = getDailyPrices(stockCode, days);
        if (response == null || !response.has("output2")) {
            return prices;
        }

        JsonNode output2 = response.get("output2");
        if (!output2.isArray()) {
            return prices;
        }

        // output2의 각 항목에서 종가(stck_clpr) 추출
        for (JsonNode item : output2) {
            if (item.has("stck_clpr")) {
                try {
                    String priceStr = item.get("stck_clpr").asText();
                    java.math.BigDecimal price = new java.math.BigDecimal(priceStr);
                    if (price.compareTo(java.math.BigDecimal.ZERO) > 0) {
                        prices.add(price);
                    }
                } catch (NumberFormatException e) {
                    // 무시
                }
            }
        }

        log.debug("종가 추출 완료 [{}]: {} 건", stockCode, prices.size());
        return prices;
    }

    /**
     * 일봉 데이터에서 OHLCV 리스트 추출 (MFI 계산용)
     * @param stockCode 종목코드
     * @param days 조회할 일수
     * @return OHLCV 데이터 리스트 (최신순) - 조회 실패시 빈 리스트
     */
    public java.util.List<OhlcvData> getDailyOhlcv(String stockCode, int days) {
        java.util.List<OhlcvData> ohlcvList = new java.util.ArrayList<>();

        JsonNode response = getDailyPrices(stockCode, days);
        if (response == null || !response.has("output2")) {
            return ohlcvList;
        }

        JsonNode output2 = response.get("output2");
        if (!output2.isArray()) {
            return ohlcvList;
        }

        // output2의 각 항목에서 OHLCV 추출
        for (JsonNode item : output2) {
            try {
                java.math.BigDecimal open = extractBigDecimal(item, "stck_oprc");
                java.math.BigDecimal high = extractBigDecimal(item, "stck_hgpr");
                java.math.BigDecimal low = extractBigDecimal(item, "stck_lwpr");
                java.math.BigDecimal close = extractBigDecimal(item, "stck_clpr");
                java.math.BigDecimal volume = extractBigDecimal(item, "acml_vol");

                if (isValidOhlcv(open, high, low, close, volume)) {
                    ohlcvList.add(new OhlcvData(open, high, low, close, volume));
                }
            } catch (Exception e) {
                // 무시하고 다음 항목 처리
            }
        }

        log.debug("OHLCV 추출 완료 [{}]: {} 건", stockCode, ohlcvList.size());
        return ohlcvList;
    }

    private java.math.BigDecimal extractBigDecimal(JsonNode item, String fieldName) {
        if (item.has(fieldName)) {
            String value = item.get(fieldName).asText();
            if (value != null && !value.isEmpty()) {
                return new java.math.BigDecimal(value);
            }
        }
        return null;
    }

    private boolean isValidOhlcv(java.math.BigDecimal open, java.math.BigDecimal high,
                                  java.math.BigDecimal low, java.math.BigDecimal close,
                                  java.math.BigDecimal volume) {
        return close != null && close.compareTo(java.math.BigDecimal.ZERO) > 0
                && high != null && high.compareTo(java.math.BigDecimal.ZERO) > 0
                && low != null && low.compareTo(java.math.BigDecimal.ZERO) > 0
                && volume != null && volume.compareTo(java.math.BigDecimal.ZERO) >= 0;
    }

    /**
     * OHLCV 데이터 클래스
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class OhlcvData {
        private java.math.BigDecimal open;
        private java.math.BigDecimal high;
        private java.math.BigDecimal low;
        private java.math.BigDecimal close;
        private java.math.BigDecimal volume;
    }

    // ========== 실전 매매 API ==========

    /**
     * 실전 매매 API 사용 가능 여부 확인
     */
    public boolean isRealTradingConfigured() {
        return isConfigured()
            && accountPrefix != null && !accountPrefix.isEmpty()
            && accountSuffix != null && !accountSuffix.isEmpty();
    }

    /**
     * 주식 현금 매수 주문 (시장가)
     * KIS API: TTTC0802U (국내주식 현금 매수)
     *
     * @param stockCode 종목코드 (6자리)
     * @param quantity 매수수량
     * @return 주문 결과 JsonNode (주문번호 포함)
     */
    public JsonNode buyStock(String stockCode, int quantity) {
        return placeOrder(stockCode, quantity, "TTTC0802U", "buy");
    }

    /**
     * 주식 현금 매도 주문 (시장가)
     * KIS API: TTTC0801U (국내주식 현금 매도)
     *
     * @param stockCode 종목코드 (6자리)
     * @param quantity 매도수량
     * @return 주문 결과 JsonNode (주문번호 포함)
     */
    public JsonNode sellStock(String stockCode, int quantity) {
        return placeOrder(stockCode, quantity, "TTTC0801U", "sell");
    }

    /**
     * 주식 주문 공통 처리
     */
    private JsonNode placeOrder(String stockCode, int quantity, String trId, String orderType) {
        String token = getAccessToken();
        if (token == null) {
            log.error("[실전매매] 토큰 발급 실패로 {} 불가", orderType);
            return null;
        }

        if (!isRealTradingConfigured()) {
            log.error("[실전매매] 계좌 정보가 설정되지 않았습니다.");
            return null;
        }

        try {
            String url = baseUrl + "/uapi/domestic-stock/v1/trading/order-cash";

            HttpHeaders headers = createHeaders(token, trId);

            // 요청 바디
            Map<String, String> body = new HashMap<>();
            body.put("CANO", accountPrefix);              // 계좌번호 앞 8자리
            body.put("ACNT_PRDT_CD", accountSuffix);      // 계좌상품코드 (01)
            body.put("PDNO", stockCode);                  // 종목코드
            body.put("ORD_DVSN", "01");                   // 주문구분: 01=시장가
            body.put("ORD_QTY", String.valueOf(quantity)); // 주문수량
            body.put("ORD_UNPR", "0");                    // 주문단가 (시장가는 0)

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            log.info("[실전매매] {} 주문 요청: {} x {}", orderType.toUpperCase(), stockCode, quantity);

            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode result = objectMapper.readTree(response.getBody());

                String rtCd = result.has("rt_cd") ? result.get("rt_cd").asText() : "";
                String msg = result.has("msg1") ? result.get("msg1").asText() : "";

                if ("0".equals(rtCd)) {
                    String orderNo = result.has("output") && result.get("output").has("ODNO")
                            ? result.get("output").get("ODNO").asText() : "";
                    log.info("[실전매매] {} 주문 성공: {} x {}, 주문번호: {}",
                            orderType.toUpperCase(), stockCode, quantity, orderNo);
                } else {
                    log.error("[실전매매] {} 주문 실패: {} - {}", orderType.toUpperCase(), stockCode, msg);
                }

                return result;
            }
        } catch (Exception e) {
            log.error("[실전매매] {} 주문 실패 [{}]: {}", orderType.toUpperCase(), stockCode, e.getMessage());
        }

        return null;
    }

    /**
     * 주식 잔고 조회 (보유종목 및 예수금)
     * KIS API: TTTC8434R (국내주식 잔고조회)
     *
     * @return 잔고 정보 JsonNode
     */
    public JsonNode getBalance() {
        String token = getAccessToken();
        if (token == null) {
            log.error("[실전매매] 토큰 발급 실패로 잔고조회 불가");
            return null;
        }

        if (!isRealTradingConfigured()) {
            log.error("[실전매매] 계좌 정보가 설정되지 않았습니다.");
            return null;
        }

        try {
            String url = baseUrl + "/uapi/domestic-stock/v1/trading/inquire-balance"
                    + "?CANO=" + accountPrefix
                    + "&ACNT_PRDT_CD=" + accountSuffix
                    + "&AFHR_FLPR_YN=N"        // 시간외단일가여부
                    + "&OFL_YN="               // 오프라인여부
                    + "&INQR_DVSN=02"          // 조회구분: 02=일반조회
                    + "&UNPR_DVSN=01"          // 단가구분
                    + "&FUND_STTL_ICLD_YN=N"   // 펀드결제분포함여부
                    + "&FNCG_AMT_AUTO_RDPT_YN=N" // 융자금액자동상환여부
                    + "&PRCS_DVSN=00"          // 처리구분
                    + "&CTX_AREA_FK100="       // 연속조회키
                    + "&CTX_AREA_NK100=";      // 연속조회키

            HttpHeaders headers = createHeaders(token, "TTTC8434R");
            HttpEntity<String> request = new HttpEntity<>(headers);

            log.debug("[실전매매] 잔고 조회 요청");

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode result = objectMapper.readTree(response.getBody());

                String rtCd = result.has("rt_cd") ? result.get("rt_cd").asText() : "";
                if ("0".equals(rtCd)) {
                    log.debug("[실전매매] 잔고 조회 성공");
                } else {
                    String msg = result.has("msg1") ? result.get("msg1").asText() : "";
                    log.error("[실전매매] 잔고 조회 실패: {}", msg);
                }

                return result;
            }
        } catch (Exception e) {
            log.error("[실전매매] 잔고 조회 실패: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 주문 결과 DTO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OrderResult {
        private boolean success;
        private String orderNo;       // 주문번호
        private String stockCode;
        private int quantity;
        private String message;
        private String errorCode;
    }

    /**
     * 잔고 정보 DTO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class BalanceInfo {
        private java.math.BigDecimal depositBalance;     // 예수금총액
        private java.math.BigDecimal availableBalance;   // 출금가능금액
        private java.math.BigDecimal totalEvaluation;    // 평가금액합계
        private java.math.BigDecimal totalProfitLoss;    // 평가손익합계
        private java.util.List<HoldingStock> holdings;   // 보유종목 목록
    }

    /**
     * 보유종목 정보 DTO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class HoldingStock {
        private String stockCode;
        private String stockName;
        private int quantity;                            // 보유수량
        private java.math.BigDecimal averagePrice;       // 평균매입가
        private java.math.BigDecimal currentPrice;       // 현재가
        private java.math.BigDecimal profitLoss;         // 평가손익
        private java.math.BigDecimal profitRate;         // 수익률
    }

    /**
     * 잔고 조회 결과를 BalanceInfo DTO로 변환
     */
    public BalanceInfo parseBalance(JsonNode balanceResponse) {
        if (balanceResponse == null) {
            return null;
        }

        try {
            BalanceInfo info = BalanceInfo.builder()
                    .holdings(new java.util.ArrayList<>())
                    .build();

            // output2에서 계좌 정보 추출
            if (balanceResponse.has("output2") && balanceResponse.get("output2").isArray()) {
                JsonNode output2 = balanceResponse.get("output2");
                if (output2.size() > 0) {
                    JsonNode account = output2.get(0);
                    info.setDepositBalance(extractBigDecimal(account, "dnca_tot_amt"));      // 예수금총액
                    info.setAvailableBalance(extractBigDecimal(account, "nxdy_excc_amt"));   // 익일정산금액
                    info.setTotalEvaluation(extractBigDecimal(account, "tot_evlu_amt"));     // 총평가금액
                    info.setTotalProfitLoss(extractBigDecimal(account, "evlu_pfls_smtl_amt")); // 평가손익합계
                }
            }

            // output1에서 보유종목 추출
            if (balanceResponse.has("output1") && balanceResponse.get("output1").isArray()) {
                JsonNode output1 = balanceResponse.get("output1");
                for (JsonNode stock : output1) {
                    String stockCode = stock.has("pdno") ? stock.get("pdno").asText() : "";
                    if (stockCode.isEmpty()) continue;

                    HoldingStock holding = HoldingStock.builder()
                            .stockCode(stockCode)
                            .stockName(stock.has("prdt_name") ? stock.get("prdt_name").asText() : "")
                            .quantity(stock.has("hldg_qty") ? stock.get("hldg_qty").asInt() : 0)
                            .averagePrice(extractBigDecimal(stock, "pchs_avg_pric"))
                            .currentPrice(extractBigDecimal(stock, "prpr"))
                            .profitLoss(extractBigDecimal(stock, "evlu_pfls_amt"))
                            .profitRate(extractBigDecimal(stock, "evlu_pfls_rt"))
                            .build();

                    if (holding.getQuantity() > 0) {
                        info.getHoldings().add(holding);
                    }
                }
            }

            return info;
        } catch (Exception e) {
            log.error("[실전매매] 잔고 파싱 실패: {}", e.getMessage());
            return null;
        }
    }
}
