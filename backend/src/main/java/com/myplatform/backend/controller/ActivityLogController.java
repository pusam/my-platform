package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ActivityLogDto;
import com.myplatform.backend.service.ActivityLogService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "활동 로그", description = "활동 로그 관리 API (관리자 전용)")
@RestController
@RequestMapping("/api/admin/logs")
@RequiredArgsConstructor
@SecurityRequirement(name = "JWT Bearer")
@PreAuthorize("hasRole('ADMIN')")
public class ActivityLogController {

    private final ActivityLogService activityLogService;

    @Operation(summary = "활동 로그 목록", description = "활동 로그를 페이징하여 조회합니다.")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<ActivityLogDto>>> getLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String actionType) {
        Page<ActivityLogDto> logs = activityLogService.getLogsWithFilters(username, actionType, page, size);
        return ResponseEntity.ok(ApiResponse.success("조회 성공", logs));
    }

    @Operation(summary = "최근 활동 로그", description = "최근 100건의 활동 로그를 조회합니다.")
    @GetMapping("/recent")
    public ResponseEntity<ApiResponse<List<ActivityLogDto>>> getRecentLogs() {
        List<ActivityLogDto> logs = activityLogService.getRecentLogs();
        return ResponseEntity.ok(ApiResponse.success("조회 성공", logs));
    }
}
