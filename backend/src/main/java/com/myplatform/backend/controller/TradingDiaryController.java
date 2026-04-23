package com.myplatform.backend.controller;

import com.myplatform.backend.entity.WeeklyTradingReport;
import com.myplatform.backend.service.TradingDiaryService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * 주간 매매 일지 — ADMIN 전용 (/api/admin/** 정책 적용).
 * mode = REAL or VIRTUAL.
 */
@Tag(name = "매매 일지", description = "AI 주간 매매 분석 리포트")
@RestController
@RequestMapping("/api/admin/trading/diary")
public class TradingDiaryController {

    private final TradingDiaryService diary;

    public TradingDiaryController(TradingDiaryService diary) {
        this.diary = diary;
    }

    @Operation(summary = "최신 리포트 조회")
    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<WeeklyTradingReport>> latest(
            @RequestParam(defaultValue = "REAL") String mode) {
        Optional<WeeklyTradingReport> r = diary.getLatest(mode);
        return ResponseEntity.ok(ApiResponse.success(r.orElse(null)));
    }

    @Operation(summary = "최근 12주 리포트 목록")
    @GetMapping("/recent")
    public ResponseEntity<ApiResponse<List<WeeklyTradingReport>>> recent(
            @RequestParam(defaultValue = "REAL") String mode) {
        return ResponseEntity.ok(ApiResponse.success(diary.getRecent(mode)));
    }

    @Operation(summary = "지난주 리포트 즉시 생성/재생성")
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<WeeklyTradingReport>> generate(
            @RequestParam(defaultValue = "REAL") String mode) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth == null ? "manual" : auth.getName();
        WeeklyTradingReport saved = diary.generateLastWeekReport(mode, username);
        return ResponseEntity.ok(ApiResponse.success("리포트가 생성되었습니다.", saved));
    }
}
