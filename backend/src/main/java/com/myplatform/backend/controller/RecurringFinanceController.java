package com.myplatform.backend.controller;

import com.myplatform.backend.dto.RecurringFinanceDto;
import com.myplatform.backend.dto.RecurringFinanceRequest;
import com.myplatform.backend.dto.RecurringFinanceUpdateRequest;
import com.myplatform.backend.service.RecurringFinanceService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "고정 수입/지출", description = "고정 수입 및 고정 지출 관리 API")
@RestController
@RequestMapping("/api/finance/recurring")
@SecurityRequirement(name = "JWT Bearer")
public class RecurringFinanceController {

    private final RecurringFinanceService recurringFinanceService;

    public RecurringFinanceController(RecurringFinanceService recurringFinanceService) {
        this.recurringFinanceService = recurringFinanceService;
    }

    @Operation(summary = "고정 수입/지출 등록", description = "새로운 고정 수입 또는 지출 항목을 등록합니다.")
    @PostMapping
    public ResponseEntity<ApiResponse<RecurringFinanceDto>> addRecurring(
            Authentication authentication,
            @RequestBody RecurringFinanceRequest request) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        RecurringFinanceDto result = recurringFinanceService.addRecurring(username, request);
        return ResponseEntity.ok(ApiResponse.success("등록되었습니다.", result));
    }

    @Operation(summary = "고정 수입/지출 목록", description = "사용자의 모든 고정 수입/지출 목록을 조회합니다.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<RecurringFinanceDto>>> getAllRecurring(
            Authentication authentication,
            @RequestParam(required = false) String type) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        List<RecurringFinanceDto> list;
        if (type != null && !type.isEmpty()) {
            list = recurringFinanceService.getRecurringByType(username, type);
        } else {
            list = recurringFinanceService.getAllRecurring(username);
        }
        return ResponseEntity.ok(ApiResponse.success("조회 성공", list));
    }

    @Operation(summary = "활성 고정 항목", description = "활성화된 고정 수입/지출만 조회합니다.")
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<RecurringFinanceDto>>> getActiveRecurring(
            Authentication authentication) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        List<RecurringFinanceDto> list = recurringFinanceService.getActiveRecurring(username);
        return ResponseEntity.ok(ApiResponse.success("조회 성공", list));
    }

    @Operation(summary = "월별 적용 항목", description = "특정 월에 적용되는 고정 수입/지출을 조회합니다.")
    @GetMapping("/month")
    public ResponseEntity<ApiResponse<List<RecurringFinanceDto>>> getRecurringForMonth(
            Authentication authentication,
            @RequestParam int year,
            @RequestParam int month) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        List<RecurringFinanceDto> list = recurringFinanceService.getRecurringForMonth(username, year, month);
        return ResponseEntity.ok(ApiResponse.success("조회 성공", list));
    }

    @Operation(summary = "고정 항목 수정", description = "고정 수입/지출의 금액을 수정합니다. 변경 이력이 기록됩니다.")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<RecurringFinanceDto>> updateRecurring(
            Authentication authentication,
            @PathVariable Long id,
            @RequestBody RecurringFinanceUpdateRequest request) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        RecurringFinanceDto result = recurringFinanceService.updateRecurring(username, id, request);
        return ResponseEntity.ok(ApiResponse.success("수정되었습니다.", result));
    }

    @Operation(summary = "고정 항목 비활성화", description = "고정 수입/지출을 비활성화(종료)합니다.")
    @PutMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<String>> deactivateRecurring(
            Authentication authentication,
            @PathVariable Long id) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        recurringFinanceService.deactivateRecurring(username, id);
        return ResponseEntity.ok(ApiResponse.success("비활성화되었습니다.", null));
    }

    @Operation(summary = "고정 항목 삭제", description = "고정 수입/지출을 삭제합니다.")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deleteRecurring(
            Authentication authentication,
            @PathVariable Long id) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        recurringFinanceService.deleteRecurring(username, id);
        return ResponseEntity.ok(ApiResponse.success("삭제되었습니다.", null));
    }
}
