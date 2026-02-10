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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 네이버 검색 API 연동 서비스
 *
 * [기능]
 * - 특정 종목의 최신 뉴스 검색
 * - 7일 이내 기사 우선, 없으면 원본 반환 (Fail-safe)
 *
 * [검색 전략 v4 - Simple & Safe]
 * 1. 검색어: 종목명만 사용 (수식어 없음)
 * 2. 정렬: sim (정확도순) - 관련성 높은 기사 우선
 * 3. 7일 이내 기사 필터링 시도
 * 4. Fail-safe: 필터링 후 0건이면 원본 상위 5개 반환
 */
@Service
@Slf4j
public class NaverSearchService {

    @Value("${naver.api.client-id:}")
    private String clientId;

    @Value("${naver.api.client-secret:}")
    private String clientSecret;

    private static final String NAVER_SEARCH_URL = "https://openapi.naver.com/v1/search/news.json";

    // 리스크 관련 키워드 (countRiskNews에서 사용)
    private static final List<String> RISK_KEYWORDS = Arrays.asList(
            "악재", "검찰", "횡령", "배임", "수사", "기소",
            "적자", "손실", "하락", "폭락", "실적악화", "공매도"
    );

    // 최신 기사 필터링 기준 (일)
    private static final int MAX_NEWS_AGE_DAYS = 7;

    // Fail-safe: 필터링 후 0건일 때 반환할 최소 개수
    private static final int FALLBACK_NEWS_COUNT = 5;

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
     * 종목 관련 뉴스 검색 (Simple & Safe v4)
     *
     * [검색 전략]
     * 1. 검색어: 종목명만 사용 (수식어 없음)
     * 2. 정렬: sim (정확도순) - 관련성 높은 기사 우선
     * 3. 7일 이내 기사 필터링 시도
     * 4. Fail-safe: 필터링 후 0건이면 원본 상위 5개 반환
     *
     * @param stockName 종목명
     * @return 뉴스 목록 (최소 5개 보장)
     */
    public List<NewsItem> searchStockNews(String stockName) {
        if (!isAvailable()) {
            log.warn("[NaverSearch] API Key가 설정되지 않았습니다.");
            return Collections.emptyList();
        }

        log.info("[NaverSearch] '{}' 종목 뉴스 검색 시작 (Simple & Safe v4)", stockName);

        // 1. 종목명으로만 검색 (정확도순, 30개)
        List<NewsItem> rawNews = searchNewsWithSort(stockName, 30, "sim");
        log.info("[NaverSearch] API 원본 응답: {}건", rawNews.size());

        if (rawNews.isEmpty()) {
            log.warn("[NaverSearch] '{}' 검색 결과 없음 (API 응답 0건)", stockName);
            return Collections.emptyList();
        }

        // 2. 종목명 포함 필터링 (날짜 필터링 전)
        List<NewsItem> relevantNews = filterByStockName(rawNews, stockName);
        log.info("[NaverSearch] 종목명 필터링 후: {}건", relevantNews.size());

        // 3. 7일 이내 기사 필터링
        List<NewsItem> recentNews = filterRecentNews(relevantNews, MAX_NEWS_AGE_DAYS);
        log.info("[NaverSearch] 7일 이내 필터링 후: {}건", recentNews.size());

        // 4. Fail-safe: 최신 뉴스가 0건이면 원본에서 상위 N개 반환
        List<NewsItem> result;
        if (recentNews.isEmpty()) {
            log.warn("[NaverSearch] 7일 이내 뉴스 0건 → Fail-safe: 원본 상위 {}개 반환", FALLBACK_NEWS_COUNT);
            result = relevantNews.stream()
                    .limit(FALLBACK_NEWS_COUNT)
                    .collect(Collectors.toList());
        } else {
            // 최신순 정렬 후 최대 15개
            result = recentNews.stream()
                    .sorted((a, b) -> comparePubDate(b.getPubDate(), a.getPubDate()))
                    .limit(15)
                    .collect(Collectors.toList());
        }

        log.info("[NaverSearch] '{}' 뉴스 검색 완료: 최종 {}건", stockName, result.size());
        return result;
    }

    /**
     * 종목명 포함 여부로 필터링 (비관련 기사 제외)
     */
    private List<NewsItem> filterByStockName(List<NewsItem> newsList, String stockName) {
        String stockNameLower = stockName.toLowerCase();

        return newsList.stream()
                .filter(news -> {
                    String title = news.getTitle() != null ? news.getTitle().toLowerCase() : "";
                    String description = news.getDescription() != null ? news.getDescription().toLowerCase() : "";
                    String content = title + " " + description;

                    // 종목명 포함 체크
                    if (!content.contains(stockNameLower)) {
                        return false;
                    }

                    // 비관련 키워드 제외
                    for (String exclude : Arrays.asList("연예", "아이돌", "드라마", "야구", "축구", "농구", "운세", "로또")) {
                        if (title.contains(exclude)) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * N일 이내 기사만 필터링
     */
    private List<NewsItem> filterRecentNews(List<NewsItem> newsList, int days) {
        return newsList.stream()
                .filter(news -> isWithinDays(news.getPubDate(), days))
                .collect(Collectors.toList());
    }

    /**
     * pubDate 비교 (최신순 정렬용)
     */
    private int comparePubDate(String date1, String date2) {
        try {
            if (date1 == null || date2 == null) return 0;
            ZonedDateTime zdt1 = ZonedDateTime.parse(date1, RFC_1123_FORMATTER);
            ZonedDateTime zdt2 = ZonedDateTime.parse(date2, RFC_1123_FORMATTER);
            return zdt1.compareTo(zdt2);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * pubDate가 N일 이내인지 확인
     * 네이버 API pubDate 형식: "Mon, 10 Feb 2025 09:00:00 +0900"
     *
     * ⚠️ 중요: Locale.ENGLISH 설정 필수! (영문 요일/월 파싱)
     */
    private boolean isWithinDays(String pubDate, int days) {
        if (pubDate == null || pubDate.isEmpty()) {
            log.debug("[NaverSearch] pubDate가 null 또는 빈 문자열 → 포함 처리");
            return true;  // 날짜 없으면 일단 포함
        }

        try {
            // RFC 1123 형식 파싱 (Locale.ENGLISH 필수!)
            ZonedDateTime articleDate = ZonedDateTime.parse(pubDate, RFC_1123_FORMATTER);
            LocalDate articleLocalDate = articleDate.toLocalDate();
            LocalDate cutoffDate = LocalDate.now().minusDays(days);

            boolean isRecent = !articleLocalDate.isBefore(cutoffDate);
            if (!isRecent) {
                log.trace("[NaverSearch] 오래된 기사: {} (기준: {} 이후)", articleLocalDate, cutoffDate);
            }
            return isRecent;

        } catch (Exception e) {
            // 파싱 실패 시 일단 포함 (Fail-safe)
            log.warn("[NaverSearch] pubDate 파싱 실패 (포함 처리): '{}' - {}", pubDate, e.getMessage());
            return true;
        }
    }

    /**
     * 문자열 자르기 (로그용)
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }

    /**
     * 뉴스 검색 API 호출 (기본: 최신순)
     *
     * @param query 검색어
     * @param display 결과 수 (최대 100)
     * @return 뉴스 목록
     */
    public List<NewsItem> searchNews(String query, int display) {
        return searchNewsWithSort(query, display, "date");
    }

    /**
     * 뉴스 검색 API 호출 (정렬 옵션 지정)
     *
     * @param query 검색어
     * @param display 결과 수 (최대 100)
     * @param sort 정렬 방식 ("sim": 정확도순, "date": 최신순)
     * @return 뉴스 목록
     */
    public List<NewsItem> searchNewsWithSort(String query, int display, String sort) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

            // API URL 구성
            String url = UriComponentsBuilder.fromUriString(NAVER_SEARCH_URL)
                    .queryParam("query", encodedQuery)
                    .queryParam("display", Math.min(display, 100)) // 최대 100개
                    .queryParam("start", 1)
                    .queryParam("sort", sort)  // sim: 정확도순, date: 최신순
                    .build(false)  // 이미 인코딩됨
                    .toUriString();

            log.debug("[NaverSearch] API 호출: query='{}', display={}, sort={}", query, display, sort);

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Naver-Client-Id", clientId);
            headers.set("X-Naver-Client-Secret", clientSecret);
            headers.set("Accept", "application/json");

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                List<NewsItem> news = parseNewsResponse(response.getBody());
                log.debug("[NaverSearch] API 응답: query='{}', sort='{}' → {}건 반환", query, sort, news.size());
                return news;
            } else {
                log.warn("[NaverSearch] API 응답 오류: status={}", response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("[NaverSearch] 뉴스 검색 실패 (query={}, sort={}): {}", query, sort, e.getMessage());
        }

        return Collections.emptyList();
    }

    /**
     * 뉴스 검색 응답 파싱
     */
    private List<NewsItem> parseNewsResponse(String jsonResponse) {
        List<NewsItem> newsList = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode items = root.get("items");

            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    NewsItem news = NewsItem.builder()
                            .title(cleanHtml(getTextValue(item, "title")))
                            .description(cleanHtml(getTextValue(item, "description")))
                            .link(getTextValue(item, "link"))
                            .originalLink(getTextValue(item, "originallink"))
                            .pubDate(getTextValue(item, "pubDate"))
                            .build();

                    newsList.add(news);
                }
            }

        } catch (Exception e) {
            log.error("[NaverSearch] 응답 파싱 실패: {}", e.getMessage());
        }

        return newsList;
    }

    /**
     * HTML 태그 및 특수문자 제거
     */
    private String cleanHtml(String html) {
        if (html == null) return null;
        return html
                .replaceAll("<[^>]*>", "")           // HTML 태그 제거
                .replaceAll("&quot;", "\"")          // 특수문자 변환
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&nbsp;", " ")
                .trim();
    }

    /**
     * 뉴스에서 위험 키워드 포함 여부 확인
     *
     * @param newsList 뉴스 목록
     * @return 위험 키워드 포함된 뉴스 수
     */
    public int countRiskNews(List<NewsItem> newsList) {
        int count = 0;
        for (NewsItem news : newsList) {
            String content = (news.getTitle() + " " + news.getDescription()).toLowerCase();
            for (String keyword : RISK_KEYWORDS) {
                if (content.contains(keyword)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /**
     * 뉴스를 AI 분석용 텍스트로 변환
     * - AI에게 전달되는 텍스트를 로그로 기록
     */
    public String formatNewsForAi(List<NewsItem> newsList) {
        if (newsList == null || newsList.isEmpty()) {
            log.warn("[NaverSearch→AI] 뉴스 데이터 없음 - AI 분석 불가");
            return "관련 뉴스가 없습니다.";
        }

        StringBuilder sb = new StringBuilder();
        int index = 1;

        log.info("[NaverSearch→AI] AI 분석용 뉴스 {}건 변환 시작", newsList.size());

        for (NewsItem news : newsList) {
            sb.append(String.format("[뉴스 %d]\n", index));
            sb.append("제목: ").append(news.getTitle()).append("\n");

            if (news.getDescription() != null && !news.getDescription().isEmpty()) {
                sb.append("내용: ").append(news.getDescription()).append("\n");
            }

            sb.append("발행일: ").append(news.getPubDate()).append("\n\n");

            // 개별 뉴스 로그 (디버그용)
            log.info("[NaverSearch→AI] 뉴스{}: {}", index, truncate(news.getTitle(), 50));
            index++;
        }

        String result = sb.toString();

        // AI에게 전달될 전체 텍스트 길이 로그
        log.info("[NaverSearch→AI] AI 전달 텍스트 길이: {} 문자", result.length());

        // 텍스트가 너무 짧으면 경고
        if (result.length() < 100) {
            log.warn("[NaverSearch→AI] 뉴스 텍스트가 너무 짧음 ({}자) - AI 분석 품질 저하 가능", result.length());
        }

        return result;
    }

    private String getTextValue(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    /**
     * API 사용 가능 여부
     */
    public boolean isAvailable() {
        return clientId != null && !clientId.isEmpty()
                && clientSecret != null && !clientSecret.isEmpty();
    }
}
