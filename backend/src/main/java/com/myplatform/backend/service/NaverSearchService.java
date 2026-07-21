package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.RiskAnalysisDto.NewsItem;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 네이버 검색 API 연동 서비스
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

    @Value("${naver.api.client-id:}")
    private String clientId;

    @Value("${naver.api.client-secret:}")
    private String clientSecret;

    private static final String NAVER_SEARCH_URL = "https://openapi.naver.com/v1/search/news.json";

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

    public NaverSearchService() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = new ObjectMapper();
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

        // 1. API 호출 (날짜순 고정!)
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
        return UriComponentsBuilder.fromUriString(NAVER_SEARCH_URL)
                .queryParam("query", query)          // 원문 — encode() 가 1회만 인코딩
                .queryParam("display", DISPLAY_COUNT)
                .queryParam("start", 1)
                .queryParam("sort", SORT_DATE)       // ★★★ 절대 date 고정! ★★★
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();
    }

    private List<NewsItem> callNaverApi(String query) {
        try {
            URI url = buildSearchUrl(query);

            log.debug("[NaverSearch] API URL: {}", url);

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Naver-Client-Id", clientId);
            headers.set("X-Naver-Client-Secret", clientSecret);
            headers.set("Accept", "application/json");

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseNewsResponse(response.getBody());
            }

            log.warn("[NaverSearch] API 응답 오류: {}", response.getStatusCode());
            return Collections.emptyList();

        } catch (Exception e) {
            log.error("[NaverSearch] API 호출 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 뉴스 응답 파싱
     */
    private List<NewsItem> parseNewsResponse(String json) {
        List<NewsItem> result = new ArrayList<>();

        try {
            JsonNode items = objectMapper.readTree(json).get("items");
            if (items == null || !items.isArray()) return result;

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
        } catch (Exception e) {
            log.error("[NaverSearch] JSON 파싱 실패: {}", e.getMessage());
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
    private LocalDate parsePubDate(String pubDate) {
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

    public boolean isAvailable() {
        return clientId != null && !clientId.isEmpty()
                && clientSecret != null && !clientSecret.isEmpty();
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
