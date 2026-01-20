package com.myplatform.backend.controller;

import com.myplatform.backend.dto.NewsSummaryDto;
import com.myplatform.backend.service.NewsService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "뉴스 요약", description = "경제 뉴스 AI 요약 API")
@RestController
@RequestMapping("/api/news")
@SecurityRequirement(name = "JWT Bearer")
public class NewsController {

    private final NewsService newsService;

    public NewsController(NewsService newsService) {
        this.newsService = newsService;
    }

    @Operation(summary = "오늘의 뉴스 조회", description = "오늘 요약된 경제 뉴스 목록을 조회합니다.")
    @GetMapping("/today")
    public ResponseEntity<ApiResponse<List<NewsSummaryDto>>> getTodayNews() {
        List<NewsSummaryDto> news = newsService.getTodayNews();
        return ResponseEntity.ok(ApiResponse.success("오늘의 뉴스 조회 성공", news));
    }

    @Operation(summary = "최근 뉴스 조회", description = "최근 요약된 경제 뉴스 10개를 조회합니다.")
    @GetMapping("/recent")
    public ResponseEntity<ApiResponse<List<NewsSummaryDto>>> getRecentNews() {
        List<NewsSummaryDto> news = newsService.getRecentNews();
        return ResponseEntity.ok(ApiResponse.success("최근 뉴스 조회 성공", news));
    }

    @Operation(summary = "뉴스 수동 수집", description = "경제 뉴스를 수동으로 수집하고 요약합니다. (관리자용)")
    @PostMapping("/fetch")
    public ResponseEntity<ApiResponse<Integer>> fetchNews() {
        int count = newsService.manualFetchNews();
        return ResponseEntity.ok(ApiResponse.success("뉴스 수집 완료", count));
    }
}
