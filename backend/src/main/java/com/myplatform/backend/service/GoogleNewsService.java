package com.myplatform.backend.service;

import com.myplatform.backend.dto.RiskAnalysisDto.NewsItem;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Google News RSS 서비스
 *
 * Rome RSS 라이브러리로 Google News RSS 피드를 파싱하여 종목 관련 뉴스를 수집한다.
 * 기존 네이버 검색 대비 속도 개선 + 관련성 향상 + description(요약문) 제공.
 */
@Service
@Slf4j
public class GoogleNewsService {

    private static final String GOOGLE_NEWS_RSS_URL = "https://news.google.com/rss/search?q=%s+주식&hl=ko&gl=KR&ceid=KR:ko";
    private static final int MAX_NEWS_AGE_DAYS = 7;
    private static final int MAX_RESULT_COUNT = 10;
    private static final int TIMEOUT_MS = 10_000;

    private static final List<String> EXCLUDE_KEYWORDS = Arrays.asList(
            "연예", "아이돌", "드라마", "야구", "축구", "농구", "운세", "로또"
    );

    private static final DateTimeFormatter PUB_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final RestTemplate restTemplate;

    public GoogleNewsService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Google News RSS에서 종목 관련 뉴스를 검색한다.
     *
     * @param stockName 종목명 (예: "삼성전자")
     * @return 관련 뉴스 목록 (최대 10개, 7일 이내)
     */
    public List<NewsItem> searchNews(String stockName) {
        try {
            String encodedName = URLEncoder.encode(stockName + " 주식", StandardCharsets.UTF_8);
            String url = String.format("https://news.google.com/rss/search?q=%s&hl=ko&gl=KR&ceid=KR:ko", encodedName);

            log.info("[GoogleNews] RSS 조회: {}", stockName);

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getBody() == null || response.getBody().isEmpty()) {
                log.warn("[GoogleNews] 빈 응답: {}", stockName);
                return List.of();
            }

            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed;
            try (StringReader reader = new StringReader(response.getBody())) {
                feed = input.build(reader);
            }

            List<NewsItem> allNews = new ArrayList<>();
            LocalDate cutoffDate = LocalDate.now().minusDays(MAX_NEWS_AGE_DAYS);

            for (SyndEntry entry : feed.getEntries()) {
                // 7일 이내 필터
                if (entry.getPublishedDate() != null) {
                    LocalDate pubDate = entry.getPublishedDate().toInstant()
                            .atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
                    if (pubDate.isBefore(cutoffDate)) {
                        continue;
                    }
                }

                String title = entry.getTitle() != null ? entry.getTitle().trim() : "";
                String description = extractDescription(entry);
                String link = entry.getLink() != null ? entry.getLink() : "";
                String pubDateStr = formatPubDate(entry.getPublishedDate());

                allNews.add(NewsItem.builder()
                        .title(title)
                        .description(description)
                        .link(link)
                        .pubDate(pubDateStr)
                        .originalLink(link)
                        .build());
            }

            // 관련성 필터링
            List<NewsItem> filtered = filterByRelevance(allNews, stockName);

            log.info("[GoogleNews] {}건 조회 → 필터 후 {}건 (종목: {})",
                    allNews.size(), filtered.size(), stockName);

            return filtered.stream()
                    .limit(MAX_RESULT_COUNT)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("[GoogleNews] RSS 파싱 실패 (종목: {}): {}", stockName, e.getMessage());
            return List.of();
        }
    }

    /**
     * RSS entry에서 description을 추출하고 HTML 태그를 제거한다.
     */
    private String extractDescription(SyndEntry entry) {
        if (entry.getDescription() == null || entry.getDescription().getValue() == null) {
            return "";
        }
        String raw = entry.getDescription().getValue();
        // HTML 태그 제거
        String text = raw.replaceAll("<[^>]+>", "").trim();
        // HTML 엔티티 디코딩
        text = text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return text;
    }

    /**
     * pubDate를 포맷팅한다.
     */
    private String formatPubDate(Date date) {
        if (date == null) return "";
        return date.toInstant()
                .atZone(ZoneId.of("Asia/Seoul"))
                .format(PUB_DATE_FORMAT);
    }

    /**
     * 관련성 필터링 (NaverSearchService 패턴 재사용)
     * Google News는 이미 검색어 기반이므로 종목명 체크는 느슨하게 적용.
     */
    private List<NewsItem> filterByRelevance(List<NewsItem> newsList, String stockName) {
        String stockNameLower = stockName.toLowerCase().trim();
        List<String> competitorKeywords = getCompetitorKeywords(stockName);
        List<NewsItem> result = new ArrayList<>();

        for (NewsItem news : newsList) {
            String title = news.getTitle() != null ? news.getTitle().toLowerCase() : "";

            // 경쟁사 키워드 제외
            boolean hasCompetitor = false;
            for (String comp : competitorKeywords) {
                if (title.contains(comp.toLowerCase())) {
                    log.trace("[GoogleNews] 제외 (경쟁 종목): {} - {}", comp, news.getTitle());
                    hasCompetitor = true;
                    break;
                }
            }
            if (hasCompetitor) continue;

            // 비관련 키워드 제외
            boolean excluded = false;
            for (String kw : EXCLUDE_KEYWORDS) {
                if (title.contains(kw)) {
                    log.trace("[GoogleNews] 제외 (비관련 키워드 {}): {}", kw, news.getTitle());
                    excluded = true;
                    break;
                }
            }
            if (excluded) continue;

            result.add(news);
        }

        return result;
    }

    /**
     * 대기업 그룹사 경쟁 키워드 (NaverSearchService 로직 재사용)
     */
    private List<String> getCompetitorKeywords(String stockName) {
        if (stockName.contains("삼성전자")) {
            return Arrays.asList("삼성SDI", "삼성물산", "삼성생명", "삼성화재",
                    "삼성바이오", "삼성엔지", "삼성카드", "삼성증권", "삼성SDS");
        }
        if (stockName.contains("현대차") || stockName.contains("현대자동차")) {
            return Arrays.asList("현대건설", "현대중공업", "현대모비스", "현대제철",
                    "현대글로비스", "현대위아", "현대로템", "현대에너지솔루션");
        }
        if (stockName.contains("SK하이닉스")) {
            return Arrays.asList("SK텔레콤", "SK이노베이션", "SK바이오팜",
                    "SK스퀘어", "SKC", "SK네트웍스");
        }
        if (stockName.contains("SK스퀘어")) {
            return Arrays.asList("SK하이닉스", "SK텔레콤", "SK이노베이션",
                    "삼성전자", "삼성SDI", "삼성물산");
        }
        if (stockName.contains("LG전자")) {
            return Arrays.asList("LG화학", "LG에너지솔루션", "LG디스플레이",
                    "LG유플러스", "LG이노텍", "LG생활건강");
        }
        return Collections.emptyList();
    }
}
