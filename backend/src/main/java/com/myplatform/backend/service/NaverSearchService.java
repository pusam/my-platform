package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.RiskAnalysisDto.NewsItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
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
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 종목 관련 뉴스 검색 (v5 - 강제 날짜순)
     *
     * @param stockName 종목명
     * @return 뉴스 목록
     */
    public List<NewsItem> searchStockNews(String stockName) {
        if (!isAvailable()) {
            log.warn("[NaverSearch] API Key 미설정");
            return Collections.emptyList();
        }

        log.info("========================================");
        log.info("[NaverSearch] 검색 시작: '{}'", stockName);
        log.info("[NaverSearch] 설정: sort={}, display={}", SORT_DATE, DISPLAY_COUNT);

        // 1. API 호출 (날짜순 고정!)
        List<NewsItem> rawNews = callNaverApi(stockName);
        log.info("[NaverSearch] 검색어: '{}', API 응답: {}건", stockName, rawNews.size());

        if (rawNews.isEmpty()) {
            log.warn("[NaverSearch] API 응답 0건 - 검색 종료");
            return Collections.emptyList();
        }

        // 2. 종목명 포함 필터링
        List<NewsItem> relevantNews = filterByRelevance(rawNews, stockName);
        log.info("[NaverSearch] 종목명 필터 후: {}건 (원본: {}건)", relevantNews.size(), rawNews.size());

        // 3. 7일 이내 필터링
        List<NewsItem> recentNews = filterByDate(relevantNews);
        log.info("[NaverSearch] 7일 이내 필터 후: {}건", recentNews.size());

        // 4. Fail-safe: 0건이면 원본 상위 N개 반환
        List<NewsItem> result;
        if (recentNews.isEmpty()) {
            log.warn("[NaverSearch] ⚠️ 7일 이내 뉴스 0건 → Fail-safe 발동: 원본 상위 {}개 반환", FALLBACK_COUNT);
            result = getTopN(relevantNews.isEmpty() ? rawNews : relevantNews, FALLBACK_COUNT);
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
    private List<NewsItem> callNaverApi(String query) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

            String url = UriComponentsBuilder.fromUriString(NAVER_SEARCH_URL)
                    .queryParam("query", encodedQuery)
                    .queryParam("display", DISPLAY_COUNT)
                    .queryParam("start", 1)
                    .queryParam("sort", SORT_DATE)  // ★★★ 절대 date 고정! ★★★
                    .build(false)
                    .toUriString();

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
     * 종목명 포함 + 비관련 제외 필터링
     */
    private List<NewsItem> filterByRelevance(List<NewsItem> newsList, String stockName) {
        String stockNameLower = stockName.toLowerCase();
        List<NewsItem> result = new ArrayList<>();

        for (NewsItem news : newsList) {
            String title = news.getTitle() != null ? news.getTitle().toLowerCase() : "";
            String desc = news.getDescription() != null ? news.getDescription().toLowerCase() : "";
            String content = title + " " + desc;

            // 종목명 포함 여부
            if (!content.contains(stockNameLower)) {
                continue;
            }

            // 비관련 키워드 제외
            boolean excluded = false;
            for (String kw : EXCLUDE_KEYWORDS) {
                if (title.contains(kw)) {
                    excluded = true;
                    break;
                }
            }

            if (!excluded) {
                result.add(news);
            }
        }

        return result;
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

    // ========== 외부 인터페이스 ==========

    /**
     * 뉴스 검색 (외부 호출용 - 날짜순 고정!)
     */
    public List<NewsItem> searchNews(String query, int display) {
        // display 무시하고 DISPLAY_COUNT 사용
        return callNaverApi(query);
    }

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
