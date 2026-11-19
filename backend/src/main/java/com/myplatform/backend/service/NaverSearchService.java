package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.RiskAnalysisDto.NewsItem;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 네이버 검색 API 연동 서비스 — NAVER API HUB 키가 있으면 HUB, 없으면 개발자센터 키({@link Route}, 2026-11-19)
 *
 * [검색 전략 v5 - 강제 날짜순 + Fail-safe]
 * 1. 검색어: 종목명 딱 한 단어만 (수식어 금지)
 * 2. 정렬: sort=date 고정 (sim 절대 금지!)
 * 3. display: 30개 (충분히 가져옴)
 * 4. 날짜 파싱: Locale.ENGLISH 필수, 실패 시 살려둠
 * 5. Fail-safe: 7일 필터 후 0건이면 원본 상위 5개 반환
 */
@Service
@Slf4j
public class NaverSearchService {

    /**
     * 뉴스 검색 경로(2026-11-19). 네이버가 검색 API 를 NAVER API HUB(네이버 클라우드)로 옮겼고, 개발자센터 키는
     * <b>2027-06-30</b> 부터 검색 API 에서 막힌다(developers.naver.com 공지 32530).
     * <ul>
     *   <li>{@code HUB} — {@code naver.hub.client-id/secret}({@code NAVER_HUB_CLIENT_ID/SECRET}) 둘 다 있을 때.</li>
     *   <li>{@code LEGACY} — HUB 키가 없고 개발자센터 키({@code NAVER_CLIENT_ID/SECRET})만 있을 때(종전 그대로).</li>
     *   <li>{@code NONE} — 둘 다 없음 = 뉴스 소스 미가용({@link #isAvailable()} false).</li>
     * </ul>
     * 한 요청에 두 키를 같이 보내지 않는다. HUB 호출이 실패해도 개발자센터로 다시 부르지 않는다 — HUB 설정 오류가 가려진다.
     */
    public enum Route { HUB, LEGACY, NONE }

    /** 개발자센터 키가 검색 API 에서 막히는 날 — 부팅 경고·진단 응답에 쓴다. */
    public static final String LEGACY_CUTOFF = "2027-06-30";
    /** HUB 뉴스 검색 기본 주소 — api.ncloud-docs.com 'NAVER API HUB > 검색 > 뉴스 검색 결과 조회'(GET /search/v1/news). */
    public static final String DEFAULT_HUB_NEWS_URL = "https://naverapihub.apigw.ntruss.com/search/v1/news";
    /** 개발자센터(openapi.naver.com) 뉴스 검색 — 종전 주소. */
    static final String LEGACY_NEWS_URL = "https://openapi.naver.com/v1/search/news.json";

    static final String HUB_KEY_ID_HEADER = "X-NCP-APIGW-API-KEY-ID";
    static final String HUB_KEY_HEADER = "X-NCP-APIGW-API-KEY";
    static final String LEGACY_ID_HEADER = "X-Naver-Client-Id";
    static final String LEGACY_SECRET_HEADER = "X-Naver-Client-Secret";

    private final String clientId;          // 개발자센터(종전)
    private final String clientSecret;
    private final String hubClientId;       // NAVER API HUB
    private final String hubClientSecret;
    private final String hubNewsUrl;

    // ========== 하드코딩 설정 ==========
    private static final String SORT_DATE = "date";      // 절대 sim 금지!
    private static final int DISPLAY_COUNT = 30;         // 충분히 가져오기
    private static final int MAX_NEWS_AGE_DAYS = 7;      // 7일 이내 필터
    private static final int FALLBACK_COUNT = 5;         // Fail-safe 반환 개수
    private static final int MAX_RESULT_COUNT = 15;      // 최종 반환 최대 개수

    // 리스크 관련 키워드
    private static final List<String> RISK_KEYWORDS = Arrays.asList(
            "악재", "검찰", "횡령", "배임", "수사", "기소",
            "적자", "손실", "하락", "폭락", "실적악화", "공매도"
    );

    // 제외 키워드 (비관련 기사)
    private static final List<String> EXCLUDE_KEYWORDS = Arrays.asList(
            "연예", "아이돌", "드라마", "야구", "축구", "농구", "운세", "로또"
    );

    // 날짜 파싱용 포맷터 (Locale.ENGLISH 필수!)
    private static final DateTimeFormatter RFC_1123_FORMATTER =
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 뉴스 검색 실패 누계(프로세스 메모리) — 08:00 재료 워밍 크론이 실행 전후로 비교해 배치 모니터에 FAILED 를 남긴다.
     * 정적인 이유는 {@link KisCallTally} 와 같다(워밍 서비스 생성자를 바꾸면 그 테스트들이 같이 바뀐다).
     */
    private static final AtomicLong FAILURES = new AtomicLong();
    private static volatile String lastFailureSummary;
    /** 실패 로그를 상태가 바뀐 순간만 ERROR 로 — 같은 실패가 이어지면 DEBUG(§5.9). 키엔 경로·상태만 넣는다. */
    private volatile String lastLoggedState = "OK";

    @Autowired
    public NaverSearchService(@Value("${naver.api.client-id:}") String clientId,
                              @Value("${naver.api.client-secret:}") String clientSecret,
                              @Value("${naver.hub.client-id:}") String hubClientId,
                              @Value("${naver.hub.client-secret:}") String hubClientSecret,
                              @Value("${naver.hub.news-url:" + DEFAULT_HUB_NEWS_URL + "}") String hubNewsUrl) {
        this(defaultRestTemplate(), clientId, clientSecret, hubClientId, hubClientSecret, hubNewsUrl);
    }

    /** 테스트용 — RestTemplate 을 바꿔 끼운다(MockRestServiceServer). */
    NaverSearchService(RestTemplate restTemplate, String clientId, String clientSecret,
                       String hubClientId, String hubClientSecret, String hubNewsUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
        this.clientId = trim(clientId);
        this.clientSecret = trim(clientSecret);
        this.hubClientId = trim(hubClientId);
        this.hubClientSecret = trim(hubClientSecret);
        this.hubNewsUrl = isBlank(hubNewsUrl) ? DEFAULT_HUB_NEWS_URL : hubNewsUrl.trim();
        logRouteOnce();
    }

    private static RestTemplate defaultRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    /** 부팅 때 한 번 — 어느 경로로 부르는지(키 값은 찍지 않는다). 개발자센터 경로면 막히는 날과 할 일을 WARN 으로. */
    private void logRouteOnce() {
        boolean hubPartial = isBlank(hubClientId) != isBlank(hubClientSecret);
        if (hubPartial) {
            log.warn("[NaverSearch] NAVER_HUB_CLIENT_ID·NAVER_HUB_CLIENT_SECRET 중 하나만 있다 — HUB 경로를 쓰지 않는다(둘 다 넣을 것)");
        }
        switch (route()) {
            case HUB -> log.info("[NaverSearch] 뉴스 검색 경로 = NAVER API HUB ({}){}", hubNewsUrl,
                    hasLegacyKeys() ? " — 개발자센터 키는 쓰지 않는다" : "");
            case LEGACY -> log.warn("[NaverSearch] 뉴스 검색 경로 = 개발자센터 키(openapi.naver.com) — {} 부터 막힌다. "
                    + "NAVER_HUB_CLIENT_ID/NAVER_HUB_CLIENT_SECRET 를 넣고 backend 를 recreate 할 것", LEGACY_CUTOFF);
            case NONE -> log.warn("[NaverSearch] 네이버 검색 키 없음 — 뉴스 검색·재료 분류가 꺼진다");
        }
    }

    /** 지금 쓰는 경로 — HUB 키 둘 다 있으면 HUB, 아니면 개발자센터 키 둘 다 있으면 LEGACY. */
    public Route route() {
        if (!isBlank(hubClientId) && !isBlank(hubClientSecret)) return Route.HUB;
        if (hasLegacyKeys()) return Route.LEGACY;
        return Route.NONE;
    }

    private boolean hasLegacyKeys() {
        return !isBlank(clientId) && !isBlank(clientSecret);
    }

    /** 이 프로세스가 뜬 뒤 뉴스 검색이 실패한 횟수(진단 호출 제외). */
    public static long failureCount() {
        return FAILURES.get();
    }

    /** 마지막 실패 요약(경로·HTTP 상태·사유) — 키 값은 담지 않는다. 실패가 없었으면 null. */
    public static String lastFailureSummary() {
        return lastFailureSummary;
    }

    /**
     * 뉴스 검색 호출 실패 — '뉴스 0건'과 구분한다(§4c). 재료 분류는 이걸 받으면 NONE 으로 캐시하지 않는다.
     * 메시지에 키 값은 없다.
     */
    public static class SearchFailedException extends RuntimeException {
        private final Route route;
        private final Integer httpStatus;

        public SearchFailedException(Route route, Integer httpStatus, String message) {
            super(message);
            this.route = route;
            this.httpStatus = httpStatus;
        }

        public Route getRoute() { return route; }
        public Integer getHttpStatus() { return httpStatus; }
    }

    /**
     * 뉴스 검색 한 번의 결과 — 경로, 부른 주소(쿼리 없이), HTTP 상태(응답이 없으면 null), 기사, 실패 사유(성공이면 null).
     * 키 값은 담지 않는다.
     */
    public record CallResult(Route route, String endpoint, Integer httpStatus, List<NewsItem> items,
                             String error, long elapsedMs) {
        public boolean ok() { return error == null; }
        public int itemCount() { return items == null ? 0 : items.size(); }
        public String summary() {
            return "경로 " + route + (httpStatus != null ? " · HTTP " + httpStatus : "") + (error != null ? " · " + error : "");
        }
    }

    /**
     * 종목 관련 뉴스 검색 (v5 - 강제 날짜순 + 엄격 필터링)
     *
     * @param stockName 종목명 (예: 삼성전자)
     * @return 뉴스 목록
     */
    public List<NewsItem> searchStockNews(String stockName) {
        if (!isAvailable()) {
            log.warn("[NaverSearch] API Key 미설정");
            return Collections.emptyList();
        }

        // ★★★ 종목명 정규화 ★★★
        String cleanStockName = stockName.trim();
        if (cleanStockName.isEmpty()) {
            log.warn("[NaverSearch] 종목명이 비어있음");
            return Collections.emptyList();
        }

        log.info("========================================");
        log.info("[NaverSearch] ★ 검색 시작: '{}' (정규화: '{}')", stockName, cleanStockName);
        log.info("[NaverSearch] 설정: sort={}, display={}", SORT_DATE, DISPLAY_COUNT);

        // 1. API 호출 (날짜순 고정!) — 호출 실패는 '0건'이 아니라 SearchFailedException(재료 NONE 캐시 금지, §4c)
        List<NewsItem> rawNews = callNaverApi(cleanStockName);
        log.info("[NaverSearch] 검색어: '{}', API 응답: {}건", cleanStockName, rawNews.size());

        if (rawNews.isEmpty()) {
            log.warn("[NaverSearch] API 응답 0건 - 검색 종료");
            return Collections.emptyList();
        }

        // 2. 종목명 포함 필터링 (★ 엄격 필터링 적용 ★)
        List<NewsItem> relevantNews = filterByRelevance(rawNews, cleanStockName);
        log.info("[NaverSearch] 종목명 필터 후: {}건 (원본: {}건)", relevantNews.size(), rawNews.size());

        // 3. 7일 이내 필터링
        List<NewsItem> recentNews = filterByDate(relevantNews);
        log.info("[NaverSearch] 7일 이내 필터 후: {}건", recentNews.size());

        // 4. Fail-safe: 0건이면 종목명 필터된 뉴스 반환 (비관련 뉴스 유입 방지)
        List<NewsItem> result;
        if (recentNews.isEmpty()) {
            if (!relevantNews.isEmpty()) {
                log.warn("[NaverSearch] 7일 이내 뉴스 0건 → 종목명 매칭 뉴스 상위 {}개 반환", FALLBACK_COUNT);
                result = getTopN(relevantNews, FALLBACK_COUNT);
            } else {
                log.warn("[NaverSearch] 종목명 매칭 뉴스 0건 → 빈 목록 반환 (비관련 뉴스 유입 방지)");
                result = Collections.emptyList();
            }
        } else {
            result = getTopN(recentNews, MAX_RESULT_COUNT);
        }

        log.info("[NaverSearch] 최종 결과: {}건", result.size());
        log.info("========================================");

        return result;
    }

    /**
     * 네이버 API 호출 (sort=date 고정!)
     */
    /**
     * 네이버 뉴스 검색 URI 빌드 — 순수 함수(테스트 대상).
     *
     * <p>⚠ 반드시 {@link URI} 를 반환한다(String 아님). RestTemplate.exchange 는 <b>String</b> 인자를
     * URI 템플릿으로 보고 <b>한 번 더 인코딩</b>하므로, 이미 인코딩된 문자열을 넘기면 {@code %} → {@code %25}
     * 이중 인코딩이 된다. 반면 {@code URI} 인자는 있는 그대로 전송(재인코딩 없음).
     *
     * <p>이력(2026-07-01): 원래 {@code URLEncoder.encode()} 수동 + {@code build(false)} 였고, 최종적으로
     * RestTemplate 이 String 을 재인코딩 → 이중 인코딩. 네이버가 한글을 못 알아들어 <b>종목과 무관한</b>
     * 최근 뉴스를 반환 → 종목명 필터가 전부 탈락 → 재료 100% NONE. 여기서 UTF-8 로 1회 인코딩한
     * {@code URI} 를 그대로 넘겨 해결. 한글 쿼리는 {@code query=%EC…}(%25 없음)여야 정상.
     */
    static URI buildSearchUrl(String query) {
        return buildSearchUrl(Route.LEGACY, LEGACY_NEWS_URL, query);
    }

    /**
     * 경로별 검색 URI — 두 경로 모두 query·display·start·sort 가 같다(HUB 문서: display 1~100, start 1~1000,
     * sort sim|date). HUB 는 문서 예시대로 {@code format=json} 을 붙인다(기본값도 json). 반환은 반드시 {@link URI}.
     */
    static URI buildSearchUrl(Route route, String baseUrl, String query) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("query", query)          // 원문 — encode() 가 1회만 인코딩
                .queryParam("display", DISPLAY_COUNT)
                .queryParam("start", 1)
                .queryParam("sort", SORT_DATE);      // ★★★ 절대 date 고정! ★★★
        if (route == Route.HUB) b.queryParam("format", "json");
        return b.encode(StandardCharsets.UTF_8).build().toUri();
    }

    /** 경로별 인증 헤더 — 그 경로의 키 한 벌만 싣는다(두 벌을 같이 보내지 않는다). */
    HttpHeaders buildHeaders(Route route) {
        HttpHeaders headers = new HttpHeaders();
        if (route == Route.HUB) {
            headers.set(HUB_KEY_ID_HEADER, hubClientId);
            headers.set(HUB_KEY_HEADER, hubClientSecret);
        } else {
            headers.set(LEGACY_ID_HEADER, clientId);
            headers.set(LEGACY_SECRET_HEADER, clientSecret);
        }
        headers.set("Accept", "application/json");
        return headers;
    }

    /** 검색 호출 — 실패면 누계·로그를 남기고 {@link SearchFailedException}(빈 목록으로 바꾸지 않는다). */
    private List<NewsItem> callNaverApi(String query) {
        CallResult r = fetch(query);
        if (!r.ok()) {
            recordFailure(r);
            throw new SearchFailedException(r.route(), r.httpStatus(), "네이버 뉴스 검색 실패 — " + r.summary());
        }
        recordSuccess(r);
        return r.items();
    }

    /**
     * 진단용 한 번 호출({@code GET /api/diagnostics/naver-search}) — 실패 누계·로그 상태는 건드리지 않는다
     * (08:00 워밍의 배치 모니터 판정과 섞이지 않게). 결과에 키 값은 없다.
     */
    public CallResult probe(String query) {
        CallResult r = fetch(isBlank(query) ? "삼성전자" : query.trim());
        log.info("[NaverSearch] 진단 호출 — {} · 기사 {}건 · {}ms", r.summary(), r.itemCount(), r.elapsedMs());
        return r;
    }

    /**
     * 진단 응답 — 어느 경로로 불렀는지·HTTP 상태·기사 수. 키는 '있다/없다'만 싣고 값은 싣지 않는다.
     * 기사 수는 종목명·7일 필터 전 원본 건수(검색 API 가 돌려준 수)다.
     */
    public Map<String, Object> probeReport(String query) {
        CallResult r = probe(query);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("route", r.route().name());
        out.put("hubKeysConfigured", !isBlank(hubClientId) && !isBlank(hubClientSecret));
        out.put("legacyKeysConfigured", hasLegacyKeys());
        out.put("endpoint", r.endpoint());
        out.put("query", isBlank(query) ? "삼성전자" : query.trim());
        out.put("httpStatus", r.httpStatus());
        out.put("itemCount", r.itemCount());
        out.put("ok", r.ok());
        out.put("error", r.error());
        out.put("elapsedMs", r.elapsedMs());
        out.put("legacyCutoff", LEGACY_CUTOFF);
        out.put("failuresSinceBoot", failureCount());
        out.put("lastFailure", lastFailureSummary());
        String note = switch (r.route()) {
            case HUB -> r.ok() ? "NAVER API HUB 로 정상 — 개발자센터 키(NAVER_CLIENT_ID/SECRET)는 쓰지 않는다" : null;
            case LEGACY -> "개발자센터 키로 부르는 중 — " + LEGACY_CUTOFF
                    + " 부터 막힌다. NAVER_HUB_CLIENT_ID/NAVER_HUB_CLIENT_SECRET 를 넣고 backend 를 recreate";
            case NONE -> "네이버 검색 키가 없다 — 뉴스 검색·재료 분류가 꺼져 있다";
        };
        out.put("note", note);
        return out;
    }

    /** HTTP 한 번 — 실패도 결과로 돌려준다(던지지 않는다). 2xx 라도 200 이 아니거나 본문이 기대 모양이 아니면 실패. */
    CallResult fetch(String query) {
        Route route = route();
        if (route == Route.NONE) return new CallResult(Route.NONE, null, null, List.of(), "키 없음", 0);
        String base = route == Route.HUB ? hubNewsUrl : LEGACY_NEWS_URL;
        long t0 = System.nanoTime();
        int status;
        String body;
        try {
            URI url = buildSearchUrl(route, base, query);
            log.debug("[NaverSearch] API URL: {}", url);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(route)), String.class);
            status = response.getStatusCode().value();
            body = response.getBody();
        } catch (RestClientResponseException e) {   // 4xx·5xx — 응답은 왔다
            int s = e.getStatusCode().value();
            String snippet = snippet(e.getResponseBodyAsString());
            return new CallResult(route, base, s, List.of(),
                    redact(describeHttpError(route, s) + (snippet.isEmpty() ? "" : " · 응답 " + snippet)), elapsedMs(t0));
        } catch (Exception e) {                      // 연결·시간 초과 등 — 응답 없음
            return new CallResult(route, base, null, List.of(),
                    redact("응답 없음 — " + e.getClass().getSimpleName() + ": " + snippet(e.getMessage())), elapsedMs(t0));
        }
        if (status != 200) {
            return new CallResult(route, base, status, List.of(), redact("200 이 아닌 응답 · 응답 " + snippet(body)), elapsedMs(t0));
        }
        try {
            return new CallResult(route, base, status, parseNewsResponse(body), null, elapsedMs(t0));
        } catch (Exception e) {
            return new CallResult(route, base, status, List.of(),
                    redact("본문 형식이 다르다(" + e.getMessage() + ") · 응답 " + snippet(body)), elapsedMs(t0));
        }
    }

    /** HTTP 오류 상태의 뜻 — HUB 문서의 공통 응답 상태 코드(401 엔 'Application 에 API 권한 없음'도 들어 있다). */
    static String describeHttpError(Route route, int status) {
        String cutoff = route == Route.LEGACY ? " (개발자센터 키는 " + LEGACY_CUTOFF + " 부터 막힌다)" : "";
        if (status == 401) {
            return route == Route.HUB
                    ? "인증 실패 — HUB 키가 틀렸거나 HUB Application 에 검색(뉴스) API 가 추가되지 않았다"
                    : "인증 실패 — 개발자센터 키가 틀렸거나 막혔다" + cutoff;
        }
        if (status == 403) return "허용되지 않은 호출" + cutoff;
        if (status == 429) return "호출 한도 초과";
        if (status >= 500) return "네이버 서버 오류";
        return "요청 오류";
    }

    private void recordFailure(CallResult r) {
        FAILURES.incrementAndGet();
        lastFailureSummary = r.summary();
        String state = r.route() + ":" + (r.httpStatus() != null ? r.httpStatus() : "NO_RESPONSE");
        if (!state.equals(lastLoggedState)) {
            lastLoggedState = state;
            log.error("[NaverSearch] API 호출 실패 — {} (같은 실패가 이어지는 동안은 DEBUG)", r.summary());
        } else {
            log.debug("[NaverSearch] API 호출 실패(이어짐) — {}", r.summary());
        }
    }

    private void recordSuccess(CallResult r) {
        if (!"OK".equals(lastLoggedState)) {
            lastLoggedState = "OK";
            log.info("[NaverSearch] API 호출 복구 — 경로 {} · HTTP {}", r.route(), r.httpStatus());
        }
    }

    /** 오류 문구에 키 값이 섞여 있으면 가린다(응답 본문이 키를 되돌려 주는 경우 대비). */
    private String redact(String text) {
        if (text == null) return null;
        String out = text;
        for (String secret : new String[]{clientId, clientSecret, hubClientId, hubClientSecret}) {
            if (secret != null && secret.length() >= 4) out = out.replace(secret, "***");
        }
        return out;
    }

    private static String snippet(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 200 ? one : one.substring(0, 200) + "…";
    }

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * 뉴스 응답 파싱 — {@code items} 배열이 없으면 실패로 던진다(HUB 는 API Gateway 라 오류 모양이 다를 수 있다 —
     * 그걸 '기사 0건'으로 읽지 않게). 빈 배열은 진짜 0건.
     */
    private List<NewsItem> parseNewsResponse(String json) throws Exception {
        List<NewsItem> result = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode items = root == null ? null : root.get("items");
        if (items == null || !items.isArray()) throw new IllegalArgumentException("items 배열 없음");

        for (JsonNode item : items) {
            String pubDate = getTextValue(item, "pubDate");

            NewsItem news = NewsItem.builder()
                    .title(cleanHtml(getTextValue(item, "title")))
                    .description(cleanHtml(getTextValue(item, "description")))
                    .link(getTextValue(item, "link"))
                    .originalLink(getTextValue(item, "originallink"))
                    .pubDate(pubDate)
                    .build();

            result.add(news);
        }
        return result;
    }

    /**
     * 종목명 포함 + 비관련 제외 필터링 (강화된 필터링)
     *
     * ★★★ 핵심 규칙 ★★★
     * 1. 제목에 종목명이 반드시 포함되어야 함 (본문만 포함은 제외)
     * 2. 다른 종목명이 제목에 있으면 제외 (삼성SDI, 삼성물산 등)
     * 3. 비관련 키워드(연예, 스포츠 등) 제외
     */
    private List<NewsItem> filterByRelevance(List<NewsItem> newsList, String stockName) {
        String stockNameLower = stockName.toLowerCase().trim();
        List<NewsItem> result = new ArrayList<>();

        // 관련 없는 종목 키워드 (삼성전자 검색 시 제외할 키워드들)
        List<String> competitorKeywords = getCompetitorKeywords(stockName);

        for (NewsItem news : newsList) {
            String title = news.getTitle() != null ? news.getTitle().toLowerCase() : "";
            String desc = news.getDescription() != null ? news.getDescription().toLowerCase() : "";

            // ★ 제목에 종목명이 반드시 포함되어야 함
            if (!title.contains(stockNameLower)) {
                log.trace("[NaverSearch] 제외 (제목에 종목명 없음): {}", news.getTitle());
                continue;
            }

            // ★ 다른 종목 키워드가 제목에 있으면 제외
            boolean hasCompetitor = false;
            for (String comp : competitorKeywords) {
                if (title.contains(comp.toLowerCase())) {
                    log.trace("[NaverSearch] 제외 (경쟁 종목): {} - {}", comp, news.getTitle());
                    hasCompetitor = true;
                    break;
                }
            }
            if (hasCompetitor) continue;

            // 비관련 키워드 제외
            boolean excluded = false;
            for (String kw : EXCLUDE_KEYWORDS) {
                if (title.contains(kw)) {
                    log.trace("[NaverSearch] 제외 (비관련 키워드 {}): {}", kw, news.getTitle());
                    excluded = true;
                    break;
                }
            }

            if (!excluded) {
                log.debug("[NaverSearch] ✓ 포함: {}", news.getTitle());
                result.add(news);
            }
        }

        return result;
    }

    /**
     * 종목별 경쟁/관련 종목 키워드 (제외 대상)
     */
    private List<String> getCompetitorKeywords(String stockName) {
        // 삼성전자 검색 시 다른 삼성 계열사 제외
        if (stockName.contains("삼성전자")) {
            return Arrays.asList("삼성SDI", "삼성물산", "삼성생명", "삼성화재",
                    "삼성바이오", "삼성엔지", "삼성카드", "삼성증권", "삼성SDS");
        }
        // 현대차 검색 시 다른 현대 계열사 제외
        if (stockName.contains("현대차") || stockName.contains("현대자동차")) {
            return Arrays.asList("현대건설", "현대중공업", "현대모비스", "현대제철",
                    "현대글로비스", "현대위아", "현대로템", "현대에너지솔루션");
        }
        // SK하이닉스 검색 시 다른 SK 계열사 제외
        if (stockName.contains("SK하이닉스")) {
            return Arrays.asList("SK텔레콤", "SK이노베이션", "SK바이오팜",
                    "SK스퀘어", "SKC", "SK네트웍스");
        }
        // SK스퀘어 검색 시 다른 SK/삼성 계열사 제외
        if (stockName.contains("SK스퀘어")) {
            return Arrays.asList("SK하이닉스", "SK텔레콤", "SK이노베이션",
                    "삼성전자", "삼성SDI", "삼성물산");
        }
        // LG전자 검색 시 다른 LG 계열사 제외
        if (stockName.contains("LG전자")) {
            return Arrays.asList("LG화학", "LG에너지솔루션", "LG디스플레이",
                    "LG유플러스", "LG이노텍", "LG생활건강");
        }
        return Collections.emptyList();
    }

    /**
     * 7일 이내 필터링 (날짜 파싱 실패 시 살려둠!)
     */
    private List<NewsItem> filterByDate(List<NewsItem> newsList) {
        LocalDate cutoff = LocalDate.now().minusDays(MAX_NEWS_AGE_DAYS);
        List<NewsItem> result = new ArrayList<>();

        for (NewsItem news : newsList) {
            LocalDate articleDate = parsePubDate(news.getPubDate());

            // 파싱 성공: 날짜 비교
            if (articleDate != null) {
                if (!articleDate.isBefore(cutoff)) {
                    result.add(news);
                } else {
                    log.trace("[NaverSearch] 제외 (오래된 기사): {}", articleDate);
                }
            } else {
                // 파싱 실패: 살려둠! (Fail-safe)
                log.debug("[NaverSearch] 날짜 파싱 실패 → 포함 처리: {}", news.getPubDate());
                result.add(news);
            }
        }

        return result;
    }

    /**
     * pubDate 파싱 (Locale.ENGLISH 사용!)
     *
     * @return LocalDate 또는 null (파싱 실패 시)
     */
    /**
     * 재료(오늘의 촉매) 분류용 — 기준일까지 {@code days} 일 안의 기사만, <b>날짜를 모르는 기사는 뺀다</b>. 순수 함수(2026-10-03).
     *
     * <p>{@link #searchStockNews} 는 7일 안 기사가 없으면 '종목명 매칭 상위 N개'를 날짜와 무관하게 돌려준다(목록 표시용 —
     * 기사마다 날짜가 붙는다). 재료 분류가 그걸 그대로 받아 몇 주 묵은 기사를 '오늘의 재료'로 저장·호재 알림·시그널 스냅샷에
     * 남겼다. 재료는 이 필터를 거친 기사로만 판단한다.
     */
    public static List<NewsItem> withinDays(List<NewsItem> news, LocalDate today, int days) {
        List<NewsItem> out = new ArrayList<>();
        if (news == null || today == null) return out;
        LocalDate cutoff = today.minusDays(days);
        for (NewsItem n : news) {
            LocalDate d = parsePubDate(n == null ? null : n.getPubDate());
            if (d != null && !d.isBefore(cutoff) && !d.isAfter(today)) out.add(n);
        }
        return out;
    }

    /** 재료 분류의 기사 창(일) — 목록 필터와 같은 7일. */
    public static final int CATALYST_NEWS_DAYS = MAX_NEWS_AGE_DAYS;

    private static LocalDate parsePubDate(String pubDate) {
        if (pubDate == null || pubDate.isEmpty()) {
            return null;
        }

        try {
            // RFC 1123 형식: "Mon, 10 Feb 2025 09:00:00 +0900"
            ZonedDateTime zdt = ZonedDateTime.parse(pubDate, RFC_1123_FORMATTER);
            return zdt.toLocalDate();
        } catch (Exception e) {
            log.debug("[NaverSearch] pubDate 파싱 실패: '{}' - {}", pubDate, e.getMessage());
            return null;  // null 반환 → 호출부에서 살려줌
        }
    }

    /**
     * 상위 N개 추출
     */
    private List<NewsItem> getTopN(List<NewsItem> list, int n) {
        if (list == null || list.isEmpty()) return Collections.emptyList();
        return list.subList(0, Math.min(n, list.size()));
    }

    // ========== 유틸리티 메서드 ==========

    private String cleanHtml(String html) {
        if (html == null) return null;
        return html
                .replaceAll("<[^>]*>", "")
                .replaceAll("&quot;", "\"")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&nbsp;", " ")
                .trim();
    }

    private String getTextValue(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    /** 뉴스 소스 가용 = 어느 한 경로의 키가 다 있다({@link #route()} ≠ NONE). 호출 성공 여부와는 별개. */
    public boolean isAvailable() {
        return route() != Route.NONE;
    }

    // ========== 네이버 금융 종목별 뉴스 크롤링 ==========

    private static final String NAVER_FINANCE_NEWS_URL = "https://finance.naver.com/item/news_news.naver?code=%s&page=1";
    private static final String NAVER_FINANCE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int FINANCE_NEWS_COUNT = 5;

    /**
     * 네이버 금융 종목별 뉴스 크롤링 (종목코드 기반)
     * URL: https://finance.naver.com/item/news_news.naver?code={stockCode}
     * 이 페이지는 해당 종목과 직접 관련된 금융 뉴스만 표시
     *
     * @param stockCode 종목코드 (6자리, 예: 005930)
     * @return 종목 관련 금융 뉴스 (최대 5건)
     */
    public List<NewsItem> searchStockNewsByCode(String stockCode) {
        if (stockCode == null || stockCode.trim().isEmpty()) {
            log.warn("[NaverFinanceNews] 종목코드가 비어있음");
            return Collections.emptyList();
        }

        String code = stockCode.trim();
        log.info("[NaverFinanceNews] 종목 {} 금융 뉴스 크롤링 시작", code);

        try {
            String url = String.format(NAVER_FINANCE_NEWS_URL, code);

            Document doc = Jsoup.connect(url)
                    .userAgent(NAVER_FINANCE_USER_AGENT)
                    .referrer("https://finance.naver.com/item/news.naver?code=" + code)
                    .timeout(10000)
                    .get();

            List<NewsItem> result = new ArrayList<>();

            // 네이버 금융 뉴스 테이블: <table class="type5">
            Elements rows = doc.select("table.type5 tbody tr");
            if (rows.isEmpty()) {
                // 대체 셀렉터
                rows = doc.select("table.type5 tr");
            }

            for (Element row : rows) {
                if (result.size() >= FINANCE_NEWS_COUNT) break;

                // 관련종목 헤더 행이나 빈 행 스킵
                Element titleTd = row.selectFirst("td.title");
                if (titleTd == null) continue;

                Element titleLink = titleTd.selectFirst("a");
                if (titleLink == null) continue;

                String title = titleLink.text().trim();
                if (title.isEmpty()) continue;

                // 링크 구성
                String href = titleLink.attr("href");
                String newsLink = href.startsWith("http") ? href : "https://finance.naver.com" + href;

                // 출처
                Element infoTd = row.selectFirst("td.info");
                String source = infoTd != null ? infoTd.text().trim() : "";

                // 날짜
                Element dateTd = row.selectFirst("td.date");
                String dateStr = dateTd != null ? dateTd.text().trim() : "";

                NewsItem news = NewsItem.builder()
                        .title(title)
                        .description(source)
                        .link(newsLink)
                        .originalLink(newsLink)
                        .pubDate(dateStr)
                        .build();

                result.add(news);
                log.debug("[NaverFinanceNews] ✓ {}: {} ({})", result.size(), title, dateStr);
            }

            log.info("[NaverFinanceNews] 종목 {} 뉴스 크롤링 완료: {}건", code, result.size());
            return result;

        } catch (Exception e) {
            log.warn("[NaverFinanceNews] 종목 {} 뉴스 크롤링 실패: {}", code, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ========== 외부 인터페이스 ==========
    // (구 searchNews(query, display) 는 제거 — display 인자를 무시하고 종목명/7일 필터도 우회한
    //  원시 30건을 반환하는 오용 유도 인터페이스였고 프로덕션 호출자가 없었다.
    //  뉴스가 필요하면 필터링된 searchStockNews/searchStockNewsByCode 를 쓸 것.)

    /**
     * 위험 뉴스 개수
     */
    public int countRiskNews(List<NewsItem> newsList) {
        int count = 0;
        for (NewsItem news : newsList) {
            String content = (news.getTitle() + " " + news.getDescription()).toLowerCase();
            for (String kw : RISK_KEYWORDS) {
                if (content.contains(kw)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /**
     * AI 분석용 텍스트 변환
     */
    public String formatNewsForAi(List<NewsItem> newsList) {
        if (newsList == null || newsList.isEmpty()) {
            return "관련 뉴스가 없습니다.";
        }

        StringBuilder sb = new StringBuilder();
        int idx = 1;

        for (NewsItem news : newsList) {
            sb.append(String.format("[뉴스 %d]\n", idx));
            sb.append("제목: ").append(news.getTitle()).append("\n");
            if (news.getDescription() != null && !news.getDescription().isEmpty()) {
                sb.append("내용: ").append(news.getDescription()).append("\n");
            }
            sb.append("발행일: ").append(news.getPubDate()).append("\n\n");
            idx++;
        }

        return sb.toString();
    }
}
