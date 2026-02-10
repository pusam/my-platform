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

/**
 * 네이버 검색 API 연동 서비스
 *
 * [기능]
 * - 특정 종목의 최신 뉴스 검색
 * - 7일 이내 기사만 필터링
 *
 * [검색 전략 v3 - Strict & Fresh]
 * 1. 검색어 구체화: "{종목명} 주가", "{종목명} 실적", "{종목명} 공시"
 * 2. 날짜순 정렬 (sort=date) - 최신 기사 우선
 * 3. 7일 이내 기사만 필터링 (옛날 기사 제거)
 * 4. 종목명 포함 여부 체크
 */
@Service
@Slf4j
public class NaverSearchService {

    @Value("${naver.api.client-id:}")
    private String clientId;

    @Value("${naver.api.client-secret:}")
    private String clientSecret;

    private static final String NAVER_SEARCH_URL = "https://openapi.naver.com/v1/search/news.json";

    // 주식 관련 검색 키워드 (검색어 조합용)
    private static final List<String> STOCK_SEARCH_KEYWORDS = Arrays.asList(
            "주가", "실적", "공시", "증권"
    );

    // 리스크 관련 키워드 (countRiskNews에서 사용)
    private static final List<String> RISK_KEYWORDS = Arrays.asList(
            "악재", "검찰", "횡령", "배임", "수사", "기소",
            "적자", "손실", "하락", "폭락", "실적악화", "공매도"
    );

    // 최신 기사 필터링 기준 (일)
    private static final int MAX_NEWS_AGE_DAYS = 7;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public NaverSearchService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 종목 관련 뉴스 검색 (Strict & Fresh v3)
     *
     * [검색 전략 - 엄격 + 최신]
     * 1. 검색어 구체화: "{종목명} 주가", "{종목명} 실적" 등
     * 2. 날짜순(date) 정렬 고정 - 최신 기사 우선
     * 3. 7일 이내 기사만 필터링
     * 4. 종목명 포함 여부 체크
     *
     * @param stockName 종목명
     * @return 필터링된 뉴스 목록 (최신순, 7일 이내)
     */
    public List<NewsItem> searchStockNews(String stockName) {
        if (!isAvailable()) {
            log.warn("[NaverSearch] API Key가 설정되지 않았습니다.");
            return Collections.emptyList();
        }

        log.info("[NaverSearch] '{}' 종목 뉴스 검색 시작 (Strict v3, 날짜순, 7일 이내)", stockName);
        Set<NewsItem> allNews = new LinkedHashSet<>(); // 중복 제거용

        // 주식 관련 키워드 조합으로 검색 (날짜순 고정)
        for (String keyword : STOCK_SEARCH_KEYWORDS) {
            String query = stockName + " " + keyword;
            List<NewsItem> searchResult = searchNewsWithSort(query, 30, "date"); // 날짜순 고정!

            // 필터링: 종목명 포함 + 7일 이내
            List<NewsItem> filtered = filterStrictly(searchResult, stockName);
            allNews.addAll(filtered);

            log.info("[NaverSearch] 검색 '{}': API {}건 → 필터 후 {}건 (누적: {}건)",
                    query, searchResult.size(), filtered.size(), allNews.size());

            // 충분한 기사가 모이면 중단
            if (allNews.size() >= 10) {
                break;
            }
        }

        List<NewsItem> result = new ArrayList<>(allNews);

        // 최대 15개로 제한
        if (result.size() > 15) {
            result = result.subList(0, 15);
        }

        log.info("[NaverSearch] '{}' 뉴스 검색 완료: 최종 {}건 (7일 이내)", stockName, result.size());

        // 검색 결과가 없으면 메시지
        if (result.isEmpty()) {
            log.info("[NaverSearch] '{}' 관련 최신 뉴스(7일 이내)가 없습니다.", stockName);
        }

        return result;
    }

    /**
     * 엄격한 필터링: 종목명 포함 + 7일 이내 기사만
     */
    private List<NewsItem> filterStrictly(List<NewsItem> newsList, String stockName) {
        List<NewsItem> filtered = new ArrayList<>();
        String stockNameLower = stockName.toLowerCase();
        LocalDate cutoffDate = LocalDate.now().minusDays(MAX_NEWS_AGE_DAYS);

        for (NewsItem news : newsList) {
            // 1. 발행일 체크 (7일 이내)
            if (!isWithinDays(news.getPubDate(), MAX_NEWS_AGE_DAYS)) {
                log.trace("[NaverSearch] 제외 (오래된 기사): pubDate={}", news.getPubDate());
                continue;
            }

            // 2. 종목명 포함 여부 체크
            String title = news.getTitle() != null ? news.getTitle().toLowerCase() : "";
            String description = news.getDescription() != null ? news.getDescription().toLowerCase() : "";
            String content = title + " " + description;

            if (!content.contains(stockNameLower)) {
                log.trace("[NaverSearch] 제외 (종목명 미포함): '{}'", truncate(title, 40));
                continue;
            }

            // 3. 명백히 비관련 기사 제외
            boolean isIrrelevant = false;
            for (String exclude : Arrays.asList("연예", "아이돌", "드라마", "야구", "축구", "농구", "운세", "로또")) {
                if (title.contains(exclude)) {
                    isIrrelevant = true;
                    break;
                }
            }

            if (!isIrrelevant) {
                filtered.add(news);
            }
        }

        return filtered;
    }

    /**
     * pubDate가 N일 이내인지 확인
     * 네이버 API pubDate 형식: "Mon, 10 Feb 2025 09:00:00 +0900"
     */
    private boolean isWithinDays(String pubDate, int days) {
        if (pubDate == null || pubDate.isEmpty()) {
            return false;
        }

        try {
            // RFC 1123 형식 파싱 (예: "Mon, 10 Feb 2025 09:00:00 +0900")
            DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME;
            ZonedDateTime articleDate = ZonedDateTime.parse(pubDate, formatter);
            LocalDate articleLocalDate = articleDate.toLocalDate();
            LocalDate cutoffDate = LocalDate.now().minusDays(days);

            boolean isRecent = !articleLocalDate.isBefore(cutoffDate);
            if (!isRecent) {
                log.trace("[NaverSearch] 오래된 기사 필터링: {} (기준: {} 이후)", articleLocalDate, cutoffDate);
            }
            return isRecent;

        } catch (Exception e) {
            // 파싱 실패 시 일단 포함 (안전한 쪽으로)
            log.debug("[NaverSearch] pubDate 파싱 실패: '{}' - {}", pubDate, e.getMessage());
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
