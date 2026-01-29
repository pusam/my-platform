package com.myplatform.backend.service;

import com.myplatform.backend.dto.NewsSummaryDto;
import com.myplatform.backend.entity.NewsSummary;
import com.myplatform.backend.repository.NewsSummaryRepository;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    private final NewsSummaryRepository newsSummaryRepository;
    private final OllamaService ollamaService;

    // 경제 뉴스 RSS 피드 URL 목록
    private static final String[] RSS_FEEDS = {
        "https://www.hankyung.com/feed/economy",      // 한국경제 경제
        "https://www.mk.co.kr/rss/30100041/",          // 매일경제 경제
        "https://rss.etnews.com/Section902.xml"        // 전자신문 경제
    };

    // 감성 분석 태그 패턴
    private static final Pattern SENTIMENT_PATTERN = Pattern.compile("\\[(긍정|부정|중립)\\]");

    public NewsService(NewsSummaryRepository newsSummaryRepository, OllamaService ollamaService) {
        this.newsSummaryRepository = newsSummaryRepository;
        this.ollamaService = ollamaService;
    }

    /**
     * 오늘의 뉴스 요약 조회
     */
    public List<NewsSummaryDto> getTodayNews() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return newsSummaryRepository.findTodayNews(startOfDay)
                .stream()
                .map(NewsSummaryDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * 최근 뉴스 요약 조회 (10개)
     */
    public List<NewsSummaryDto> getRecentNews() {
        return newsSummaryRepository.findTop10ByOrderBySummarizedAtDesc()
                .stream()
                .map(NewsSummaryDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * 매일 아침 8시에 경제 뉴스 수집 및 요약
     * 크론 표현식: 초 분 시 일 월 요일
     */
    @Scheduled(cron = "0 0 8 * * *")
    @Transactional
    public void fetchAndSummarizeNews() {
        log.info("=== 경제 뉴스 수집 및 요약 시작 ===");

        List<RssItem> allItems = new ArrayList<>();

        // 각 RSS 피드에서 뉴스 수집
        for (String feedUrl : RSS_FEEDS) {
            try {
                List<RssItem> items = fetchRssFeed(feedUrl);
                allItems.addAll(items);
                log.info("RSS 피드 수집 완료: {} ({}건)", feedUrl, items.size());
            } catch (Exception e) {
                log.error("RSS 피드 수집 실패: {}", feedUrl, e);
            }
        }

        // 최대 5개 뉴스만 요약 (AI 부하 고려)
        int count = 0;

        for (RssItem item : allItems) {
            if (count >= 5) break;

            // URL 기준 중복 체크 (제목 수정에도 동일 뉴스 거름)
            if (item.link != null && newsSummaryRepository.existsBySourceUrl(item.link)) {
                log.info("중복 뉴스 건너뜀 (URL): {}", item.title);
                continue;
            }

            try {
                // AI로 요약 및 감성 분석
                SummaryResult result = summarizeWithAi(item.title, item.description);

                // DB에 저장
                NewsSummary news = new NewsSummary();
                news.setTitle(item.title);
                news.setOriginalContent(item.description);
                news.setSummary(result.summary);
                news.setSentiment(result.sentiment);
                news.setSourceName(item.sourceName);
                news.setSourceUrl(item.link);
                news.setPublishedAt(item.publishedAt);
                news.setSummarizedAt(LocalDateTime.now());

                newsSummaryRepository.save(news);
                count++;
                log.info("뉴스 요약 완료: {} [{}]", item.title, result.sentiment);

                // AI 부하 방지를 위해 잠시 대기
                Thread.sleep(2000);

            } catch (Exception e) {
                log.error("뉴스 요약 실패: {}", item.title, e);
            }
        }

        log.info("=== 경제 뉴스 수집 및 요약 완료 ({}건) ===", count);
    }

    /**
     * 수동으로 뉴스 수집 및 요약 (비동기 처리)
     * 브라우저 타임아웃 방지를 위해 백그라운드에서 실행
     */
    @Async
    public CompletableFuture<Integer> manualFetchNewsAsync() {
        log.info("=== 비동기 뉴스 수집 시작 ===");
        try {
            fetchAndSummarizeNews();
            int count = (int) newsSummaryRepository.findTodayNews(LocalDate.now().atStartOfDay()).size();
            log.info("=== 비동기 뉴스 수집 완료 ({}건) ===", count);
            return CompletableFuture.completedFuture(count);
        } catch (Exception e) {
            log.error("비동기 뉴스 수집 실패", e);
            return CompletableFuture.completedFuture(0);
        }
    }

    /**
     * RSS 피드 파싱 (Jsoup 사용하여 HTML 클린업)
     */
    private List<RssItem> fetchRssFeed(String feedUrl) throws Exception {
        List<RssItem> items = new ArrayList<>();

        URL url = new URL(feedUrl);
        SyndFeedInput input = new SyndFeedInput();
        SyndFeed feed = input.build(new XmlReader(url));

        String sourceName = extractSourceName(feedUrl);

        // 최근 10개 항목만 가져오기
        int limit = Math.min(feed.getEntries().size(), 10);
        for (int i = 0; i < limit; i++) {
            SyndEntry entry = feed.getEntries().get(i);

            RssItem item = new RssItem();
            item.title = cleanHtmlWithJsoup(entry.getTitle());
            item.description = cleanHtmlWithJsoup(
                    entry.getDescription() != null ? entry.getDescription().getValue() : ""
            );
            item.link = entry.getLink();
            item.sourceName = sourceName;
            item.publishedAt = entry.getPublishedDate() != null ?
                    LocalDateTime.ofInstant(entry.getPublishedDate().toInstant(), ZoneId.systemDefault()) :
                    LocalDateTime.now();

            items.add(item);
        }

        return items;
    }

    /**
     * AI를 사용하여 뉴스 요약 + 감성 분석
     */
    private SummaryResult summarizeWithAi(String title, String content) {
        String prompt = String.format("""
            다음 경제 뉴스를 5줄 이내로 간결하게 요약해주세요.
            핵심 내용만 포함하고, 불필요한 수식어는 제외해주세요.

            요약 마지막에 이 뉴스가 주식 시장에 미치는 영향을 다음 중 하나로 판단해서 태그를 달아주세요:
            - [긍정] : 시장에 호재, 상승 기대
            - [부정] : 시장에 악재, 하락 우려
            - [중립] : 시장에 큰 영향 없음

            제목: %s

            내용: %s

            [요약]
            """, title, content);

        String systemPrompt = """
            당신은 경제 뉴스 요약 및 시장 분석 전문가입니다.
            반드시 한국어로 답변하세요.
            - 핵심 내용을 5줄 이내로 요약
            - 객관적이고 중립적인 어조 유지
            - 숫자와 통계는 정확하게 포함
            - 불필요한 인사말이나 부가 설명 없이 요약만 제공
            - 요약 마지막에 반드시 [긍정], [부정], [중립] 중 하나의 태그를 붙여주세요
            """;

        String response = ollamaService.chat(prompt, systemPrompt);

        // 응답에서 감성 태그 파싱
        String sentiment = "NEUTRAL";
        String summary = response;

        if (response != null) {
            Matcher matcher = SENTIMENT_PATTERN.matcher(response);
            if (matcher.find()) {
                String tag = matcher.group(1);
                sentiment = switch (tag) {
                    case "긍정" -> "POSITIVE";
                    case "부정" -> "NEGATIVE";
                    default -> "NEUTRAL";
                };
                // 태그 제거한 요약문
                summary = response.replaceAll("\\s*\\[(긍정|부정|중립)\\]\\s*", "").trim();
            }

            // 요약이 너무 길면 자르기
            if (summary.length() > 500) {
                summary = summary.substring(0, 500) + "...";
            }
        } else {
            summary = "요약을 생성할 수 없습니다.";
        }

        return new SummaryResult(summary, sentiment);
    }

    /**
     * 소스 이름 추출
     */
    private String extractSourceName(String feedUrl) {
        if (feedUrl.contains("hankyung")) return "한국경제";
        if (feedUrl.contains("mk.co.kr")) return "매일경제";
        if (feedUrl.contains("etnews")) return "전자신문";
        return "기타";
    }

    /**
     * Jsoup을 사용한 HTML 클린업
     * - 모든 HTML 태그 제거
     * - HTML 엔티티 디코딩
     * - 불필요한 공백 정리
     */
    private String cleanHtmlWithJsoup(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        try {
            // Jsoup으로 HTML 파싱 후 텍스트만 추출
            Document doc = Jsoup.parse(html);

            // 스크립트, 스타일 태그 제거
            doc.select("script, style, noscript").remove();

            // 텍스트만 추출 (HTML 태그 제거, 엔티티 디코딩)
            String text = doc.text();

            // 여러 개의 공백을 하나로
            text = text.replaceAll("\\s+", " ").trim();

            return text;
        } catch (Exception e) {
            log.warn("HTML 클린업 실패, 폴백 사용: {}", e.getMessage());
            // 폴백: 기본 정규식으로 태그 제거
            return Jsoup.clean(html, Safelist.none())
                    .replaceAll("\\s+", " ")
                    .trim();
        }
    }

    /**
     * 요약 결과 내부 클래스
     */
    private static class SummaryResult {
        final String summary;
        final String sentiment;

        SummaryResult(String summary, String sentiment) {
            this.summary = summary;
            this.sentiment = sentiment;
        }
    }

    /**
     * RSS 아이템 내부 클래스
     */
    private static class RssItem {
        String title;
        String description;
        String link;
        String sourceName;
        LocalDateTime publishedAt;
    }
}
