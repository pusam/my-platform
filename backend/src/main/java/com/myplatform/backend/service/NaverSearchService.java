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
 * - 제목에 종목명이 포함된 뉴스만 필터링
 *
 * [검색 전략 v2 - Loose Search]
 * 1. 단순 검색어: "{종목명}" 또는 "{종목명} 주식"
 * 2. 정확도순 정렬 (sort=sim) - 일단 뉴스를 무조건 가져옴
 * 3. 20개 넉넉하게 요청 후, Java에서 제목 필터링
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

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public NaverSearchService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 종목 관련 뉴스 검색 (Loose Search v2)
     *
     * [검색 전략 - 단순화]
     * 1. 단순 검색어로 API 호출 (종목명, 종목명+주식)
     * 2. 정확도순(sim) 정렬로 20개 요청
     * 3. Java에서 제목 필터링 (종목명 포함 여부만 체크)
     *
     * @param stockName 종목명
     * @return 필터링된 뉴스 목록
     */
    public List<NewsItem> searchStockNews(String stockName) {
        if (!isAvailable()) {
            log.warn("[NaverSearch] API Key가 설정되지 않았습니다.");
            return Collections.emptyList();
        }

        log.info("[NaverSearch] '{}' 종목 뉴스 검색 시작 (Loose Search v2)", stockName);
        Set<NewsItem> allNews = new LinkedHashSet<>(); // 중복 제거용

        // 1. 단순 검색: 종목명만 (정확도순, 20개)
        List<NewsItem> simpleSearch = searchNewsWithSort(stockName, 20, "sim");
        log.info("[NaverSearch] 1차 검색 '{}': {}건 반환", stockName, simpleSearch.size());
        allNews.addAll(filterByTitle(simpleSearch, stockName));

        // 2. 결과가 부족하면 "종목명 주식"으로 추가 검색
        if (allNews.size() < 5) {
            String query2 = stockName + " 주식";
            List<NewsItem> stockSearch = searchNewsWithSort(query2, 20, "sim");
            log.info("[NaverSearch] 2차 검색 '{}': {}건 반환", query2, stockSearch.size());
            allNews.addAll(filterByTitle(stockSearch, stockName));
        }

        // 3. 그래도 부족하면 최신순으로도 검색
        if (allNews.size() < 5) {
            List<NewsItem> dateSearch = searchNewsWithSort(stockName, 20, "date");
            log.info("[NaverSearch] 3차 검색 '{}' (최신순): {}건 반환", stockName, dateSearch.size());
            allNews.addAll(filterByTitle(dateSearch, stockName));
        }

        List<NewsItem> result = new ArrayList<>(allNews);

        // 최대 15개로 제한
        if (result.size() > 15) {
            result = result.subList(0, 15);
        }

        log.info("[NaverSearch] '{}' 뉴스 검색 완료: 최종 {}건", stockName, result.size());

        // 검색 결과가 없으면 경고
        if (result.isEmpty()) {
            log.warn("[NaverSearch] '{}' 관련 뉴스를 찾지 못했습니다. API 응답 확인 필요.", stockName);
        }

        return result;
    }

    /**
     * 제목에 종목명이 포함된 뉴스만 필터링 (Loose Filter)
     * - 제목 또는 내용에 종목명(또는 축약형)이 있으면 통과
     * - 연예/스포츠 등 명백히 관련 없는 뉴스만 제외
     */
    private List<NewsItem> filterByTitle(List<NewsItem> newsList, String stockName) {
        List<NewsItem> filtered = new ArrayList<>();
        String stockNameLower = stockName.toLowerCase();

        // 종목명 축약형 (예: "삼성전자" → "삼성")
        String shortName = stockName.length() >= 2
                ? stockName.substring(0, Math.min(2, stockName.length())).toLowerCase()
                : stockNameLower;

        for (NewsItem news : newsList) {
            String title = news.getTitle() != null ? news.getTitle().toLowerCase() : "";
            String description = news.getDescription() != null ? news.getDescription().toLowerCase() : "";
            String content = title + " " + description;

            // 종목명 또는 축약형이 포함되어 있으면 통과
            boolean containsStockName = content.contains(stockNameLower)
                    || content.contains(shortName);

            if (!containsStockName) {
                log.trace("[NaverSearch] 제외 (종목명 미포함): '{}'", truncate(title, 40));
                continue;
            }

            // 명백히 관련 없는 뉴스 제외 (연예, 스포츠 등) - 최소한의 필터링
            boolean isIrrelevant = false;
            for (String exclude : Arrays.asList("연예", "아이돌", "드라마", "야구", "축구", "농구", "운세")) {
                if (title.contains(exclude)) {
                    isIrrelevant = true;
                    log.trace("[NaverSearch] 제외 (비관련): '{}' (키워드: {})", truncate(title, 40), exclude);
                    break;
                }
            }

            if (!isIrrelevant) {
                filtered.add(news);
            }
        }

        log.debug("[NaverSearch] 제목 필터링: {}건 → {}건", newsList.size(), filtered.size());
        return filtered;
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
