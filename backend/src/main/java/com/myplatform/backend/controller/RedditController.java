package com.myplatform.backend.controller;

import com.myplatform.backend.dto.RedditPostDto;
import com.myplatform.backend.service.RedditService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Reddit 주식 정보", description = "Reddit 주식 관련 게시물 API")
@RestController
@RequestMapping("/api/reddit")
@SecurityRequirement(name = "JWT Bearer")
public class RedditController {

    private final RedditService redditService;

    public RedditController(RedditService redditService) {
        this.redditService = redditService;
    }

    @Operation(summary = "API 상태 확인", description = "Reddit API 설정 상태를 확인합니다.")
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("configured", redditService.isConfigured());
        status.put("subreddits", redditService.getAvailableSubreddits());
        return ResponseEntity.ok(ApiResponse.success("Reddit API 상태", status));
    }

    @Operation(summary = "주식 관련 인기 게시물", description = "여러 주식 관련 서브레딧에서 인기 게시물을 조회합니다.")
    @GetMapping("/posts")
    public ResponseEntity<ApiResponse<List<RedditPostDto>>> getStockPosts(
            @Parameter(description = "서브레딧당 조회 개수")
            @RequestParam(defaultValue = "10") int limit) {

        if (!redditService.isConfigured()) {
            return ResponseEntity.ok(ApiResponse.fail("Reddit API가 설정되지 않았습니다. 환경변수를 확인하세요."));
        }

        List<RedditPostDto> posts = redditService.getStockPosts(limit);
        return ResponseEntity.ok(ApiResponse.success("주식 관련 게시물 조회 성공", posts));
    }

    @Operation(summary = "특정 서브레딧 게시물", description = "특정 서브레딧의 게시물을 조회합니다.")
    @GetMapping("/subreddit/{subreddit}")
    public ResponseEntity<ApiResponse<List<RedditPostDto>>> getSubredditPosts(
            @Parameter(description = "서브레딧 이름")
            @PathVariable String subreddit,
            @Parameter(description = "정렬 방식 (hot, new, top, rising)")
            @RequestParam(defaultValue = "hot") String sort,
            @Parameter(description = "조회 개수")
            @RequestParam(defaultValue = "25") int limit) {

        if (!redditService.isConfigured()) {
            return ResponseEntity.ok(ApiResponse.fail("Reddit API가 설정되지 않았습니다."));
        }

        List<RedditPostDto> posts = redditService.getSubredditPosts(subreddit, sort, limit);
        return ResponseEntity.ok(ApiResponse.success("r/" + subreddit + " 게시물 조회 성공", posts));
    }

    @Operation(summary = "주식 관련 검색", description = "주식 관련 서브레딧에서 키워드로 검색합니다.")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<RedditPostDto>>> searchPosts(
            @Parameter(description = "검색 키워드 (종목명, 티커 등)")
            @RequestParam String query,
            @Parameter(description = "조회 개수")
            @RequestParam(defaultValue = "25") int limit) {

        if (!redditService.isConfigured()) {
            return ResponseEntity.ok(ApiResponse.fail("Reddit API가 설정되지 않았습니다."));
        }

        List<RedditPostDto> posts = redditService.searchStockPosts(query, limit);
        return ResponseEntity.ok(ApiResponse.success("검색 완료: " + query, posts));
    }

    @Operation(summary = "트렌딩 티커", description = "최근 게시물에서 가장 많이 언급된 종목 티커를 조회합니다.")
    @GetMapping("/trending")
    public ResponseEntity<ApiResponse<List<RedditPostDto.TrendingTicker>>> getTrendingTickers(
            @Parameter(description = "분석할 게시물 수 (서브레딧당)")
            @RequestParam(defaultValue = "15") int postLimit) {

        if (!redditService.isConfigured()) {
            return ResponseEntity.ok(ApiResponse.fail("Reddit API가 설정되지 않았습니다."));
        }

        List<RedditPostDto.TrendingTicker> tickers = redditService.getTrendingTickers(postLimit);
        return ResponseEntity.ok(ApiResponse.success("트렌딩 티커 조회 성공", tickers));
    }

    @Operation(summary = "지원 서브레딧 목록", description = "조회 가능한 주식 관련 서브레딧 목록을 반환합니다.")
    @GetMapping("/subreddits")
    public ResponseEntity<ApiResponse<List<String>>> getAvailableSubreddits() {
        List<String> subreddits = redditService.getAvailableSubreddits();
        return ResponseEntity.ok(ApiResponse.success("지원 서브레딧 목록", subreddits));
    }
}
