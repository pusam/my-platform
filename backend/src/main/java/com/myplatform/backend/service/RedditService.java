package com.myplatform.backend.service;

import com.myplatform.backend.dto.RedditPostDto;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RedditService {

    private static final Logger log = LoggerFactory.getLogger(RedditService.class);

    // Reddit RSS Feed URL 패턴
    private static final String RSS_URL_PATTERN = "https://www.reddit.com/r/%s/%s.rss";

    // Stock ticker pattern: $AAPL or standalone uppercase 2-5 letter words
    private static final Pattern TICKER_PATTERN = Pattern.compile("\\$([A-Z]{1,5})|\\b([A-Z]{2,5})\\b");

    // 주식 관련 서브레딧 목록
    private static final List<String> STOCK_SUBREDDITS = Arrays.asList(
            "wallstreetbets",
            "stocks",
            "investing",
            "stockmarket",
            "options",
            "ValueInvesting"
    );

    // 티커 추출 시 제외할 일반 단어들
    private static final Set<String> EXCLUDED_WORDS = Set.of(
            "THE", "AND", "FOR", "ARE", "BUT", "NOT", "YOU", "ALL", "CAN", "HAD", "HER", "WAS",
            "ONE", "OUR", "OUT", "HAS", "HIS", "HOW", "MAN", "NEW", "NOW", "OLD", "SEE", "WAY",
            "WHO", "BOY", "DID", "GET", "HIM", "LET", "PUT", "SAY", "SHE", "TOO", "USE", "ANY",
            "DAY", "GOT", "MAY", "OWN", "TOP", "BIG", "EOD", "IMO", "IMHO", "DD", "OP", "EDIT",
            "TL", "DR", "TLDR", "USA", "ETF", "IPO", "CEO", "CFO", "COO", "SEC", "FED", "GDP",
            "ATH", "ATL", "LOL", "OMG", "WTF", "YOLO", "FOMO", "HODL", "WSB", "NYSE", "NASDAQ",
            "RSS", "XML", "URL", "API", "PM", "AM", "EST", "PST", "UTC", "USD", "EUR", "GBP"
    );

    /**
     * RSS는 항상 사용 가능
     */
    public boolean isConfigured() {
        return true;
    }

    /**
     * 특정 서브레딧의 게시물 조회 (RSS)
     */
    public List<RedditPostDto> getSubredditPosts(String subreddit, String sort, int limit) {
        try {
            String rssUrl = String.format(RSS_URL_PATTERN, subreddit, sort);
            log.info("Reddit RSS 호출: {}", rssUrl);

            URL url = new URL(rssUrl);
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(url));

            List<RedditPostDto> posts = new ArrayList<>();
            int count = Math.min(feed.getEntries().size(), limit);

            for (int i = 0; i < count; i++) {
                SyndEntry entry = feed.getEntries().get(i);
                RedditPostDto post = parseRssEntry(entry, subreddit);
                posts.add(post);
            }

            log.info("r/{} 에서 {}개 게시물 로드 완료", subreddit, posts.size());
            return posts;

        } catch (Exception e) {
            log.error("Reddit RSS 피드 조회 실패: r/{}", subreddit, e);
            return Collections.emptyList();
        }
    }

    /**
     * 여러 주식 서브레딧에서 인기 게시물 조회
     */
    public List<RedditPostDto> getStockPosts(int limitPerSubreddit) {
        List<RedditPostDto> allPosts = new ArrayList<>();

        for (String subreddit : STOCK_SUBREDDITS) {
            try {
                List<RedditPostDto> posts = getSubredditPosts(subreddit, "hot", limitPerSubreddit);
                allPosts.addAll(posts);
                Thread.sleep(100); // Rate limiting
            } catch (Exception e) {
                log.error("r/{} 조회 실패", subreddit, e);
            }
        }

        // 최신순 정렬 후 상위 50개 반환
        return allPosts.stream()
                .sorted((a, b) -> {
                    if (b.getCreatedAt() == null) return -1;
                    if (a.getCreatedAt() == null) return 1;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .limit(50)
                .collect(Collectors.toList());
    }

    /**
     * 주식 관련 검색 (여러 서브레딧에서 키워드 포함 게시물 필터링)
     */
    public List<RedditPostDto> searchStockPosts(String query, int limit) {
        List<RedditPostDto> allPosts = getStockPosts(10);

        // 키워드로 필터링 (제목 또는 본문에 포함)
        String lowerQuery = query.toLowerCase();
        return allPosts.stream()
                .filter(post -> {
                    String title = post.getTitle() != null ? post.getTitle().toLowerCase() : "";
                    String selftext = post.getSelftext() != null ? post.getSelftext().toLowerCase() : "";
                    return title.contains(lowerQuery) || selftext.contains(lowerQuery);
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 트렌딩 티커 분석
     */
    public List<RedditPostDto.TrendingTicker> getTrendingTickers(int postLimit) {
        List<RedditPostDto> posts = getStockPosts(postLimit);

        // 티커별 언급 횟수 카운트
        Map<String, Integer> tickerCounts = new HashMap<>();

        for (RedditPostDto post : posts) {
            Set<String> uniqueTickers = new HashSet<>();

            // 제목에서 티커 추출
            extractTickers(post.getTitle(), uniqueTickers);

            // 본문에서 티커 추출
            if (post.getSelftext() != null) {
                extractTickers(post.getSelftext(), uniqueTickers);
            }

            // 카운트 증가
            for (String ticker : uniqueTickers) {
                tickerCounts.merge(ticker, 1, Integer::sum);
            }

            // 게시물에 티커 설정
            post.setMentionedTickers(new ArrayList<>(uniqueTickers));
        }

        // 언급 횟수 순으로 정렬하여 상위 20개 반환
        return tickerCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(20)
                .map(entry -> new RedditPostDto.TrendingTicker(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * RSS 엔트리를 DTO로 변환
     */
    private RedditPostDto parseRssEntry(SyndEntry entry, String subreddit) {
        RedditPostDto post = new RedditPostDto();

        // ID 추출 (URL에서)
        String link = entry.getLink();
        if (link != null && link.contains("/comments/")) {
            String[] parts = link.split("/comments/");
            if (parts.length > 1) {
                String idPart = parts[1].split("/")[0];
                post.setId(idPart);
            }
        }

        post.setTitle(cleanHtml(entry.getTitle()));
        post.setSubreddit(subreddit);
        post.setPermalink(link);
        post.setUrl(link);

        // 작성자 추출
        if (entry.getAuthor() != null) {
            String author = entry.getAuthor();
            if (author.startsWith("/u/")) {
                author = author.substring(3);
            }
            post.setAuthor(author);
        }

        // 본문 추출 (description에서 HTML 제거)
        if (entry.getDescription() != null) {
            String content = cleanHtml(entry.getDescription().getValue());
            post.setSelftext(content);
        }

        // 게시 시간
        if (entry.getPublishedDate() != null) {
            post.setCreatedAt(LocalDateTime.ofInstant(
                    entry.getPublishedDate().toInstant(),
                    ZoneId.systemDefault()
            ));
        } else if (entry.getUpdatedDate() != null) {
            post.setCreatedAt(LocalDateTime.ofInstant(
                    entry.getUpdatedDate().toInstant(),
                    ZoneId.systemDefault()
            ));
        }

        // 티커 추출
        Set<String> tickers = new HashSet<>();
        extractTickers(post.getTitle(), tickers);
        extractTickers(post.getSelftext(), tickers);
        post.setMentionedTickers(new ArrayList<>(tickers));

        // RSS에서는 점수/댓글 수를 알 수 없음 (기본값 0)
        post.setScore(0);
        post.setNumComments(0);
        post.setUpvoteRatio(0);

        return post;
    }

    /**
     * 텍스트에서 주식 티커 추출
     */
    private void extractTickers(String text, Set<String> tickers) {
        if (text == null || text.isEmpty()) {
            return;
        }

        Matcher matcher = TICKER_PATTERN.matcher(text);
        while (matcher.find()) {
            String ticker = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (ticker != null && !EXCLUDED_WORDS.contains(ticker) && ticker.length() >= 2) {
                tickers.add(ticker);
            }
        }
    }

    /**
     * HTML 태그 및 특수문자 제거
     */
    private String cleanHtml(String text) {
        if (text == null) return "";
        return text
                .replaceAll("<[^>]*>", "")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&quot;", "\"")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&#39;", "'")
                .replaceAll("&apos;", "'")
                .replaceAll("\\[link\\]", "")
                .replaceAll("\\[comments\\]", "")
                .replaceAll("submitted by", "")
                .trim();
    }

    /**
     * 지원하는 서브레딧 목록 반환
     */
    public List<String> getAvailableSubreddits() {
        return new ArrayList<>(STOCK_SUBREDDITS);
    }
}
