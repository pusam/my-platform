package com.myplatform.backend.controller;

import com.myplatform.backend.dto.NewsFetchAsyncResponse;
import com.myplatform.backend.dto.NewsPollItemDto;
import com.myplatform.backend.dto.NewsSummaryDto;
import com.myplatform.backend.service.NewsService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

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

    @Operation(summary = "최신 뉴스 폴링", description = "지정된 분 이내에 새로 수집된 뉴스를 조회합니다. 긴급(HOT) 뉴스는 urgent=true로 표시됩니다.")
    @GetMapping("/poll")
    public ResponseEntity<ApiResponse<List<NewsPollItemDto>>> pollNews(
            @RequestParam(defaultValue = "15") int minutes
    ) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(minutes);
        List<NewsSummaryDto> recent = newsService.getNewsSince(since);

        List<NewsPollItemDto> result = recent.stream()
                .map(dto -> NewsPollItemDto.builder()
                        .id(dto.getId())
                        .title(dto.getTitle())
                        .summary(dto.getSummary())
                        .sourceName(dto.getSourceName())
                        .sourceUrl(dto.getSourceUrl())
                        .sentiment(dto.getSentiment())
                        .sentimentLabel(dto.getSentimentLabel())
                        .summarizedAt(dto.getSummarizedAt())
                        .urgent(newsService.isUrgentNews(dto.getTitle()))
                        .build())
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success("최신 뉴스 폴링 완료", result));
    }

    @Operation(
        summary = "뉴스 수동 수집 (비동기)",
        description = "경제 뉴스를 백그라운드에서 수집하고 요약합니다. 즉시 응답을 반환하며, 수집은 백그라운드에서 진행됩니다."
    )
    @PostMapping("/fetch")
    public ResponseEntity<ApiResponse<NewsFetchAsyncResponse>> fetchNews() {
        // 비동기로 뉴스 수집 시작 (결과를 기다리지 않음)
        newsService.manualFetchNewsAsync();

        NewsFetchAsyncResponse result = NewsFetchAsyncResponse.builder()
                .status("STARTED")
                .message("뉴스 수집 작업이 백그라운드에서 시작되었습니다. 완료까지 1~2분 소요될 수 있습니다.")
                .build();

        return ResponseEntity.ok(ApiResponse.success("뉴스 수집 작업 시작됨", result));
    }
}
