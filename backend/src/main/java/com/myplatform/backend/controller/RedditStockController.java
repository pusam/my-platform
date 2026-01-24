package com.myplatform.backend.controller;

import com.myplatform.backend.dto.RedditPostDto;
import com.myplatform.backend.dto.RedditStockMentionDto;
import com.myplatform.backend.service.RedditStockService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Reddit 주식 정보", description = "Reddit에서 수집한 주식 관련 정보 API")
@RestController
@RequestMapping("/api/reddit")
@SecurityRequirement(name = "JWT Bearer")
public class RedditStockController {

    private final RedditStockService redditStockService;

    public RedditStockController(RedditStockService redditStockService) {
        this.redditStockService = redditStockService;
    }

    @Operation(
        summary = "미국 주식 인기 종목",
        description = "Reddit에서 가장 많이 언급되는 미국 주식 종목 조회 (r/wallstreetbets, r/stocks 등)"
    )
    @GetMapping("/trending/us")
    public ResponseEntity<ApiResponse<List<RedditStockMentionDto>>> getTrendingUSStocks() {
        List<RedditStockMentionDto> data = redditStockService.getTrendingUSStocks();
        if (data.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.fail("데이터를 가져올 수 없습니다."));
        }
        return ResponseEntity.ok(ApiResponse.success("미국 주식 인기 종목 조회 성공", data));
    }

    @Operation(
        summary = "한국 주식 인기 종목",
        description = "Reddit에서 언급되는 한국 주식 종목 조회 (삼성, 현대, SK, LG, 네이버, 카카오 등)"
    )
    @GetMapping("/trending/kr")
    public ResponseEntity<ApiResponse<List<RedditStockMentionDto>>> getTrendingKRStocks() {
        List<RedditStockMentionDto> data = redditStockService.getTrendingKRStocks();
        if (data.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.fail("데이터를 가져올 수 없습니다."));
        }
        return ResponseEntity.ok(ApiResponse.success("한국 주식 관련 정보 조회 성공", data));
    }

    @Operation(
        summary = "서브레딧 인기 게시글",
        description = "특정 서브레딧의 인기 게시글 조회 (wallstreetbets, stocks, investing 등)"
    )
    @GetMapping("/posts/{subreddit}")
    public ResponseEntity<ApiResponse<List<RedditPostDto>>> getHotPosts(
        @Parameter(description = "서브레딧 이름 (예: wallstreetbets, stocks)")
        @PathVariable String subreddit
    ) {
        List<RedditPostDto> data = redditStockService.getHotPosts(subreddit);
        if (data.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.fail("게시글을 가져올 수 없습니다."));
        }
        return ResponseEntity.ok(ApiResponse.success("서브레딧 게시글 조회 성공", data));
    }
}

