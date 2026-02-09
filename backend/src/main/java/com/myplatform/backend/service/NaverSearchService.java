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
import java.util.*;

/**
 * 네이버 검색 API 연동 서비스
 *
 * [기능]
 * - 특정 종목의 뉴스 검색
 * - 악재/호재 키워드와 조합하여 타겟 검색
 *
 * [검색 쿼리 예시]
 * - "{종목명} 악재"
 * - "{종목명} 검찰"
 * - "{종목명} 횡령"
 * - "{종목명} 실적"
 */
@Service
@Slf4j
public class NaverSearchService {

    @Value("${naver.api.client-id:}")
    private String clientId;

    @Value("${naver.api.client-secret:}")
    private String clientSecret;

    private static final String NAVER_SEARCH_URL = "https://openapi.naver.com/v1/search/news.json";

    // 리스크 관련 검색 키워드
    private static final List<String> RISK_KEYWORDS = Arrays.asList(
            "악재", "검찰", "횡령", "배임", "수사", "기소",
            "적자", "손실", "하락", "폭락", "실적악화"
    );

    // 일반 검색 키워드
    private static final List<String> GENERAL_KEYWORDS = Arrays.asList(
            "실적", "주가", "전망"
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public NaverSearchService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 종목 관련 뉴스 검색 (리스크 키워드 중심)
     *
     * @param stockName 종목명
     * @return 뉴스 목록
     */
    public List<NewsItem> searchStockNews(String stockName) {
        if (!isAvailable()) {
            log.warn("[NaverSearch] API Key가 설정되지 않았습니다.");
            return Collections.emptyList();
        }

        Set<NewsItem> allNews = new LinkedHashSet<>(); // 중복 제거용

        // 1. 리스크 키워드로 검색
        for (String keyword : RISK_KEYWORDS) {
            String query = stockName + " " + keyword;
            List<NewsItem> news = searchNews(query, 5);
            allNews.addAll(news);
        }

        // 2. 일반 키워드로 검색
        for (String keyword : GENERAL_KEYWORDS) {
            String query = stockName + " " + keyword;
            List<NewsItem> news = searchNews(query, 3);
            allNews.addAll(news);
        }

        // 3. 종목명 단독 검색
        List<NewsItem> generalNews = searchNews(stockName, 5);
        allNews.addAll(generalNews);

        List<NewsItem> result = new ArrayList<>(allNews);

        // 최대 15개로 제한
        if (result.size() > 15) {
            result = result.subList(0, 15);
        }

        log.info("[NaverSearch] {} 관련 뉴스 {}건 검색됨", stockName, result.size());
        return result;
    }

    /**
     * 뉴스 검색 API 호출
     *
     * @param query 검색어
     * @param display 결과 수
     * @return 뉴스 목록
     */
    public List<NewsItem> searchNews(String query, int display) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

            String url = UriComponentsBuilder.fromUriString(NAVER_SEARCH_URL)
                    .queryParam("query", encodedQuery)
                    .queryParam("display", display)
                    .queryParam("start", 1)
                    .queryParam("sort", "date")  // 최신순
                    .build(false)  // 이미 인코딩됨
                    .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Naver-Client-Id", clientId);
            headers.set("X-Naver-Client-Secret", clientSecret);

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseNewsResponse(response.getBody());
            }

        } catch (Exception e) {
            log.error("[NaverSearch] 뉴스 검색 실패 (query={}): {}", query, e.getMessage());
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
     */
    public String formatNewsForAi(List<NewsItem> newsList) {
        if (newsList.isEmpty()) {
            return "관련 뉴스가 없습니다.";
        }

        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (NewsItem news : newsList) {
            sb.append(String.format("[뉴스 %d]\n", index++));
            sb.append("제목: ").append(news.getTitle()).append("\n");
            if (news.getDescription() != null && !news.getDescription().isEmpty()) {
                sb.append("내용: ").append(news.getDescription()).append("\n");
            }
            sb.append("발행일: ").append(news.getPubDate()).append("\n\n");
        }
        return sb.toString();
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
