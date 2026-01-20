package com.myplatform.backend.service;

import com.myplatform.backend.dto.NewsSummaryDto;
import com.myplatform.backend.entity.NewsSummary;
import com.myplatform.backend.repository.NewsSummaryRepository;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();

        for (RssItem item : allItems) {
            if (count >= 5) break;

            // 중복 체크
            if (newsSummaryRepository.existsByTitleToday(item.title, startOfDay)) {
                log.info("중복 뉴스 건너뜀: {}", item.title);
                continue;
            }

            try {
                // AI로 요약
                String summary = summarizeWithAi(item.title, item.description);

                // DB에 저장
                NewsSummary news = new NewsSummary();
                news.setTitle(item.title);
                news.setOriginalContent(item.description);
                news.setSummary(summary);
                news.setSourceName(item.sourceName);
                news.setSourceUrl(item.link);
                news.setPublishedAt(item.publishedAt);
                news.setSummarizedAt(LocalDateTime.now());

                newsSummaryRepository.save(news);
                count++;
                log.info("뉴스 요약 완료: {}", item.title);

                // AI 부하 방지를 위해 잠시 대기
                Thread.sleep(2000);

            } catch (Exception e) {
                log.error("뉴스 요약 실패: {}", item.title, e);
            }
        }

        log.info("=== 경제 뉴스 수집 및 요약 완료 ({}건) ===", count);
    }

    /**
     * 수동으로 뉴스 수집 및 요약 (테스트용)
     */
    @Transactional
    public int manualFetchNews() {
        log.info("=== 수동 뉴스 수집 시작 ===");
        fetchAndSummarizeNews();
        return (int) newsSummaryRepository.findTodayNews(LocalDate.now().atStartOfDay()).size();
    }

    /**
     * RSS 피드 파싱
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
            item.title = cleanHtml(entry.getTitle());
            item.description = cleanHtml(entry.getDescription() != null ?
                    entry.getDescription().getValue() : "");
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
     * AI를 사용하여 뉴스 요약
     */
    private String summarizeWithAi(String title, String content) {
        String prompt = String.format("""
            다음 경제 뉴스를 5줄 이내로 간결하게 요약해주세요.
            핵심 내용만 포함하고, 불필요한 수식어는 제외해주세요.

            제목: %s

            내용: %s

            [요약]
            """, title, content);

        String systemPrompt = """
            당신은 경제 뉴스 요약 전문가입니다.
            반드시 한국어로 답변하세요.
            - 핵심 내용을 5줄 이내로 요약
            - 객관적이고 중립적인 어조 유지
            - 숫자와 통계는 정확하게 포함
            - 불필요한 인사말이나 부가 설명 없이 요약만 제공
            """;

        String summary = ollamaService.chat(prompt, systemPrompt);

        // 요약이 너무 길면 자르기
        if (summary != null && summary.length() > 500) {
            summary = summary.substring(0, 500) + "...";
        }

        return summary != null ? summary : "요약을 생성할 수 없습니다.";
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
     * HTML 태그 제거
     */
    private String cleanHtml(String text) {
        if (text == null) return "";
        return text.replaceAll("<[^>]*>", "")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&quot;", "\"")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .trim();
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
