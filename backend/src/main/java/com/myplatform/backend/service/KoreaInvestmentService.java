package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.core.util.DateTimeUtil;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

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
    private final KisApiRateLimiter rateLimiter;

    // 토큰 캐시
    private String accessToken;
    private LocalDateTime tokenExpireTime;

    // 토큰 발급 실패 시 쿨다운 (Rate Limit 방지)
    private LocalDateTime tokenCooldownUntil;
    private static final int TOKEN_COOLDOWN_SECONDS = 65;  // 1분 + 여유 5초

    public KoreaInvestmentService(RestTemplate restTemplate, ObjectMapper objectMapper,
                                  KisApiRateLimiter rateLimiter) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Rate Limiter 인스턴스 반환 (외부에서 우선순위 지정 시 사용)
     */
    public KisApiRateLimiter getRateLimiter() {
        return rateLimiter;
    }

    /**
     * Rate-limited API 호출 래퍼 (기본 NORMAL 우선순위)
     */
    public <T> T executeWithRateLimit(Supplier<T> apiCall) {
        return rateLimiter.execute(KisApiRateLimiter.Priority.NORMAL, apiCall);
    }

    /**
     * Rate-limited API 호출 래퍼 (우선순위 지정)
     */
    public <T> T executeWithRateLimit(KisApiRateLimiter.Priority priority, Supplier<T> apiCall) {
        return rateLimiter.execute(priority, apiCall);
    }

    /**
     * KIS rate-limit 에러(EGW00201 / HTTP 429 / "초당 거래건수 초과")면 RuntimeException 으로
     * 던져 KisApiRateLimiter 가 재시도하도록 한다. 그 외 에러는 호출부의 기존 처리 유지.
     *
     * 각 internal 메서드의 catch 블록 맨 앞에서 호출하면 기존 로그/null 리턴은 그대로 둔 채
     * rate-limit 에러만 선별적으로 재시도를 태울 수 있다.
     */
    private void rethrowIfRateLimit(Exception e) {
        if (e instanceof org.springframework.web.client.HttpServerErrorException) {
            String body = ((org.springframework.web.client.HttpServerErrorException) e).getResponseBodyAsString();
            if (body != null && (body.contains("EGW00201") || body.contains("초당 거래건수"))) {
                throw new RuntimeException("KIS rate limit (EGW00201): " + body, e);
            }
        }
        if (e instanceof org.springframework.web.client.HttpClientErrorException) {
            int status = ((org.springframework.web.client.HttpClientErrorException) e).getStatusCode().value();
            if (status == 429) {
                throw new RuntimeException("KIS rate limit (HTTP 429)", e);
            }
        }
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
            && DateTimeUtil.kstNow().isBefore(tokenExpireTime.minusHours(1))) {
            return true;
        }
        // 쿨다운 중이면 false
        if (tokenCooldownUntil != null && DateTimeUtil.kstNow().isBefore(tokenCooldownUntil)) {
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
            && DateTimeUtil.kstNow().isBefore(tokenExpireTime.minusHours(1))) {
            return accessToken;
        }

        // 쿨다운 중이면 null 반환 (Rate Limit 방지)
        if (tokenCooldownUntil != null && DateTimeUtil.kstNow().isBefore(tokenCooldownUntil)) {
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
                    tokenExpireTime = DateTimeUtil.kstNow().plusHours(24);
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
                    tokenCooldownUntil = DateTimeUtil.kstNow().plusSeconds(TOKEN_COOLDOWN_SECONDS);
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
            tokenCooldownUntil = DateTimeUtil.kstNow().plusSeconds(TOKEN_COOLDOWN_SECONDS);
            log.info("KIS 토큰 쿨다운 설정: {}초 ({}까지)", TOKEN_COOLDOWN_SECONDS, tokenCooldownUntil);
        } catch (Exception e) {
            log.error("KIS 토큰 발급 예외 - appKey: {}, baseUrl: {}", maskedKey, baseUrl, e);
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("Connection refused") || msg.contains("Connect timed out")) {
                tokenCooldownUntil = DateTimeUtil.kstNow().plusSeconds(TOKEN_COOLDOWN_SECONDS * 2);
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
        return getStockPriceWithPriority(stockCode, KisApiRateLimiter.Priority.NORMAL);
    }

    /**
     * 주식 현재가 조회 (우선순위 지정).
     * CircuitBreaker — KIS 연속 실패 시 OPEN 으로 빠른 실패 + fallback(null) → 매매 봇이 stale 시세 의존 방지.
     * Retry — 일시적 IO/timeout 자동 재시도 (300ms·600ms·1200ms).
     */
    @CircuitBreaker(name = "kisApi", fallbackMethod = "getStockPriceFallback")
    @Retry(name = "kisApi")
    public JsonNode getStockPriceWithPriority(String stockCode, KisApiRateLimiter.Priority priority) {
        return rateLimiter.execute(priority, () -> getStockPriceInternal(stockCode));
    }

    /** CircuitBreaker OPEN / 모든 retry 실패 시 fallback. 호출자는 null 체크로 거래 중단 판단. */
    @SuppressWarnings("unused")
    private JsonNode getStockPriceFallback(String stockCode, KisApiRateLimiter.Priority priority, Throwable t) {
        log.warn("[KIS CircuitBreaker] 시세 조회 실패 fallback — code: {}, cause: {}",
                stockCode, t.getClass().getSimpleName() + ": " + t.getMessage());
        return null;
    }

    /**
     * 진단용 시장구분 지정 현재가 조회 — ×10 배수오염 발생 시 UN(통합) vs J(KRX 단독) raw 대조에 사용.
     * <p>이상치 종목에서만 1회 호출되므로 LOW 우선순위(정상 매매 호출 비간섭). CircuitBreaker 미적용 —
     * 호출부({@code StockPriceService.warnIfPriceOutlier})가 null/예외를 모두 흡수하며 본 시세 흐름엔 영향 없음.
     * UN/J/NX 모두 동일 TR(FHKST01010100)·동일 output 구조라 호출부 파싱을 그대로 재사용한다.
     */
    public JsonNode getStockPriceByMarketDiv(String stockCode, String marketDiv) {
        return rateLimiter.execute(KisApiRateLimiter.Priority.LOW,
                () -> getStockPriceInternal(stockCode, marketDiv));
    }

    private JsonNode getStockPriceInternal(String stockCode) {
        return getStockPriceInternal(stockCode, "UN");
    }

    /**
     * 현재가 조회 (시장구분 지정). 기본 경로는 UN(통합) — {@link #getStockPriceInternal(String)}.
     * marketDiv: UN(KRX+NXT 통합) / J(KRX 단독) / NX(NXT 단독). 8-20시 거래시간 전체 커버는 UN.
     */
    private JsonNode getStockPriceInternal(String stockCode, String marketDiv) {
        String token = getAccessToken();
        if (token == null) {
            return null;
        }

        try {
            // 국내주식 현재가 조회 API
            // 시장구분: UN(통합) — KRX + NXT 통합시세, 8-20시 거래시간 전체 커버
            // (J=KRX 단독, NX=NXT 단독)
            String url = baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-price"
                    + "?FID_COND_MRKT_DIV_CODE=" + marketDiv
                    + "&FID_INPUT_ISCD=" + stockCode;

            HttpHeaders headers = createHeaders(token, "FHKST01010100");
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return objectMapper.readTree(response.getBody());
            }
        } catch (Exception e) {
            rethrowIfRateLimit(e);
            log.error("주식 현재가 조회 실패 [{}/{}]: {}", stockCode, marketDiv, e.getMessage());
        }

        return null;
    }

    /**
     * 주식 현재가 체결 조회 (FHKST01010300) — 체결강도(tday_rltv) 소스.
     *
     * <p>현재가 시세(FHKST01010100) 응답에는 체결강도 필드가 없어 체결강도가 항상 null
     * → 화면에 기본값 100% 고정 표시되던 버그의 근본 원인. 이 API 의 output 배열(최근 체결)
     * 각 항목에 tday_rltv(당일 체결강도)가 들어 있다. 장 마감 후에도 당일 마지막 체결 기준
     * 값을 반환한다. 시세(가격) 조회 용도가 아님 — 시세 단일 경로 불변식과 무관.
     */
    public JsonNode getStockCcnl(String stockCode) {
        return rateLimiter.execute(KisApiRateLimiter.Priority.NORMAL, () -> {
            String token = getAccessToken();
            if (token == null) {
                return null;
            }
            try {
                String url = baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-ccnl"
                        + "?FID_COND_MRKT_DIV_CODE=J"
                        + "&FID_INPUT_ISCD=" + stockCode;

                HttpHeaders headers = createHeaders(token, "FHKST01010300");
                HttpEntity<String> request = new HttpEntity<>(headers);

                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.GET, request, String.class);

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    return objectMapper.readTree(response.getBody());
                }
            } catch (Exception e) {
                rethrowIfRateLimit(e);
                log.error("주식 체결 조회 실패 [{}]: {}", stockCode, e.getMessage());
            }
            return null;
        });
    }

    /**
     * 주식 기본 정보 조회 (종목명 등)
     * @param stockCode 종목코드
     * @return API 응답 JsonNode
     */
    public JsonNode getStockInfo(String stockCode) {
        return rateLimiter.execute(KisApiRateLimiter.Priority.NORMAL, () -> {
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
                rethrowIfRateLimit(e);
                log.error("주식 기본정보 조회 실패 [{}]: {}", stockCode, e.getMessage());
            }

            return null;
        });
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
        return rateLimiter.execute(KisApiRateLimiter.Priority.NORMAL, () -> {
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
                rethrowIfRateLimit(e);
                log.error("투자자별 매매동향 조회 실패 [{}]: {}", stockCode, e.getMessage());
            }

            return null;
        });
    }

    /**
     * 프로그램 매매 추이 조회
     * @param stockCode 종목코드 (6자리)
     * @return API 응답 JsonNode
     */
    public JsonNode getProgramTrading(String stockCode) {
        return rateLimiter.execute(KisApiRateLimiter.Priority.NORMAL, () -> {
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
                log.debug("프로그램 매매 API 미지원 (404) [{}] - 네이버 투자자 매매동향 폴백 사용", stockCode);
            } catch (Exception e) {
                rethrowIfRateLimit(e);
                log.warn("프로그램 매매 조회 실패 [{}]: {}", stockCode, e.getMessage());
            }

            return null;
        });
    }

    /**
     * 지수 현재가 조회 (코스피, 코스닥)
     * @param indexCode 지수코드 (0001: 코스피, 1001: 코스닥)
     * @return API 응답 JsonNode
     */
    public JsonNode getIndexPrice(String indexCode) {
        return rateLimiter.execute(KisApiRateLimiter.Priority.NORMAL, () -> {
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
                rethrowIfRateLimit(e);
                log.error("지수 현재가 조회 실패 [{}]: {}", indexCode, e.getMessage());
            }

            return null;
        });
    }

    /**
     * 지수 분봉 데이터 조회
     * @param indexCode 지수코드
     * @return API 응답 JsonNode
     */
    public JsonNode getIndexMinuteChart(String indexCode) {
        return rateLimiter.execute(KisApiRateLimiter.Priority.NORMAL, () -> {
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
                rethrowIfRateLimit(e);
                log.error("지수 분봉 조회 실패 [{}]: {}", indexCode, e.getMessage());
            }

            return null;
        });
    }

    /**
     * 주식 분봉 데이터 조회 (당일 1분봉)
     * KIS API: FHKST03010200 - 주식당일분봉조회
     *
     * @param stockCode 종목코드
     * @return API 응답 JsonNode (output2에 분봉 데이터 배열)
     */
    public JsonNode getStockMinuteChart(String stockCode) {
        return getStockMinuteChartWithPriority(stockCode, KisApiRateLimiter.Priority.NORMAL);
    }

    /**
     * 주식 분봉 데이터 조회 (우선순위 지정)
     */
    public JsonNode getStockMinuteChartWithPriority(String stockCode, KisApiRateLimiter.Priority priority) {
        return rateLimiter.execute(priority, () -> {
            String token = getAccessToken();
            if (token == null) {
                return null;
            }

            try {
                // 현재 시간 또는 NXT 거래종료 시간 (HHMMSS 형식)
                // NXT 애프터마켓 20:00까지 분봉 수집 (이전엔 KRX 15:30 가드)
                java.time.LocalTime now = java.time.LocalTime.now();
                java.time.LocalTime marketClose = java.time.LocalTime.of(20, 0);
                java.time.LocalTime queryTime = now.isAfter(marketClose) ? marketClose : now;
                String timeStr = String.format("%02d%02d%02d", queryTime.getHour(), queryTime.getMinute(), 0);

                String url = baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice"
                        + "?FID_COND_MRKT_DIV_CODE=UN"  // KRX+NXT 통합
                        + "&FID_INPUT_ISCD=" + stockCode
                        + "&FID_INPUT_HOUR_1=" + timeStr
                        + "&FID_PW_DATA_INCU_YN=Y";

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
                rethrowIfRateLimit(e);
                log.warn("분봉 API 예외 [{}]: {}", stockCode, e.getMessage());
            }

            return null;
        });
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
        return rateLimiter.execute(KisApiRateLimiter.Priority.NORMAL, () -> {
            String token = getAccessToken();
            if (token == null) {
                return null;
            }

            try {
                java.time.LocalDate endDate = java.time.LocalDate.now();
                java.time.LocalDate startDate = endDate.minusDays(days + 30);

                String endDateStr = endDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                String startDateStr = startDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);

                String url = baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"
                        + "?FID_COND_MRKT_DIV_CODE=J"
                        + "&FID_INPUT_ISCD=" + stockCode
                        + "&FID_INPUT_DATE_1=" + startDateStr
                        + "&FID_INPUT_DATE_2=" + endDateStr
                        + "&FID_PERIOD_DIV_CODE=D"
                        + "&FID_ORG_ADJ_PRC=0";

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
                rethrowIfRateLimit(e);
                log.warn("일봉 API 예외 [{}]: {}", stockCode, e.getMessage());
            }

            return null;
        });
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
        return getForeignInstitutionTotalWithPriority(investorType, isBuy, sortByAmount, KisApiRateLimiter.Priority.NORMAL);
    }

    /**
     * 국내기관_외국인 매매종목가집계 조회 (우선순위 지정)
     */
    public JsonNode getForeignInstitutionTotalWithPriority(String investorType, boolean isBuy,
                                                            boolean sortByAmount, KisApiRateLimiter.Priority priority) {
        return rateLimiter.execute(priority, () -> {
            String token = getAccessToken();
            if (token == null) {
                log.error("토큰 발급 실패로 API 호출 불가");
                return null;
            }

            try {
                String url = baseUrl + "/uapi/domestic-stock/v1/quotations/foreign-institution-total"
                        + "?FID_COND_MRKT_DIV_CODE=V"
                        + "&FID_COND_SCR_DIV_CODE=16449"
                        + "&FID_INPUT_ISCD=0000"
                        + "&FID_DIV_CLS_CODE=" + (sortByAmount ? "1" : "0")
                        + "&FID_RANK_SORT_CLS_CODE=" + (isBuy ? "0" : "1")
                        + "&FID_ETC_CLS_CODE=" + investorType;

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
                rethrowIfRateLimit(e);
                log.error("외국인/기관 매매종목 조회 실패 [투자자:{}, 매수:{}]: {}",
                        investorType, isBuy, e.getMessage(), e);
            }

            return null;
        });
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
        return rateLimiter.execute(KisApiRateLimiter.Priority.NORMAL, () -> {
            String token = getAccessToken();
            if (token == null) {
                log.error("[거래량급증] 토큰 발급 실패");
                return null;
            }

            try {
                String url = baseUrl + "/uapi/domestic-stock/v1/quotations/volume-rank"
                        + "?FID_COND_MRKT_DIV_CODE=J"
                        + "&FID_COND_SCR_DIV_CODE=20171"
                        + "&FID_INPUT_ISCD=0000"
                        + "&FID_DIV_CLS_CODE=0"
                        + "&FID_BLNG_CLS_CODE=0"
                        + "&FID_TRGT_CLS_CODE=111111111"
                        + "&FID_TRGT_EXLS_CLS_CODE=000000"
                        + "&FID_INPUT_PRICE_1="
                        + "&FID_INPUT_PRICE_2="
                        + "&FID_VOL_CNT="
                        + "&FID_INPUT_DATE_1=";

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
                rethrowIfRateLimit(e);
                log.error("[거래량급증] 조회 실패: {}", e.getMessage(), e);
            }

            return null;
        });
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
        return getDailyPricesWithPriority(stockCode, days, KisApiRateLimiter.Priority.NORMAL);
    }

    /**
     * 일봉 데이터 조회 (우선순위 지정)
     */
    public JsonNode getDailyPricesWithPriority(String stockCode, int days, KisApiRateLimiter.Priority priority) {
        return rateLimiter.execute(priority, () -> getDailyPricesInternal(stockCode, days));
    }

    private JsonNode getDailyPricesInternal(String stockCode, int days) {
        String token = getAccessToken();
        if (token == null) {
            log.error("토큰 발급 실패로 일봉 조회 불가");
            return null;
        }

        try {
            java.time.LocalDate endDate = java.time.LocalDate.now();
            java.time.LocalDate startDate = endDate.minusDays(days + 30);
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");

            String url = baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"
                    + "?FID_COND_MRKT_DIV_CODE=J"
                    + "&FID_INPUT_ISCD=" + stockCode
                    + "&FID_INPUT_DATE_1=" + startDate.format(formatter)
                    + "&FID_INPUT_DATE_2=" + endDate.format(formatter)
                    + "&FID_PERIOD_DIV_CODE=D"
                    + "&FID_ORG_ADJ_PRC=0";

            log.debug("일봉 조회 API 호출: stockCode={}, days={}", stockCode, days);

            HttpHeaders headers = createHeaders(token, "FHKST03010100");
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode result = objectMapper.readTree(response.getBody());

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
            rethrowIfRateLimit(e);
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
     * 일봉 종가 리스트 추출 (우선순위 지정)
     */
    public java.util.List<java.math.BigDecimal> getDailyClosePricesWithPriority(String stockCode, int days,
                                                                                  KisApiRateLimiter.Priority priority) {
        java.util.List<java.math.BigDecimal> prices = new java.util.ArrayList<>();

        JsonNode response = getDailyPricesWithPriority(stockCode, days, priority);
        if (response == null || !response.has("output2")) {
            return prices;
        }

        JsonNode output2 = response.get("output2");
        if (!output2.isArray()) {
            return prices;
        }

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

        log.debug("종가 추출 완료 [{}]: {} 건 (priority={})", stockCode, prices.size(), priority);
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
    @CircuitBreaker(name = "kisApi", fallbackMethod = "buyStockFallback")
    public JsonNode buyStock(String stockCode, int quantity, java.math.BigDecimal price) {
        return rateLimiter.execute(KisApiRateLimiter.Priority.CRITICAL,
                () -> placeOrder(stockCode, quantity, price, "TTTC0802U", "buy"), 3);
    }

    /** CircuitBreaker OPEN — RealTradeService 가 null 받아 거래 중단 + 운영자 알림 발동 */
    @SuppressWarnings("unused")
    private JsonNode buyStockFallback(String stockCode, int quantity, java.math.BigDecimal price, Throwable t) {
        log.error("[KIS CircuitBreaker] buyStock fallback — code: {}, qty: {}, cause: {}",
                stockCode, quantity, t.getClass().getSimpleName() + ": " + t.getMessage());
        return null;
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
    @CircuitBreaker(name = "kisApi", fallbackMethod = "sellStockFallback")
    public JsonNode sellStock(String stockCode, int quantity, java.math.BigDecimal price) {
        return rateLimiter.execute(KisApiRateLimiter.Priority.CRITICAL,
                () -> placeOrder(stockCode, quantity, price, "TTTC0801U", "sell"), 3);
    }

    @SuppressWarnings("unused")
    private JsonNode sellStockFallback(String stockCode, int quantity, java.math.BigDecimal price, Throwable t) {
        log.error("[KIS CircuitBreaker] sellStock fallback — code: {}, qty: {}, cause: {}",
                stockCode, quantity, t.getClass().getSimpleName() + ": " + t.getMessage());
        return null;
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

            // 요청 바디 (지정가 주문)
            Map<String, String> body = new HashMap<>();
            body.put("CANO", accountPrefix);              // 계좌번호 앞 8자리
            body.put("ACNT_PRDT_CD", accountSuffix);      // 계좌상품코드 (01)
            body.put("PDNO", stockCode);                  // 종목코드
            body.put("ORD_DVSN", "00");                   // 주문구분: 00=지정가 (슬리피지 방지)
            body.put("ORD_QTY", String.valueOf(quantity)); // 주문수량
            body.put("ORD_UNPR", price.setScale(0, java.math.RoundingMode.DOWN).toString()); // 주문단가

            // KIS 주문 API 는 hashkey 헤더 필수 — 누락 시 게이트웨이가 EGW00202(GW라우팅 오류)로 거부.
            String hashKey = createHashKey(body);

            HttpHeaders headers = createHeaders(token, trId);
            if (hashKey != null) {
                headers.set("hashkey", hashKey);
            } else {
                log.warn("[실전매매] hashkey 발급 실패 — KIS 거부 가능성 매우 높음");
            }

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
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // KIS 가 4xx/5xx 로 명시적 거부 (예: EGW00202 GW라우팅, EGW00121 모의/실전 키 불일치 등).
            // 주문 안 들어간 게 확실하므로 body 의 rt_cd/msg 를 파싱해서 반환 →
            // RealTradeService 가 "응답 null = 불확실" 대신 "명시적 거부" 로 처리, 자동 킬스위치 안 발동.
            String errorBody = e.getResponseBodyAsString();
            log.error("[실전매매] {} 주문 실패 [{}]: {} - body: {}",
                    orderType.toUpperCase(), stockCode, e.getStatusCode(), errorBody);
            try {
                JsonNode parsed = objectMapper.readTree(errorBody);
                if (parsed.has("rt_cd")) {
                    return parsed;
                }
            } catch (Exception ignore) { /* body parsing 실패 시 null fall-through */ }
        } catch (Exception e) {
            log.error("[실전매매] {} 주문 실패 [{}]: {}", orderType.toUpperCase(), stockCode, e.getMessage());
        }

        return null;
    }

    /**
     * KIS HASH KEY 발급 — 주문 같은 데이터 변경(POST) API 호출 시 필수 헤더.
     * /uapi/hashkey 에 body 를 POST 해서 HASH 값을 받아온다.
     * appkey/appsecret 만 필요하고 access token 은 불필요.
     * 발급 실패 시 null 반환 → 호출부가 hashkey 없이 진행 (KIS 가 EGW00202 로 거부).
     */
    private String createHashKey(Map<String, String> body) {
        try {
            String url = baseUrl + "/uapi/hashkey";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("appkey", appKey);
            headers.set("appsecret", appSecret);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode result = objectMapper.readTree(response.getBody());
                if (result.has("HASH")) {
                    return result.get("HASH").asText();
                }
                log.error("[KIS hashkey] 응답에 HASH 필드 없음 - body: {}", response.getBody());
            }
        } catch (Exception e) {
            log.error("[KIS hashkey] 발급 실패: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 주식 잔고 조회 (보유종목 및 예수금)
     * KIS API: TTTC8434R (국내주식 잔고조회)
     *
     * @return 잔고 정보 JsonNode
     */
    @CircuitBreaker(name = "kisApi", fallbackMethod = "getBalanceFallback")
    public JsonNode getBalance() {
        // 매매 전 필수 조회 — CRITICAL 로 승격 (다른 호출에 밀리지 않게) + 재시도 3회
        return rateLimiter.execute(KisApiRateLimiter.Priority.CRITICAL, () -> getBalanceInternal(), 3);
    }

    /** CircuitBreaker OPEN — RealTradeService.getBalanceInfo(force=true) 가 null 받아 매매 abort */
    @SuppressWarnings("unused")
    private JsonNode getBalanceFallback(Throwable t) {
        log.error("[KIS CircuitBreaker] getBalance fallback — cause: {}",
                t.getClass().getSimpleName() + ": " + t.getMessage());
        return null;
    }

    private JsonNode getBalanceInternal() {
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
                    + "&AFHR_FLPR_YN=N"
                    + "&OFL_YN="
                    + "&INQR_DVSN=02"
                    + "&UNPR_DVSN=01"
                    + "&FUND_STTL_ICLD_YN=N"
                    + "&FNCG_AMT_AUTO_RDPT_YN=N"
                    + "&PRCS_DVSN=00"
                    + "&CTX_AREA_FK100="
                    + "&CTX_AREA_NK100=";

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
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            // rate limit 등은 rateLimiter 가 재시도할 수 있도록 예외를 그대로 던진다.
            String body = e.getResponseBodyAsString();
            log.error("[실전매매] 잔고 조회 실패: {} {}", e.getStatusCode(), body);
            throw new RuntimeException("잔고 조회 실패: " + body, e);
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
