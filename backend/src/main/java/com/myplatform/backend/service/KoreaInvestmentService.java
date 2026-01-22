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

            HttpHeaders headers = createHeaders(token, "FHPTJ04400000");
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return objectMapper.readTree(response.getBody());
            }
        } catch (Exception e) {
            log.error("외국인/기관 매매종목 조회 실패 [투자자:{}, 매수:{}]: {}",
                    investorType, isBuy, e.getMessage());
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
}
