package com.myplatform.backend.controller;

import com.myplatform.core.dto.ApiResponse;
import com.myplatform.backend.dto.FinanceRecordDto;
import com.myplatform.backend.dto.FinanceRecordRequest;
import com.myplatform.backend.dto.FinanceSummaryDto;
import com.myplatform.backend.dto.FinanceTransactionDto;
import com.myplatform.backend.dto.FinanceTransactionRequest;
import com.myplatform.backend.service.FinanceRecordService;
import com.myplatform.backend.service.FinanceTransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "가계부", description = "수입/지출 관리 API")
@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
@SecurityRequirement(name = "JWT Bearer")
public class FinanceRecordController {

    private final FinanceRecordService financeRecordService;
    private final FinanceTransactionService transactionService;

    // ==================== 기존 API (호환성 유지) ====================

    @Operation(summary = "내 재무 기록 조회 (구버전)", description = "사용자의 월별 재무 기록을 조회합니다.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<FinanceRecordDto>>> getMyRecords(Authentication authentication) {
        String username = authentication.getName();
        List<FinanceRecordDto> records = financeRecordService.getMyRecords(username);
        return ResponseEntity.ok(ApiResponse.success(records));
    }

    @Operation(summary = "재무 기록 저장 (구버전)", description = "월별 재무 기록을 저장하거나 업데이트합니다.")
    @PostMapping
    public ResponseEntity<ApiResponse<FinanceRecordDto>> saveRecord(
            Authentication authentication,
            @RequestBody FinanceRecordRequest request) {
        String username = authentication.getName();
        FinanceRecordDto saved = financeRecordService.saveRecord(username, request);
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @Operation(summary = "재무 기록 삭제 (구버전)", description = "특정 재무 기록을 삭제합니다.")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRecord(
            Authentication authentication,
            @PathVariable Long id) {
        String username = authentication.getName();
        financeRecordService.deleteRecord(username, id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ==================== 새로운 거래 API ====================

    @Operation(summary = "월별 거래 내역 조회", description = "특정 월의 수입/지출 거래 내역을 조회합니다.")
    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<FinanceSummaryDto>> getMonthlyTransactions(
            Authentication authentication,
            @RequestParam int year,
            @RequestParam int month) {
        String username = authentication.getName();
        FinanceSummaryDto summary = transactionService.getMonthlyTransactions(username, year, month);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @Operation(summary = "전체 거래 내역 조회", description = "사용자의 모든 거래 내역을 조회합니다.")
    @GetMapping("/transactions/all")
    public ResponseEntity<ApiResponse<List<FinanceTransactionDto>>> getAllTransactions(
            Authentication authentication) {
        String username = authentication.getName();
        List<FinanceTransactionDto> transactions = transactionService.getAllTransactions(username);
        return ResponseEntity.ok(ApiResponse.success(transactions));
    }

    @Operation(summary = "거래 등록", description = "새로운 수입/지출 거래를 등록합니다.")
    @PostMapping("/transactions")
    public ResponseEntity<ApiResponse<FinanceTransactionDto>> addTransaction(
            Authentication authentication,
            @RequestBody FinanceTransactionRequest request) {
        String username = authentication.getName();
        FinanceTransactionDto saved = transactionService.addTransaction(username, request);
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @Operation(summary = "거래 삭제", description = "특정 거래를 삭제합니다.")
    @DeleteMapping("/transactions/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTransaction(
            Authentication authentication,
            @PathVariable Long id) {
        String username = authentication.getName();
        transactionService.deleteTransaction(username, id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
