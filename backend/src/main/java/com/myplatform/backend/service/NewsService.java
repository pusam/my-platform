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
    private final GeminiService geminiService;

    // 경제 뉴스 RSS 피드 URL 목록
    private static final String[] RSS_FEEDS = {
        "https://www.hankyung.com/feed/economy",      // 한국경제 경제
        "https://www.mk.co.kr/rss/30100041/",          // 매일경제 경제
        "https://rss.etnews.com/Section902.xml"        // 전자신문 경제
    };

    // 감성 분석 태그 패턴
    private static final Pattern SENTIMENT_PATTERN = Pattern.compile("\\[(긍정|부정|중립)\\]");

    // ═══ 긴급 뉴스 키워드 (프론트 토스트 알림용) ═══
    private static final String[] URGENT_KEYWORDS = {
        "수주", "실적", "계약", "급등", "급락", "사상최고", "역대급", "호실적",
        "인수", "합병", "M&A", "IPO", "상장", "흑자전환", "적자전환",
        "소각", "무상증자", "공급계약", "사상최대", "어닝서프라이즈", "어닝쇼크",
        "자사주", "배당확대", "액면분할", "스톡옵션", "유상증자",
        "상한가", "하한가", "서킷브레이커", "사이드카"
    };

    // ═══ 카테고리 필터링: 투자 관련 뉴스만 수집 ═══
    private static final String[] INCLUDE_KEYWORDS = {
        "증시", "주가", "코스피", "코스닥", "나스닥", "S&P", "다우",
        "실적", "영업이익", "매출", "수주", "계약", "분기", "공급", "흑자",
        "금리", "환율", "유가", "원자재", "채권",
        "반도체", "2차전지", "배터리", "AI", "수출", "수입",
        "IPO", "상장", "공모", "배당", "자사주", "소각", "유상증자",
        "급등", "급락", "상한가", "하한가", "최대", "사상최고",
        "GDP", "CPI", "고용", "무역수지", "경상수지",
        "외국인", "기관", "순매수", "순매도", "공매도",
        "인수", "합병", "M&A", "투자", "펀드",
        "바이오", "제약", "신약", "전기차", "자동차",
        "은행", "보험", "증권사", "부동산", "건설"
    };

    private static final String[] EXCLUDE_KEYWORDS = {
        // 날씨/환경
        "날씨", "기온", "폭염", "한파", "미세먼지", "강수", "태풍", "폭우", "폭설", "자외선", "꽃가루",
        // 연예/문화
        "연예", "아이돌", "드라마", "영화배우", "예능", "콘서트", "가수", "배우", "셀럽",
        // 생활/소비 (비투자)
        "빵값", "식품가격", "맛집", "레시피", "다이어트", "택배", "배송",
        "샤넬", "루이비통", "에르메스", "명품", "브랜드가방", "하소연",
        "육아", "출산", "결혼식", "돌잔치", "반려동물", "펫",
        // 사건사고/사회
        "사건사고", "교통사고", "실종", "화재", "범죄", "살인", "폭행", "사기",
        "재판", "판결", "검찰", "경찰", "구속", "체포",
        // 스포츠
        "스포츠", "야구", "축구", "올림픽", "월드컵", "농구", "배구", "골프대회",
        // 여행/레저
        "여행", "관광", "축제", "공연", "전시회", "뮤지컬",
        // 정치 일반 (경제정책 제외)
        "대통령선거", "국회의원", "정당", "여당", "야당", "탄핵"
    };

    public NewsService(NewsSummaryRepository newsSummaryRepository, GeminiService geminiService) {
        this.newsSummaryRepository = newsSummaryRepository;
        this.geminiService = geminiService;
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
     * 특정 시각 이후 새로 수집된 뉴스 조회 (폴링용)
     */
    public List<NewsSummaryDto> getNewsSince(LocalDateTime since) {
        return newsSummaryRepository.findBySummarizedAtBetweenOrderBySummarizedAtDesc(since, LocalDateTime.now())
                .stream()
                .map(NewsSummaryDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * 뉴스 제목에 긴급 키워드가 포함되어 있는지 확인
     */
    public boolean isUrgentNews(String title) {
        if (title == null) return false;
        String lower = title.toLowerCase();
        for (String kw : URGENT_KEYWORDS) {
            if (lower.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    //  스케줄러: 장중 15분 간격 + 아침 07:30 배치
    // ═══════════════════════════════════════════════════════════════

    /**
     * 장중 실시간 수집 (평일 08:00~17:45, 15분 간격)
     */
    @Scheduled(cron = "0 */15 8-17 * * MON-FRI")
    public void scheduledMarketHours() {
        log.info("[스케줄] 장중 15분 주기 뉴스 수집 시작");
        fetchAndSummarizeNews(3);  // 장중에는 최대 3건씩 빠르게
    }

    /**
     * 장 마감 직후 수집 (평일 18:00)
     */
    @Scheduled(cron = "0 0 18 * * MON-FRI")
    public void scheduledAfterClose() {
        log.info("[스케줄] 장 마감 후 뉴스 수집");
        fetchAndSummarizeNews(5);
    }

    /**
     * 아침 프리마켓 배치 (평일 07:30 - 야간 뉴스 한 번에 수집)
     */
    @Scheduled(cron = "0 30 7 * * MON-FRI")
    public void scheduledMorningBatch() {
        log.info("[스케줄] 아침 07:30 배치 뉴스 수집");
        fetchAndSummarizeNews(8);  // 밤새 쌓인 뉴스 넉넉하게
    }

    @Transactional
    public void fetchAndSummarizeNews() {
        fetchAndSummarizeNews(5);
    }

    @Transactional
    public void fetchAndSummarizeNews(int maxArticles) {
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

        // 투자 관련 뉴스만 필터링
        List<RssItem> filteredItems = allItems.stream()
                .filter(this::isInvestmentRelated)
                .collect(Collectors.toList());
        log.info("카테고리 필터링: {}건 → {}건 (투자 관련)", allItems.size(), filteredItems.size());

        // 최대 maxArticles개 뉴스만 요약 (AI 부하 고려)
        int count = 0;

        for (RssItem item : filteredItems) {
            if (count >= maxArticles) break;

            // URL 기준 중복 체크 (제목 수정에도 동일 뉴스 거름)
            if (item.link != null && newsSummaryRepository.existsBySourceUrl(item.link)) {
                log.info("중복 뉴스 건너뜀 (URL): {}", item.title);
                continue;
            }

            try {
                // Gemini 호출 제거 — 원문 그대로 저장 (Gemini 쿼터를 AI전략용으로 확보)
                String summary = item.description;
                if (summary != null && summary.length() > 500) {
                    summary = summary.substring(0, 500) + "...";
                }

                NewsSummary news = new NewsSummary();
                news.setTitle(item.title);
                news.setOriginalContent(item.description);
                news.setSummary(summary != null ? summary : item.title);
                news.setSentiment("NEUTRAL");
                news.setSourceName(item.sourceName);
                news.setSourceUrl(item.link);
                news.setPublishedAt(item.publishedAt);
                news.setSummarizedAt(LocalDateTime.now());

                newsSummaryRepository.save(news);
                count++;
                log.debug("뉴스 저장: {}", item.title);

            } catch (Exception e) {
                log.error("뉴스 저장 실패: {}", item.title, e);
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
     * 투자 관련 뉴스인지 필터링 (화이트리스트 방식)
     *
     * 1단계: EXCLUDE 키워드가 있으면 무조건 Drop (생활/연예/사회 등)
     * 2단계: INCLUDE 키워드가 하나라도 있으면 통과
     * 3단계: 어디에도 해당 안 되면 Drop (안전하게 차단)
     */
    private boolean isInvestmentRelated(RssItem item) {
        String text = (item.title + " " + item.description).toLowerCase();

        // 1단계: EXCLUDE 키워드가 있으면 무조건 Drop
        for (String kw : EXCLUDE_KEYWORDS) {
            if (text.contains(kw.toLowerCase())) {
                log.debug("비투자 뉴스 제외 (EXCLUDE): {}", item.title);
                return false;
            }
        }

        // 2단계: INCLUDE 키워드가 있으면 통과
        for (String kw : INCLUDE_KEYWORDS) {
            if (text.contains(kw.toLowerCase())) return true;
        }

        // 3단계: 어디에도 해당 안 되면 Drop (화이트리스트 방식)
        log.debug("비투자 뉴스 제외 (키워드 미매칭): {}", item.title);
        return false;
    }

    /**
     * AI를 사용하여 뉴스 요약 + 감성 분석
     */
    private SummaryResult summarizeWithAi(String title, String content) {
        String prompt = String.format("""
            [뉴스 원문]
            제목: %s
            본문: %s

            [작업 지시]
            위 뉴스 원문의 내용만을 근거로, 핵심을 정확히 3개의 불릿 포인트로 요약하세요.

            [필수 규칙]
            1. 반드시 위에 제공된 "뉴스 원문"의 본문 내용 안에서만 팩트를 추출할 것.
            2. 본문에 없는 내용을 절대 지어내지 말 것 (Hallucination 금지).
            3. 다른 기사나 일반 상식을 섞어 쓰지 말 것.
            4. 각 불릿은 '• '로 시작, 20~40자 이내로 건조하게 팩트만 작성.
            5. 원문에 수치(금액, 비율, 날짜 등)가 있으면 그대로 인용할 것.
            6. 마지막 줄에 [긍정], [부정], [중립] 태그를 붙일 것.
            """, title, content);

        String systemPrompt = """
            당신은 경제 뉴스 팩트 추출기입니다. 한국어로만 답변합니다.

            ★ 최우선 원칙: 주어진 뉴스 본문에 있는 내용만 요약합니다.
            ★ 본문에 없는 정보를 추가하거나 추측하면 안 됩니다.
            ★ 다른 뉴스, 배경지식, 일반 상식을 섞으면 안 됩니다.

            출력 형식:
            • [본문 팩트 1]
            • [본문 팩트 2]
            • [본문 팩트 3]
            [긍정/부정/중립]

            위 형식 외에 인사말, 설명, 부가 문장을 출력하지 마세요.
            """;

        String response = geminiService.chat(prompt);

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
