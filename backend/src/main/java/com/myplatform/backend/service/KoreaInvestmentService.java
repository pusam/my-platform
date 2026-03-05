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

    // 토큰 발급 실패 시 쿨다운 (Rate Limit 방지)
    private LocalDateTime tokenCooldownUntil;
    private static final int TOKEN_COOLDOWN_SECONDS = 65;  // 1분 + 여유 5초

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
     * 토큰이 현재 사용 가능한지 확인 (쿨다운 포함)
     * - 토큰 발급 시도 없이 빠르게 상태만 확인
     */
    public boolean isTokenAvailable() {
        // 이미 유효한 토큰이 있으면 true
        if (accessToken != null && tokenExpireTime != null
            && LocalDateTime.now().isBefore(tokenExpireTime.minusHours(1))) {
            return true;
        }
        // 쿨다운 중이면 false
        if (tokenCooldownUntil != null && LocalDateTime.now().isBefore(tokenCooldownUntil)) {
            return false;
        }
        // 설정이 안 되어 있으면 false
        return isConfigured();
    }

    /**
     * Access Token 발급
     * - 토큰 유효시간: 24시간
     * - 만료 1시간 전에 갱신
     * - Rate Limit 방지를 위한 쿨다운 적용
     */
    public synchronized String getAccessToken() {
        // 토큰이 유효하면 재사용
        if (accessToken != null && tokenExpireTime != null
            && LocalDateTime.now().isBefore(tokenExpireTime.minusHours(1))) {
            return accessToken;
        }

        // 쿨다운 중이면 null 반환 (Rate Limit 방지)
        if (tokenCooldownUntil != null && LocalDateTime.now().isBefore(tokenCooldownUntil)) {
            log.debug("토큰 발급 쿨다운 중 ({}까지 대기)", tokenCooldownUntil);
            return null;
        }

        if (!isConfigured()) {
            log.warn("한국투자증권 API 키가 설정되지 않았습니다. (appKey 길이: {}, appSecret 길이: {})",
                    appKey != null ? appKey.length() : 0,
                    appSecret != null ? appSecret.length() : 0);
            return null;
        }

        // 마스킹된 키로 디버그 로깅
        String maskedKey = appKey.length() > 4
                ? appKey.substring(0, 4) + "****" : "****";

        try {
            String url = baseUrl + "/oauth2/tokenP";
            log.info("KIS 토큰 발급 시도 - baseUrl: {}, appKey: {}", baseUrl, maskedKey);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = new HashMap<>();
            body.put("grant_type", "client_credentials");
            body.put("appkey", appKey);
            body.put("appsecret", appSecret);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            log.info("KIS 토큰 응답 - HTTP {}, body 길이: {}",
                    response.getStatusCode(),
                    response.getBody() != null ? response.getBody().length() : 0);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());

                if (root.has("access_token")) {
                    accessToken = root.get("access_token").asText();
                    // 토큰 만료시간 설정 (24시간)
                    tokenExpireTime = LocalDateTime.now().plusHours(24);
                    // 쿨다운 해제
                    tokenCooldownUntil = null;
                    log.info("KIS Access Token 발급 성공 (만료: {})", tokenExpireTime);
                    return accessToken;
                } else {
                    // 에러 상세 로깅
                    String errorCode = root.has("error_code") ? root.get("error_code").asText() : "";
                    String errorMsg = root.has("msg") ? root.get("msg").asText() : "";
                    String errorDesc = root.has("error_description") ? root.get("error_description").asText() : "";
                    log.error("KIS 토큰 발급 실패 - code: {}, msg: {}, desc: {}, 전체 응답: {}",
                            errorCode, errorMsg, errorDesc, response.getBody());
                    tokenCooldownUntil = LocalDateTime.now().plusSeconds(TOKEN_COOLDOWN_SECONDS);
                    log.info("KIS 토큰 쿨다운 설정: {}까지 대기", tokenCooldownUntil);
                }
            } else {
                log.error("KIS 토큰 비정상 응답 - HTTP {}", response.getStatusCode());
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            log.error("KIS 토큰 발급 HTTP {} - appKey: {}, baseUrl: {}, 응답: {}",
                    statusCode, maskedKey, baseUrl, responseBody, e);
            if (statusCode == 401) {
                log.error("KIS 토큰 401 Unauthorized - appKey/appSecret 확인 필요");
            } else if (statusCode == 403) {
                log.error("KIS 토큰 403 Forbidden - API 권한 또는 IP 접근 제한 확인");
            } else if (statusCode == 429) {
                log.error("KIS 토큰 429 Too Many Requests - 분당 요청 한도 초과");
            }
            tokenCooldownUntil = LocalDateTime.now().plusSeconds(TOKEN_COOLDOWN_SECONDS);
            log.info("KIS 토큰 쿨다운 설정: {}초 ({}까지)", TOKEN_COOLDOWN_SECONDS, tokenCooldownUntil);
        } catch (Exception e) {
            log.error("KIS 토큰 발급 예외 - appKey: {}, baseUrl: {}", maskedKey, baseUrl, e);
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("Connection refused") || msg.contains("Connect timed out")) {
                tokenCooldownUntil = LocalDateTime.now().plusSeconds(TOKEN_COOLDOWN_SECONDS * 2);
                log.error("KIS API 서버 연결 불가 - {}초 쿨다운", TOKEN_COOLDOWN_SECONDS * 2);
            }
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
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            // KIS API에서 해당 엔드포인트 폐지/변경 시 404 반환 → 조용히 무시
            log.debug("프로그램 매매 API 미지원 (404) [{}] - 네이버 투자자 매매동향 폴백 사용", stockCode);
        } catch (Exception e) {
            log.warn("프로그램 매매 조회 실패 [{}]: {}", stockCode, e.getMessage());
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
     * 주식 분봉 데이터 조회 (당일 1분봉)
     * KIS API: FHKST03010200 - 주식당일분봉조회
     *
     * @param stockCode 종목코드
     * @return API 응답 JsonNode (output2에 분봉 데이터 배열)
     */
    public JsonNode getStockMinuteChart(String stockCode) {
        String token = getAccessToken();
        if (token == null) {
            return null;
        }

        try {
            // 현재 시간 또는 장 마감 시간 (HHMMSS 형식)
            java.time.LocalTime now = java.time.LocalTime.now();
            java.time.LocalTime marketClose = java.time.LocalTime.of(15, 30);
            java.time.LocalTime queryTime = now.isAfter(marketClose) ? marketClose : now;
            String timeStr = String.format("%02d%02d%02d", queryTime.getHour(), queryTime.getMinute(), 0);

            // 주식 당일 분봉 조회 API
            // FID_INPUT_HOUR_1: 조회 시작 시간 (HHMMSS) - 현재시간 기준으로 과거 데이터 조회
            // FID_PW_DATA_INCU_YN: 과거 데이터 포함 여부 (Y: 이전 데이터 포함)
            String url = baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice"
                    + "?FID_COND_MRKT_DIV_CODE=J"       // J: 주식, ETF, ETN
                    + "&FID_INPUT_ISCD=" + stockCode    // 종목코드
                    + "&FID_INPUT_HOUR_1=" + timeStr    // 조회시간 (HHMMSS)
                    + "&FID_PW_DATA_INCU_YN=Y";         // 과거 데이터 포함

            HttpHeaders headers = createHeaders(token, "FHKST03010200");
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return objectMapper.readTree(response.getBody());
            } else {
                log.warn("분봉 API HTTP 에러 [{}]: status={}", stockCode, response.getStatusCode());
            }
        } catch (Exception e) {
            log.warn("분봉 API 예외 [{}]: {}", stockCode, e.getMessage());
        }

        return null;
    }

    /**
     * 주식 일봉 차트 조회
     * RSI 다이버전스 등 기술적 분석에 사용
     *
     * @param stockCode 종목코드
     * @param days 조회할 일수 (최대 100일)
     * @return API 응답 JsonNode (output2에 일봉 데이터 배열)
     */
    public JsonNode getStockDailyChart(String stockCode, int days) {
        String token = getAccessToken();
        if (token == null) {
            return null;
        }

        try {
            // 종료일: 오늘
            java.time.LocalDate endDate = java.time.LocalDate.now();
            // 시작일: days일 전
            java.time.LocalDate startDate = endDate.minusDays(days + 30);  // 주말/공휴일 고려 여유분

            String endDateStr = endDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            String startDateStr = startDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);

            // 주식 일별 시세 조회 API (FHKST03010100)
            String url = baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"
                    + "?FID_COND_MRKT_DIV_CODE=J"       // J: 주식
                    + "&FID_INPUT_ISCD=" + stockCode    // 종목코드
                    + "&FID_INPUT_DATE_1=" + startDateStr  // 시작일
                    + "&FID_INPUT_DATE_2=" + endDateStr    // 종료일
                    + "&FID_PERIOD_DIV_CODE=D"          // D: 일봉
                    + "&FID_ORG_ADJ_PRC=0";             // 0: 수정주가 미반영

            HttpHeaders headers = createHeaders(token, "FHKST03010100");
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode result = objectMapper.readTree(response.getBody());
                log.debug("일봉 조회 완료 [{}]: {}~{}", stockCode, startDateStr, endDateStr);
                return result;
            } else {
                log.warn("일봉 API HTTP 에러 [{}]: status={}", stockCode, response.getStatusCode());
            }
        } catch (Exception e) {
            log.warn("일봉 API 예외 [{}]: {}", stockCode, e.getMessage());
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
                int outputSize = result.has("output") && result.get("output").isArray() ? result.get("output").size() : 0;
                log.info("KIS API 응답: rt_cd={}, output 크기={}",
                        result.has("rt_cd") ? result.get("rt_cd").asText() : "없음", outputSize);
                if (outputSize == 0) {
                    log.warn("KIS API 빈 응답 [투자자:{}] msg1={}, raw={}",
                            investorType,
                            result.has("msg1") ? result.get("msg1").asText() : "없음",
                            response.getBody().length() > 500 ? response.getBody().substring(0, 500) : response.getBody());
                }
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
     * 거래량 급증 종목 조회 (모멘텀 스크리너용)
     * KIS API: FHPST01710000 - 거래량급등종목
     *
     * 전일 대비 거래량이 급증한 종목 상위 30개 조회
     * @return API 응답 JsonNode (output 배열에 종목 정보)
     */
    public JsonNode getVolumeRankStocks() {
        String token = getAccessToken();
        if (token == null) {
            log.error("[거래량급증] 토큰 발급 실패");
            return null;
        }

        try {
            // 거래량급등종목 API (FHPST01710000)
            String url = baseUrl + "/uapi/domestic-stock/v1/quotations/volume-rank"
                    + "?FID_COND_MRKT_DIV_CODE=J"       // J: 주식
                    + "&FID_COND_SCR_DIV_CODE=20171"    // 화면번호
                    + "&FID_INPUT_ISCD=0000"            // 전체
                    + "&FID_DIV_CLS_CODE=0"             // 전체
                    + "&FID_BLNG_CLS_CODE=0"            // 전체
                    + "&FID_TRGT_CLS_CODE=111111111"    // 대상 구분
                    + "&FID_TRGT_EXLS_CLS_CODE=000000"  // 제외 구분
                    + "&FID_INPUT_PRICE_1="             // 시작가격
                    + "&FID_INPUT_PRICE_2="             // 종료가격
                    + "&FID_VOL_CNT="                   // 거래량 조건
                    + "&FID_INPUT_DATE_1=";             // 기준일

            log.info("[거래량급증] API 호출 시작");

            HttpHeaders headers = createHeaders(token, "FHPST01710000");
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode result = objectMapper.readTree(response.getBody());

                String rtCd = result.has("rt_cd") ? result.get("rt_cd").asText() : "";
                if ("0".equals(rtCd)) {
                    int count = result.has("output") && result.get("output").isArray()
                            ? result.get("output").size() : 0;
                    log.info("[거래량급증] 조회 성공 - {}건", count);
                } else {
                    String msg = result.has("msg1") ? result.get("msg1").asText() : "";
                    log.warn("[거래량급증] API 오류: {} - {}", rtCd, msg);
                }

                return result;
            }
        } catch (Exception e) {
            log.error("[거래량급증] 조회 실패: {}", e.getMessage(), e);
        }

        return null;
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

        // output2의 각 항목에서 OHLCV + 날짜 추출
        for (JsonNode item : output2) {
            try {
                java.math.BigDecimal open = extractBigDecimal(item, "stck_oprc");
                java.math.BigDecimal high = extractBigDecimal(item, "stck_hgpr");
                java.math.BigDecimal low = extractBigDecimal(item, "stck_lwpr");
                java.math.BigDecimal close = extractBigDecimal(item, "stck_clpr");
                java.math.BigDecimal volume = extractBigDecimal(item, "acml_vol");

                // 거래일자 추출 (stck_bsop_date: YYYYMMDD 형식)
                java.time.LocalDate tradeDate = null;
                if (item.has("stck_bsop_date")) {
                    String dateStr = item.get("stck_bsop_date").asText();
                    if (dateStr != null && dateStr.length() == 8) {
                        tradeDate = java.time.LocalDate.parse(dateStr,
                                java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                    }
                }

                if (isValidOhlcv(open, high, low, close, volume)) {
                    OhlcvData data = new OhlcvData(open, high, low, close, volume);
                    data.setTradeDate(tradeDate);
                    ohlcvList.add(data);
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
     * OHLCV 데이터 클래스 (날짜 포함)
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class OhlcvData {
        private java.math.BigDecimal open;
        private java.math.BigDecimal high;
        private java.math.BigDecimal low;
        private java.math.BigDecimal close;
        private java.math.BigDecimal volume;
        private java.time.LocalDate tradeDate;  // 거래일자 추가

        // 날짜 없는 생성자 (기존 호환성)
        public OhlcvData(java.math.BigDecimal open, java.math.BigDecimal high,
                         java.math.BigDecimal low, java.math.BigDecimal close,
                         java.math.BigDecimal volume) {
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
            this.tradeDate = null;
        }
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
     * @param price 주문단가 (지정가 주문)
     * @return 주문 결과 JsonNode (주문번호 포함)
     */
    public JsonNode buyStock(String stockCode, int quantity, java.math.BigDecimal price) {
        return placeOrder(stockCode, quantity, price, "TTTC0802U", "buy");
    }

    /**
     * 주식 현금 매도 주문 (지정가)
     * KIS API: TTTC0801U (국내주식 현금 매도)
     *
     * @param stockCode 종목코드 (6자리)
     * @param quantity 매도수량
     * @param price 주문단가 (지정가 주문)
     * @return 주문 결과 JsonNode (주문번호 포함)
     */
    public JsonNode sellStock(String stockCode, int quantity, java.math.BigDecimal price) {
        return placeOrder(stockCode, quantity, price, "TTTC0801U", "sell");
    }

    /**
     * 주식 주문 공통 처리 (지정가)
     * ORD_DVSN: 00=지정가, 01=시장가
     * 지정가 주문으로 슬리피지(체결 오차) 방지
     */
    private JsonNode placeOrder(String stockCode, int quantity, java.math.BigDecimal price, String trId, String orderType) {
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

            // 요청 바디 (지정가 주문)
            Map<String, String> body = new HashMap<>();
            body.put("CANO", accountPrefix);              // 계좌번호 앞 8자리
            body.put("ACNT_PRDT_CD", accountSuffix);      // 계좌상품코드 (01)
            body.put("PDNO", stockCode);                  // 종목코드
            body.put("ORD_DVSN", "00");                   // 주문구분: 00=지정가 (슬리피지 방지)
            body.put("ORD_QTY", String.valueOf(quantity)); // 주문수량
            body.put("ORD_UNPR", price.setScale(0, java.math.RoundingMode.DOWN).toString()); // 주문단가

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            log.info("[실전매매] 지정가 {} 주문 요청: {} x {} @ {}원", orderType.toUpperCase(), stockCode, quantity, price);

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
