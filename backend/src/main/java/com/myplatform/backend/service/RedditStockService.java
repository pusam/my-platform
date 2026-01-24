package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.RedditPostDto;
import com.myplatform.backend.dto.RedditStockMentionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reddit 주식 정보 수집 서비스
 * - 미국 주식: r/wallstreetbets, r/stocks, r/investing
 * - 한국 주식: r/hanguk (한국 서브레딧)
 */
@Service
public class RedditStockService {

    private static final Logger log = LoggerFactory.getLogger(RedditStockService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // 미국 주식 서브레딧
    private static final String[] US_SUBREDDITS = {"wallstreetbets", "stocks", "investing", "StockMarket"};
    // 한국 주식 서브레딧 (실제로는 한국 관련 서브레딧이 제한적이므로 일반 검색 사용)
    private static final String[] KR_SUBREDDITS = {"hanguk", "korea"};

    // 주식 티커 패턴 (대문자 1-5자)
    private static final Pattern TICKER_PATTERN = Pattern.compile("\\b([A-Z]{1,5})\\b");

    // 감성 분석용 키워드
    private static final Set<String> POSITIVE_WORDS = Set.of(
        "bull", "bullish", "moon", "rocket", "calls", "buy", "long", "pump", "gain", "profit",
        "green", "up", "rally", "breakout", "strong", "undervalued", "opportunity"
    );
    private static final Set<String> NEGATIVE_WORDS = Set.of(
        "bear", "bearish", "crash", "dump", "puts", "sell", "short", "loss", "red", "down",
        "drop", "weak", "overvalued", "avoid", "risk", "decline"
    );

    public RedditStockService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 미국 주식 인기 종목 조회
     */
    @Cacheable(value = "redditUSStocks", unless = "#result == null || #result.isEmpty()")
    public List<RedditStockMentionDto> getTrendingUSStocks() {
        log.info("Reddit 미국 주식 인기 종목 수집 시작");
        Map<String, RedditStockMentionDto> stockMap = new HashMap<>();

        for (String subreddit : US_SUBREDDITS) {
            try {
                List<RedditPostDto> posts = fetchSubredditPosts(subreddit, 100);
                processPostsForStocks(posts, stockMap, "US");
            } catch (Exception e) {
                log.error("서브레딧 {} 수집 실패: {}", subreddit, e.getMessage());
            }
        }

        return rankAndFilterStocks(stockMap);
    }

    /**
     * 한국 주식 인기 종목 조회 (검색 기반)
     */
    @Cacheable(value = "redditKRStocks", unless = "#result == null || #result.isEmpty()")
    public List<RedditStockMentionDto> getTrendingKRStocks() {
        log.info("Reddit 한국 주식 인기 종목 수집 시작");
        Map<String, RedditStockMentionDto> stockMap = new HashMap<>();

        // 한국 주요 종목 키워드로 검색
        String[] krKeywords = {"삼성전자", "Samsung", "Hyundai", "현대", "SK", "LG", "Naver", "네이버", "카카오", "Kakao"};

        for (String keyword : krKeywords) {
            try {
                List<RedditPostDto> posts = searchReddit(keyword, 50);
                processPostsForStocks(posts, stockMap, "KR");
            } catch (Exception e) {
                log.error("키워드 {} 검색 실패: {}", keyword, e.getMessage());
            }
        }

        return rankAndFilterStocks(stockMap);
    }

    /**
     * 특정 서브레딧의 인기 게시글 조회
     */
    @Cacheable(value = "redditPosts", key = "#subreddit", unless = "#result == null || #result.isEmpty()")
    public List<RedditPostDto> getHotPosts(String subreddit) {
        log.info("서브레딧 {} 인기 게시글 조회", subreddit);
        return fetchSubredditPosts(subreddit, 25);
    }

    /**
     * 서브레딧에서 게시글 가져오기
     */
    private List<RedditPostDto> fetchSubredditPosts(String subreddit, int limit) {
        String url = String.format("https://www.reddit.com/r/%s/hot.json?limit=%d", subreddit, limit);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "MyPlatform/1.0");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return parseRedditResponse(response.getBody());
        } catch (Exception e) {
            log.error("서브레딧 {} 조회 실패: {}", subreddit, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Reddit 검색
     */
    private List<RedditPostDto> searchReddit(String query, int limit) {
        String url = String.format("https://www.reddit.com/search.json?q=%s&sort=hot&limit=%d", query, limit);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "MyPlatform/1.0");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return parseRedditResponse(response.getBody());
        } catch (Exception e) {
            log.error("Reddit 검색 실패: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Reddit API 응답 파싱
     */
    private List<RedditPostDto> parseRedditResponse(String responseBody) {
        List<RedditPostDto> posts = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode children = root.path("data").path("children");

            for (JsonNode child : children) {
                JsonNode data = child.path("data");

                RedditPostDto post = new RedditPostDto();
                post.setPostId(data.path("id").asText());
                post.setTitle(data.path("title").asText());
                post.setSubreddit(data.path("subreddit").asText());
                post.setAuthor(data.path("author").asText());
                post.setUpvotes(data.path("ups").asInt());
                post.setCommentCount(data.path("num_comments").asInt());
                post.setUrl("https://reddit.com" + data.path("permalink").asText());

                long createdUtc = data.path("created_utc").asLong();
                post.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochSecond(createdUtc), ZoneId.systemDefault()));

                // 티커 추출
                String text = post.getTitle() + " " + data.path("selftext").asText();
                Set<String> tickers = extractTickers(text);
                post.setTickers(String.join(", ", tickers));

                // 감성 분석
                post.setSentiment(analyzeSentiment(text));

                posts.add(post);
            }
        } catch (Exception e) {
            log.error("Reddit 응답 파싱 실패", e);
        }

        return posts;
    }

    /**
     * 게시글에서 주식 정보 추출 및 집계
     */
    private void processPostsForStocks(List<RedditPostDto> posts, Map<String, RedditStockMentionDto> stockMap, String market) {
        for (RedditPostDto post : posts) {
            if (post.getTickers() == null || post.getTickers().isEmpty()) {
                continue;
            }

            String[] tickers = post.getTickers().split(", ");
            for (String tickerTemp : tickers) {
                String ticker = tickerTemp.trim();
                if (ticker.length() < 2 || ticker.length() > 5) {
                    continue;
                }

                RedditStockMentionDto mention = stockMap.computeIfAbsent(ticker, k -> {
                    RedditStockMentionDto dto = new RedditStockMentionDto();
                    dto.setTicker(k);
                    dto.setStockName(k);
                    dto.setMentionCount(0);
                    dto.setPositiveCount(0);
                    dto.setNegativeCount(0);
                    dto.setNeutralCount(0);
                    dto.setMarket(market);
                    dto.setLastUpdated(LocalDateTime.now());
                    return dto;
                });

                mention.setMentionCount(mention.getMentionCount() + 1);

                // 감성 분석
                String sentiment = post.getSentiment();
                if ("POSITIVE".equals(sentiment)) {
                    mention.setPositiveCount(mention.getPositiveCount() + 1);
                } else if ("NEGATIVE".equals(sentiment)) {
                    mention.setNegativeCount(mention.getNegativeCount() + 1);
                } else {
                    mention.setNeutralCount(mention.getNeutralCount() + 1);
                }
            }
        }

        // 감성 점수 계산
        for (RedditStockMentionDto mention : stockMap.values()) {
            int total = mention.getMentionCount();
            if (total > 0) {
                double score = ((mention.getPositiveCount() - mention.getNegativeCount()) * 100.0) / total;
                mention.setSentimentScore(Math.round(score * 100.0) / 100.0);
            }
        }
    }

    /**
     * 티커 추출 (대문자 1-5자)
     */
    private Set<String> extractTickers(String text) {
        Set<String> tickers = new HashSet<>();
        Matcher matcher = TICKER_PATTERN.matcher(text);

        while (matcher.find()) {
            String ticker = matcher.group(1);
            // 일반 단어 필터링 (I, A, CEO 등)
            if (!isCommonWord(ticker) && ticker.length() >= 2) {
                tickers.add(ticker);
            }
        }

        return tickers;
    }

    /**
     * 일반 단어 필터링
     */
    private boolean isCommonWord(String word) {
        Set<String> commonWords = Set.of(
            "I", "A", "THE", "AND", "OR", "BUT", "FOR", "TO", "OF", "IN", "ON", "AT",
            "CEO", "CFO", "CTO", "IPO", "ETF", "DD", "YOLO", "IMO", "IMHO", "FYI"
        );
        return commonWords.contains(word);
    }

    /**
     * 간단한 감성 분석
     */
    private String analyzeSentiment(String text) {
        text = text.toLowerCase();
        int positiveScore = 0;
        int negativeScore = 0;

        for (String word : POSITIVE_WORDS) {
            if (text.contains(word)) {
                positiveScore++;
            }
        }

        for (String word : NEGATIVE_WORDS) {
            if (text.contains(word)) {
                negativeScore++;
            }
        }

        if (positiveScore > negativeScore) {
            return "POSITIVE";
        } else if (negativeScore > positiveScore) {
            return "NEGATIVE";
        } else {
            return "NEUTRAL";
        }
    }

    /**
     * 주식 순위 매기기 및 필터링
     */
    private List<RedditStockMentionDto> rankAndFilterStocks(Map<String, RedditStockMentionDto> stockMap) {
        List<RedditStockMentionDto> result = new ArrayList<>(stockMap.values());

        // 언급 횟수로 정렬
        result.sort((a, b) -> b.getMentionCount().compareTo(a.getMentionCount()));

        // 상위 50개만 반환 및 순위 부여
        return result.stream()
            .limit(50)
            .peek(stock -> stock.setRank(result.indexOf(stock) + 1))
            .collect(Collectors.toList());
    }
}

