package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.util.OrderSession;
import com.myplatform.backend.util.OrderSessionRouter;
import com.myplatform.core.util.DateTimeUtil;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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

    // ── NXT/연장장 주문 라우팅 (2026-09-14 대비, 기본 OFF — docs/NXT_ROUTING_DESIGN.md) ──
    // flag OFF 면 NXT 세션 요청도 REGULAR 로 강등(현행 바이트 동일). atr-trading.enabled 패턴 동일.
    @Value("${bot.nxt-routing.enabled:false}")
    private volatile boolean nxtRoutingEnabled;

    // ⚠ KIS order-cash NXT 거래소구분 필드 — 이름·값 미확정(§0.4). 9/14 전 KIS 문서로 확정 후 주입.
    // 공란이면 NXT 주문 경로는 예외로 거부(fail-CLOSED) — 추측 파라미터 금지(§4c).
    @Value("${kis.order.nxt-exchange-param-name:}")
    private String nxtExchangeParamName;
    @Value("${kis.order.nxt-exchange-param-value:}")
    private String nxtExchangeParamValue;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final KisApiRateLimiter rateLimiter;
    // 토큰 발급/캐시/갱신/쿨다운/401 무효화 = 공유 단일 출처 (P3-8 선택B). 3서비스가 같은 빈을 주입받아
    // 앱 전체가 토큰 1개를 공유(발급 경합 최소화). appKey/appSecret 은 헤더용으로 이 서비스에 계속 유지.
    private final KisTokenManager tokenManager;

    public KoreaInvestmentService(RestTemplate restTemplate, ObjectMapper objectMapper,
                                  KisApiRateLimiter rateLimiter, KisTokenManager tokenManager) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
        this.tokenManager = tokenManager;
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
     * KIS API 호출 예외가 인증 실패(HTTP 401)인지 판정. 순수 함수(테스트 대상).
     * <p>판정 단일 출처는 {@link KisTokenManager#isAuthFailure} — 기존 호출부/테스트 호환용 정적 위임.
     */
    static boolean isAuthFailure(Exception e) {
        return KisTokenManager.isAuthFailure(e);
    }

    /**
     * KIS 조회 API 호출 예외 공통 처리: rate-limit(429/EGW00201)은 재시도 위해 rethrow,
     * 401 인증 실패는 <b>공유 토큰 캐시</b>를 1회 무효화한다(값 기반 CAS — {@code usedToken} 이 현재
     * 캐시와 동일할 때만). {@code usedToken} = 실패한 호출이 헤더에 실었던 토큰(호출부 로컬).
     * (주문 경로는 별도 catch 에서 {@code tokenManager.invalidateOnAuthFailure} 직접 호출.)
     */
    private void handleKisApiException(Exception e, String usedToken) {
        rethrowIfRateLimit(e);                                  // rate-limit → RuntimeException 재던짐(아래 미도달)
        tokenManager.invalidateOnAuthFailure(e, usedToken);     // 401 → 공유 토큰 캐시 1회 CAS 무효화
    }

    /**
     * API 설정이 유효한지 확인
     */
    public boolean isConfigured() {
        return appKey != null && !appKey.isEmpty()
            && appSecret != null && !appSecret.isEmpty();
    }

    /**
     * 토큰이 현재 사용 가능한지 확인 (쿨다운 포함) — 공유 매니저 위임.
     * 발급 시도 없이 상태만 확인. 공개 시그니처 유지(외부 호출부 무변경).
     */
    public boolean isTokenAvailable() {
        return tokenManager.isTokenAvailable();
    }

    /**
     * 공유 Access Token — 발급/캐시/갱신/쿨다운은 {@link KisTokenManager} 가 전담(P3-8 선택B).
     * 공개 시그니처 유지(외부 호출부 무변경). 발급은 매니저에서 {@code synchronized} 전역 직렬화.
     */
    public String getAccessToken() {
        return tokenManager.getAccessToken();
    }

    /**
     * KIS 토큰 응답의 expires_in(초)을 추출한다. 순수 함수(테스트 대상).
     * <p>구현 단일 출처는 {@link KisTokenManager#parseExpiresInSeconds} — 기존 테스트 호환용 정적 위임.
     */
    static long parseExpiresInSeconds(JsonNode root) {
        return KisTokenManager.parseExpiresInSeconds(root);
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
            handleKisApiException(e, token);
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
                handleKisApiException(e, token);
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
                handleKisApiException(e, token);
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
                handleKisApiException(e, token);
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
                handleKisApiException(e, token);
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
                handleKisApiException(e, token);
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
                handleKisApiException(e, token);
                log.error("지수 분봉 조회 실패 [{}]: {}", indexCode, e.getMessage());
            }

            return null;
        });
    }

    /**
     * 지수 일봉 OHLCV 조회 — regime/sector 의 KOSPI MA60 계산용.
     * KIS TR FHPUP02120000 (inquire-index-daily-price). pykrx 지수 엔드포인트가 KRX 포맷 변경으로
     * 전구간 빈값(2026-06-30)이라 KIS 일봉으로 전환. 진짜 종합지수(0001) — ETF 프록시 아님.
     *
     * @param indexCode 지수코드 (0001: 코스피, 1001: 코스닥)
     * @param days 조회 <b>캘린더</b> 일수(거래일 아님 — 130 캘린더 ~ 90 거래일이라 MA60+5일슬로프 충분).
     * @return 일봉 리스트(오름차순 과거→최신, pykrx 동형). 실패/미설정 시 빈 리스트(위장값 금지).
     */
    public java.util.List<IndexOhlcvData> getIndexDailyOhlcv(String indexCode, int days) {
        return getIndexDailyOhlcv(indexCode, days, null);   // null = 오늘 앵커(현행 동작 — 바이트 동일)
    }

    /**
     * 앵커 지정 오버로드 — {@code anchorEnd} 기준 그 직전 최대 ~100 거래일. KIS 지수 TR 이 한 번에 ~100건만
     * 반환하므로, 252거래일 등 장기 창은 호출부가 anchorEnd 를 과거로 밀며 <b>페이지네이션</b>한다
     * ({@code VolatilityRegimeService}). anchorEnd=null 이면 오늘 앵커 = 기존 호출부와 완전 동일.
     */
    public java.util.List<IndexOhlcvData> getIndexDailyOhlcv(String indexCode, int days, java.time.LocalDate anchorEnd) {
        return rateLimiter.execute(KisApiRateLimiter.Priority.NORMAL, () -> {
            String token = getAccessToken();
            if (token == null) {
                return java.util.List.<IndexOhlcvData>of();
            }
            try {
                java.time.LocalDate end = (anchorEnd != null) ? anchorEnd : java.time.LocalDate.now();
                java.time.LocalDate start = end.minusDays(days);
                java.time.format.DateTimeFormatter fmt =
                        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");

                // 국내주식업종기간별시세(일/주/월/년) - U: 업종(지수).
                // ⚠ KIS 지수 TR 은 FID_INPUT_DATE_1 을 '기준일(앵커)'로 그 직전 최대 100건을 반환한다
                // (종목 TR(FHKST03010100)은 DATE_2 가 끝인 것과 반대). 그래서 DATE_1=오늘(end)로 둬야
                // 최신이 last 에 온다. DATE_2(=start)는 하한(또는 미사용). 2026-06-30 운영 확인:
                // DATE_1=start 로 두니 데이터가 start(4개월 전)에서 끝나 regime asOf 오판 -> DATE_1=end 로 교정.
                String url = baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-index-daily-price"
                        + "?FID_COND_MRKT_DIV_CODE=U"
                        + "&FID_INPUT_ISCD=" + indexCode
                        + "&FID_INPUT_DATE_1=" + end.format(fmt)     // 앵커=조회 기준일(오늘) → 최신이 last
                        + "&FID_INPUT_DATE_2=" + start.format(fmt)   // 하한(또는 미사용)
                        + "&FID_PERIOD_DIV_CODE=D";

                HttpHeaders headers = createHeaders(token, "FHPUP02120000");
                HttpEntity<String> request = new HttpEntity<>(headers);
                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.GET, request, String.class);

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    java.util.List<IndexOhlcvData> rows =
                            parseIndexDaily(objectMapper.readTree(response.getBody()));
                    log.debug("지수 일봉 조회 [{}]: {} 건", indexCode, rows.size());
                    return rows;
                }
            } catch (Exception e) {
                handleKisApiException(e, token);
                log.error("지수 일봉 조회 실패 [{}]: {}", indexCode, e.getMessage());
            }
            return java.util.List.<IndexOhlcvData>of();
        });
    }

    /**
     * 지수 일봉 응답 파싱 — 순수 함수(테스트 대상). {@code output2[]} → 오름차순(과거→최신) 리스트.
     * (주의) 필드명(bstp_nmix_oprc/hgpr/lwpr/prpr, stck_bsop_date)은 KIS 규격 기준 — 결측/이상 행은 graceful skip(보정 없이 위장값 금지).
     * 종가(bstp_nmix_prpr) 결측 행은 제외; 시/고/저는 누락 시 null 허용(regime 은 종가만 필요).
     */
    static java.util.List<IndexOhlcvData> parseIndexDaily(JsonNode response) {
        java.util.List<IndexOhlcvData> out = new java.util.ArrayList<>();
        if (response == null || !response.has("output2") || !response.get("output2").isArray()) {
            return out;
        }
        for (JsonNode item : response.get("output2")) {
            String dateStr = nodeText(item, "stck_bsop_date");
            java.math.BigDecimal close = nodeDecimal(item, "bstp_nmix_prpr");
            if (dateStr == null || dateStr.length() != 8
                    || close == null || close.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                continue;   // 영업일자/종가 결측/이상 → 제외
            }
            String iso = dateStr.substring(0, 4) + "-" + dateStr.substring(4, 6) + "-" + dateStr.substring(6, 8);
            out.add(new IndexOhlcvData(iso,
                    nodeDecimal(item, "bstp_nmix_oprc"), nodeDecimal(item, "bstp_nmix_hgpr"),
                    nodeDecimal(item, "bstp_nmix_lwpr"), close));
        }
        out.sort(java.util.Comparator.comparing(IndexOhlcvData::date));   // 과거→최신 (pykrx 동형)
        return out;
    }

    private static String nodeText(JsonNode item, String field) {
        if (item != null && item.has(field)) {
            String v = item.get(field).asText();
            return (v == null || v.isEmpty()) ? null : v;
        }
        return null;
    }

    private static java.math.BigDecimal nodeDecimal(JsonNode item, String field) {
        String v = nodeText(item, field);
        if (v == null) return null;
        try {
            return new java.math.BigDecimal(v.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;   // 파싱 불가 → null (보정 안 함)
        }
    }

    /** 지수 일봉 한 점 — date(yyyy-MM-dd) + OHLC. JSON 직렬화되어 python regime/sector 가 소비. */
    public record IndexOhlcvData(String date, java.math.BigDecimal open,
                                 java.math.BigDecimal high, java.math.BigDecimal low,
                                 java.math.BigDecimal close) {}

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
                handleKisApiException(e, token);
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
                handleKisApiException(e, token);
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
                handleKisApiException(e, token);
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
                handleKisApiException(e, token);
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
            handleKisApiException(e, token);
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
                () -> placeOrder(stockCode, quantity, price, "TTTC0802U", "buy", OrderSession.REGULAR), 3);
    }

    /**
     * 세션 지정 매수 오버로드 (NXT 연장장 대비). 기본 3-arg 는 REGULAR 로 위임 — 기존 호출부 무영향.
     * NXT 요청은 {@code bot.nxt-routing.enabled} OFF 시 REGULAR 로 강등(WARN), 미확정 파라미터면 거부(fail-CLOSED).
     */
    @CircuitBreaker(name = "kisApi", fallbackMethod = "buyStockSessionFallback")
    public JsonNode buyStock(String stockCode, int quantity, java.math.BigDecimal price, OrderSession session) {
        return rateLimiter.execute(KisApiRateLimiter.Priority.CRITICAL,
                () -> placeOrder(stockCode, quantity, price, "TTTC0802U", "buy", session), 3);
    }

    /** CircuitBreaker OPEN — RealTradeService 가 null 받아 거래 중단 + 운영자 알림 발동 */
    @SuppressWarnings("unused")
    private JsonNode buyStockFallback(String stockCode, int quantity, java.math.BigDecimal price, Throwable t) {
        log.error("[KIS CircuitBreaker] buyStock fallback — code: {}, qty: {}, cause: {}",
                stockCode, quantity, t.getClass().getSimpleName() + ": " + t.getMessage());
        return null;
    }

    @SuppressWarnings("unused")
    private JsonNode buyStockSessionFallback(String stockCode, int quantity, java.math.BigDecimal price,
                                             OrderSession session, Throwable t) {
        log.error("[KIS CircuitBreaker] buyStock({}) fallback — code: {}, qty: {}, cause: {}",
                session, stockCode, quantity, t.getClass().getSimpleName() + ": " + t.getMessage());
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
                () -> placeOrder(stockCode, quantity, price, "TTTC0801U", "sell", OrderSession.REGULAR), 3);
    }

    /**
     * 세션 지정 매도 오버로드 (NXT 방어 청산 대비). 기본 3-arg 는 REGULAR 로 위임 — 기존 호출부 무영향.
     * NXT 요청은 {@code bot.nxt-routing.enabled} OFF 시 REGULAR 로 강등(WARN), 미확정 파라미터면 거부(fail-CLOSED).
     */
    @CircuitBreaker(name = "kisApi", fallbackMethod = "sellStockSessionFallback")
    public JsonNode sellStock(String stockCode, int quantity, java.math.BigDecimal price, OrderSession session) {
        return rateLimiter.execute(KisApiRateLimiter.Priority.CRITICAL,
                () -> placeOrder(stockCode, quantity, price, "TTTC0801U", "sell", session), 3);
    }

    @SuppressWarnings("unused")
    private JsonNode sellStockFallback(String stockCode, int quantity, java.math.BigDecimal price, Throwable t) {
        log.error("[KIS CircuitBreaker] sellStock fallback — code: {}, qty: {}, cause: {}",
                stockCode, quantity, t.getClass().getSimpleName() + ": " + t.getMessage());
        return null;
    }

    @SuppressWarnings("unused")
    private JsonNode sellStockSessionFallback(String stockCode, int quantity, java.math.BigDecimal price,
                                              OrderSession session, Throwable t) {
        log.error("[KIS CircuitBreaker] sellStock({}) fallback — code: {}, qty: {}, cause: {}",
                session, stockCode, quantity, t.getClass().getSimpleName() + ": " + t.getMessage());
        return null;
    }

    /**
     * 주식 주문 공통 처리 (지정가)
     * ORD_DVSN: 00=지정가, 01=시장가
     * 지정가 주문으로 슬리피지(체결 오차) 방지
     */
    /**
     * NXT/연장장 주문 라우팅을 주문 바디에 반영한다. 시각을 인자로 받아 결정성 있게 테스트 가능(package-private).
     *
     * <ul>
     *   <li>요청 REGULAR(기본) 또는 flag OFF/창 밖으로 REGULAR 강등 → 바디 <b>무변경</b>(현행 바이트 동일), null 반환.</li>
     *   <li>강등 시(NXT 요청 → REGULAR) WARN 로그(§4c: 조용한 다운그레이드 금지).</li>
     *   <li>NXT 라우팅 확정 + 거래소구분 파라미터 설정됨 → 바디에 필드 추가, null 반환.</li>
     *   <li>NXT 라우팅 확정 + 파라미터 <b>미설정</b> → <b>명시적 거부 노드(rt_cd≠0) 반환</b>(fail-CLOSED).
     *       KIS 미호출이라 '불확실'(null)이 아니므로 killswitch 미발동 — RealTradeService 가 markFailed 처리.</li>
     * </ul>
     *
     * @return null = 정상 진행(바디 확정), non-null = 명시적 거부 응답(호출부가 그대로 반환)
     */
    JsonNode applyNxtRouting(Map<String, String> body, String stockCode,
                            OrderSession requestedSession, java.time.LocalTime now) {
        OrderSession effective = OrderSessionRouter.resolveOrderSession(now, nxtRoutingEnabled, requestedSession);
        if (requestedSession == OrderSession.NXT_EXTENDED && effective != OrderSession.NXT_EXTENDED) {
            log.warn("[실전매매] NXT 세션 요청 → REGULAR 강등 (nxtRoutingEnabled={}, 시각={}): {} — flag OFF 또는 NXT 창(15:30~20:00) 밖",
                    nxtRoutingEnabled, now, stockCode);
        }
        if (effective != OrderSession.NXT_EXTENDED) {
            return null;   // REGULAR — 바디 현행 동일
        }
        // NXT 라우팅 확정. ⚠ 거래소구분 필드 이름·값 미확정(§0.4) — 미설정이면 fail-CLOSED.
        if (nxtExchangeParamName == null || nxtExchangeParamName.isBlank()
                || nxtExchangeParamValue == null || nxtExchangeParamValue.isBlank()) {
            log.error("[실전매매] NXT 주문 거부(fail-CLOSED) — 거래소구분 파라미터 미설정"
                    + "(kis.order.nxt-exchange-param-name/value). 9/14 전 KIS 문서로 확정 필요. code: {}", stockCode);
            com.fasterxml.jackson.databind.node.ObjectNode reject = objectMapper.createObjectNode();
            reject.put("rt_cd", "1");
            reject.put("msg1", "NXT_PARAM_UNCONFIGURED");
            return reject;
        }
        body.put(nxtExchangeParamName, nxtExchangeParamValue);
        log.info("[실전매매] NXT 연장 세션 라우팅 적용 — code: {}, {}={}",
                stockCode, nxtExchangeParamName, nxtExchangeParamValue);
        return null;
    }

    private JsonNode placeOrder(String stockCode, int quantity, java.math.BigDecimal price,
                               String trId, String orderType, OrderSession requestedSession) {
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

            // NXT/연장장 라우팅: REGULAR 이면 바디 현행 동일(거래소구분 미추가). NXT 인데 파라미터 미확정이면
            // 명시적 거부(rt_cd≠0)를 반환(fail-CLOSED). createHashKey 前에 바디 확정돼야 하므로 여기서 적용.
            JsonNode routingReject = applyNxtRouting(body, stockCode, requestedSession,
                    DateTimeUtil.kstNow().toLocalTime());
            if (routingReject != null) {
                return routingReject;   // 미확정 NXT = 접수 전 거부(불확실 null 아님 → killswitch 미발동)
            }

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
            // 401(인증 실패)이면 토큰 캐시만 1회 무효화 → 다음 호출 재발급. §4d: 주문 재시도는 절대 없음
            // (401=미접수 확실, 아래 거부바디/null 반환 흐름 불변 → killswitch 로직 무접촉).
            tokenManager.invalidateOnAuthFailure(e, token);
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
                    return result;
                }
                String msg = result.has("msg1") ? result.get("msg1").asText() : "";
                log.error("[실전매매] 잔고 조회 실패: {}", msg);
                // rt_cd≠0 에러 바디를 그대로 반환하면 parseBalance 가 "예수금 0·보유 없음"의 빈 정상잔고로
                // 위장(+RealTradeService 30초 캐시) → 정상 매도(손절 포함)가 "보유 부족"으로 거부되고 킬스위치
                // 자산 계산이 오염된다. null = 실패 신호(주문 경로 중단 / 표시 경로 직전 캐시 유지).
                return null;
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
     * 주문 체결 조회 (TTTC0081R, inquire-daily-ccld) — 특정 주문(ODNO)의 총체결수량 반환.
     * <p>읽기 전용·best-effort. 지정가 부분/미체결 확인용. 실패·주문없음·미설정 시 <b>null</b> 반환 →
     * 호출측이 "체결 확인 불가(UNKNOWN)"로 보수 처리하도록 한다.
     * <p>⚠ 응답 필드명(output1/odno/tot_ccld_qty)은 KIS inquire-daily-ccld 규격 기준 — 운영 로그로 1회 확인 권장.
     *
     * @return 총체결수량(같은 ODNO 의 체결 row 합산), 조회 실패/주문 미발견 시 null
     */
    public Integer inquireDailyCcld(String stockCode, String orderNo) {
        String token = getAccessToken();
        if (token == null || !isRealTradingConfigured() || orderNo == null || orderNo.isBlank()) {
            return null;
        }
        try {
            String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
                    .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE); // yyyyMMdd
            String url = baseUrl + "/uapi/domestic-stock/v1/trading/inquire-daily-ccld"
                    + "?CANO=" + accountPrefix
                    + "&ACNT_PRDT_CD=" + accountSuffix
                    + "&INQR_STRT_DT=" + today
                    + "&INQR_END_DT=" + today
                    + "&SLL_BUY_DVSN_CD=00"   // 00=전체(매수/매도)
                    + "&INQR_DVSN=00"          // 정렬: 역순
                    + "&PDNO=" + stockCode
                    + "&CCLD_DVSN=00"          // 00=전체
                    + "&ORD_GNO_BRNO="
                    + "&ODNO=" + orderNo
                    + "&INQR_DVSN_3=00"
                    + "&INQR_DVSN_1="
                    + "&CTX_AREA_FK100="
                    + "&CTX_AREA_NK100=";
            HttpHeaders headers = createHeaders(token, "TTTC0081R");
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode result = objectMapper.readTree(response.getBody());
                if (!"0".equals(result.path("rt_cd").asText())) {
                    log.warn("[실전매매] 체결조회 실패 [{}] ODNO {}: {}", stockCode, orderNo,
                            result.path("msg1").asText());
                    return null;
                }
                JsonNode arr = result.get("output1");
                if (arr == null || !arr.isArray()) return null;
                int totalCcld = 0;
                boolean found = false;
                for (JsonNode row : arr) {
                    if (orderNo.equals(row.path("odno").asText())) {
                        totalCcld += row.path("tot_ccld_qty").asInt(0);
                        found = true;
                    }
                }
                return found ? totalCcld : null;
            }
        } catch (Exception e) {
            log.warn("[실전매매] 체결조회 예외 [{}] ODNO {}: {}", stockCode, orderNo, e.getMessage());
            tokenManager.invalidateOnAuthFailure(e, token);   // 401 → 토큰 1회 무효화(읽기 전용, resolveFill 은 기존대로 null=UNKNOWN)
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
