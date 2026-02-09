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
 * - 특정 종목의 뉴스 검색 (주식/증권 관련 뉴스만 필터링)
 * - 악재/호재 키워드와 조합하여 타겟 검색
 *
 * [검색 전략]
 * 1. 주식 관련 키워드 조합: "{종목명} 주가", "{종목명} 특징주", "{종목명} 실적"
 * 2. 비관련 뉴스 필터링: 연예, 날씨, 운세 등 제외
 * 3. 최신순 정렬 (sort=date)
 */
@Service
@Slf4j
public class NaverSearchService {

    @Value("${naver.api.client-id:}")
    private String clientId;

    @Value("${naver.api.client-secret:}")
    private String clientSecret;

    private static final String NAVER_SEARCH_URL = "https://openapi.naver.com/v1/search/news.json";

    // 주식 관련 필수 검색 키워드 (정확도 높은 순서)
    private static final List<String> STOCK_KEYWORDS = Arrays.asList(
            "주가", "특징주", "실적", "증권", "주식"
    );

    // 리스크 관련 검색 키워드
    private static final List<String> RISK_KEYWORDS = Arrays.asList(
            "악재", "검찰", "횡령", "배임", "수사", "기소",
            "적자", "손실", "하락", "폭락", "실적악화", "공매도"
    );

    // 호재 관련 검색 키워드
    private static final List<String> POSITIVE_KEYWORDS = Arrays.asList(
            "호재", "급등", "상승", "실적개선", "흑자전환", "목표가상향"
    );

    // 제외할 키워드 (비관련 뉴스 필터링)
    private static final List<String> EXCLUDE_KEYWORDS = Arrays.asList(
            "운세", "날씨", "연예", "스포츠", "드라마", "영화", "아이돌",
            "가수", "배우", "결혼", "이혼", "열애", "출산", "사망",
            "맛집", "레시피", "여행", "패션", "뷰티", "다이어트",
            "로또", "복권", "게임", "e스포츠", "야구", "축구", "농구"
    );

    // 증권/경제 관련 키워드 (있으면 가산점)
    private static final List<String> FINANCE_KEYWORDS = Arrays.asList(
            "코스피", "코스닥", "증권", "주식", "투자", "시가총액", "PER", "PBR",
            "배당", "공시", "IR", "애널리스트", "목표가", "매수", "매도",
            "기관", "외국인", "개인", "거래량", "시총", "상장", "유증"
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public NaverSearchService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 종목 관련 뉴스 검색 (주식/증권 뉴스만 필터링)
     *
     * [검색 전략]
     * 1. "{종목명} 주가", "{종목명} 특징주" 등 주식 키워드로 검색
     * 2. 리스크 키워드로 악재 뉴스 검색
     * 3. 비관련 뉴스(연예, 날씨 등) 필터링
     *
     * @param stockName 종목명
     * @return 필터링된 뉴스 목록
     */
    public List<NewsItem> searchStockNews(String stockName) {
        if (!isAvailable()) {
            log.warn("[NaverSearch] API Key가 설정되지 않았습니다.");
            return Collections.emptyList();
        }

        log.info("[NaverSearch] '{}' 종목 뉴스 검색 시작", stockName);
        Set<NewsItem> allNews = new LinkedHashSet<>(); // 중복 제거용
        int totalSearched = 0;
        int totalFiltered = 0;

        // 1. 주식 관련 키워드로 검색 (가장 정확한 결과 우선)
        for (String keyword : STOCK_KEYWORDS) {
            String query = stockName + " " + keyword;
            List<NewsItem> news = searchNews(query, 10);
            totalSearched += news.size();

            // 비관련 뉴스 필터링
            List<NewsItem> filtered = filterRelevantNews(news, stockName);
            totalFiltered += (news.size() - filtered.size());
            allNews.addAll(filtered);

            log.debug("[NaverSearch] 쿼리='{}' -> 검색 {}건, 필터 후 {}건",
                    query, news.size(), filtered.size());
        }

        // 2. 리스크 키워드로 검색 (악재 발굴)
        for (String keyword : RISK_KEYWORDS.subList(0, Math.min(5, RISK_KEYWORDS.size()))) {
            String query = stockName + " " + keyword;
            List<NewsItem> news = searchNews(query, 5);
            totalSearched += news.size();

            List<NewsItem> filtered = filterRelevantNews(news, stockName);
            totalFiltered += (news.size() - filtered.size());
            allNews.addAll(filtered);
        }

        // 3. 호재 키워드로 검색 (균형 잡힌 분석용)
        for (String keyword : POSITIVE_KEYWORDS.subList(0, Math.min(3, POSITIVE_KEYWORDS.size()))) {
            String query = stockName + " " + keyword;
            List<NewsItem> news = searchNews(query, 3);
            totalSearched += news.size();

            List<NewsItem> filtered = filterRelevantNews(news, stockName);
            totalFiltered += (news.size() - filtered.size());
            allNews.addAll(filtered);
        }

        List<NewsItem> result = new ArrayList<>(allNews);

        // 최대 15개로 제한
        if (result.size() > 15) {
            result = result.subList(0, 15);
        }

        log.info("[NaverSearch] '{}' 뉴스 검색 완료: 총 검색 {}건 → 필터링 {}건 제외 → 최종 {}건",
                stockName, totalSearched, totalFiltered, result.size());

        // 검색 결과가 없으면 경고
        if (result.isEmpty()) {
            log.warn("[NaverSearch] '{}' 관련 유효한 뉴스를 찾지 못했습니다.", stockName);
        }

        return result;
    }

    /**
     * 비관련 뉴스 필터링
     * - 연예, 날씨, 운세 등 비관련 뉴스 제외
     * - 종목명이 제목/내용에 포함되어야 함
     * - 증권/경제 키워드 있으면 우선 포함
     */
    private List<NewsItem> filterRelevantNews(List<NewsItem> newsList, String stockName) {
        List<NewsItem> filtered = new ArrayList<>();

        for (NewsItem news : newsList) {
            String title = news.getTitle() != null ? news.getTitle().toLowerCase() : "";
            String description = news.getDescription() != null ? news.getDescription().toLowerCase() : "";
            String content = title + " " + description;

            // 1. 제외 키워드 체크 (연예, 날씨, 운세 등)
            boolean hasExcludeKeyword = false;
            for (String exclude : EXCLUDE_KEYWORDS) {
                if (content.contains(exclude)) {
                    hasExcludeKeyword = true;
                    log.trace("[NaverSearch] 제외: '{}' (키워드: {})", truncate(title, 30), exclude);
                    break;
                }
            }
            if (hasExcludeKeyword) {
                continue;
            }

            // 2. 종목명이 포함되어 있는지 확인 (필수)
            String stockNameLower = stockName.toLowerCase();
            boolean containsStockName = content.contains(stockNameLower);

            // 종목명의 일부만 포함되어도 허용 (예: "삼성" in "삼성전자")
            if (!containsStockName && stockName.length() >= 4) {
                String shortName = stockName.substring(0, Math.min(3, stockName.length()));
                containsStockName = content.contains(shortName.toLowerCase());
            }

            if (!containsStockName) {
                log.trace("[NaverSearch] 제외: '{}' (종목명 미포함)", truncate(title, 30));
                continue;
            }

            // 3. 증권/경제 키워드 있으면 보너스 (없어도 통과 가능)
            boolean hasFinanceKeyword = false;
            for (String finance : FINANCE_KEYWORDS) {
                if (content.contains(finance.toLowerCase())) {
                    hasFinanceKeyword = true;
                    break;
                }
            }

            // 증권 키워드 없어도 종목명 있으면 포함 (단, 로그로 표시)
            if (!hasFinanceKeyword) {
                log.trace("[NaverSearch] 포함(경제키워드 없음): '{}'", truncate(title, 30));
            }

            filtered.add(news);
        }

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
     * 뉴스 검색 API 호출
     *
     * @param query 검색어
     * @param display 결과 수 (최대 100)
     * @return 뉴스 목록
     */
    public List<NewsItem> searchNews(String query, int display) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

            // API URL 구성 (최신순 정렬, display 개수 제한)
            String url = UriComponentsBuilder.fromUriString(NAVER_SEARCH_URL)
                    .queryParam("query", encodedQuery)
                    .queryParam("display", Math.min(display, 100)) // 최대 100개
                    .queryParam("start", 1)
                    .queryParam("sort", "date")  // 최신순 정렬 (sim: 정확도순)
                    .build(false)  // 이미 인코딩됨
                    .toUriString();

            log.debug("[NaverSearch] API 호출: query='{}', display={}", query, display);

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Naver-Client-Id", clientId);
            headers.set("X-Naver-Client-Secret", clientSecret);
            headers.set("Accept", "application/json");

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                List<NewsItem> news = parseNewsResponse(response.getBody());
                log.debug("[NaverSearch] API 응답: query='{}' → {}건 반환", query, news.size());
                return news;
            } else {
                log.warn("[NaverSearch] API 응답 오류: status={}", response.getStatusCode());
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
