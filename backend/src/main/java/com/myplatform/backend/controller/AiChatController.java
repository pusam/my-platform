package com.myplatform.backend.controller;

import com.myplatform.core.dto.ApiResponse;
import com.myplatform.backend.dto.ChatRequest;
import com.myplatform.backend.dto.ChatResponse;
import com.myplatform.backend.service.OllamaService;
import com.myplatform.backend.service.AssetService;
import com.myplatform.backend.service.FinanceTransactionService;
import com.myplatform.backend.dto.AssetSummaryDto;
import com.myplatform.backend.dto.FinanceSummaryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Tag(name = "AI 상담", description = "AI 상담사 API")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@SecurityRequirement(name = "JWT Bearer")
public class AiChatController {

    private final OllamaService ollamaService;
    private final AssetService assetService;
    private final FinanceTransactionService financeTransactionService;

    @Operation(summary = "AI 채팅", description = "AI 상담사와 대화합니다.")
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(
            Authentication authentication,
            @RequestBody ChatRequest request) {

        String username = authentication.getName();
        String response;

        if (request.isUseContext()) {
            // 맞춤 상담: 사용자 재무 데이터 포함
            String userContext = buildUserContext(username);
            response = ollamaService.consultWithContext(request.getMessage(), userContext);
        } else {
            // 일반 대화
            response = ollamaService.generalChat(request.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(response)));
    }

    @Operation(summary = "AI 서버 상태 확인", description = "AI 서버 연결 상태를 확인합니다.")
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Boolean>> checkStatus() {
        boolean available = ollamaService.isAvailable();
        return ResponseEntity.ok(ApiResponse.success(available));
    }

    /**
     * 사용자의 재무 컨텍스트를 생성합니다.
     */
    private String buildUserContext(String username) {
        StringBuilder context = new StringBuilder();

        try {
            // 자산 정보 조회
            AssetSummaryDto assetSummary = assetService.getAssetSummary(username);
            context.append("=== 자산 현황 ===\n");

            if (assetSummary.getGold() != null && assetSummary.getGold().getTotalQuantity().compareTo(BigDecimal.ZERO) > 0) {
                context.append(String.format("- 금: %.2fg (투자금: %,.0f원, 현재가치: %,.0f원, 수익률: %.2f%%)\n",
                        assetSummary.getGold().getTotalQuantity(),
                        assetSummary.getGold().getTotalInvestment(),
                        assetSummary.getGold().getCurrentValue(),
                        assetSummary.getGold().getProfitRate()));
            }

            if (assetSummary.getSilver() != null && assetSummary.getSilver().getTotalQuantity().compareTo(BigDecimal.ZERO) > 0) {
                context.append(String.format("- 은: %.2fg (투자금: %,.0f원, 현재가치: %,.0f원, 수익률: %.2f%%)\n",
                        assetSummary.getSilver().getTotalQuantity(),
                        assetSummary.getSilver().getTotalInvestment(),
                        assetSummary.getSilver().getCurrentValue(),
                        assetSummary.getSilver().getProfitRate()));
            }

            if (assetSummary.getStocks() != null && !assetSummary.getStocks().isEmpty()) {
                for (var stock : assetSummary.getStocks()) {
                    context.append(String.format("- 주식(%s): %,.0f주 (투자금: %,.0f원, 현재가치: %,.0f원, 수익률: %.2f%%)\n",
                            stock.getStockName(),
                            stock.getTotalQuantity(),
                            stock.getTotalInvestment(),
                            stock.getCurrentValue(),
                            stock.getProfitRate()));
                }
            }

        } catch (Exception e) {
            context.append("자산 정보를 불러올 수 없습니다.\n");
        }

        try {
            // 이번 달 가계부 정보 조회
            LocalDate now = LocalDate.now();
            FinanceSummaryDto financeSummary = financeTransactionService.getMonthlyTransactions(
                    username, now.getYear(), now.getMonthValue());

            context.append("\n=== 이번 달 가계부 ===\n");
            context.append(String.format("- 총 수입: %,.0f원\n", financeSummary.getTotalIncome()));
            context.append(String.format("- 총 지출: %,.0f원\n", financeSummary.getTotalExpense()));
            context.append(String.format("- 잔액: %,.0f원\n", financeSummary.getBalance()));

            if (financeSummary.getTotalIncome().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal savingRate = financeSummary.getBalance()
                        .divide(financeSummary.getTotalIncome(), 4, java.math.RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                context.append(String.format("- 저축률: %.1f%%\n", savingRate));
            }

        } catch (Exception e) {
            context.append("\n가계부 정보를 불러올 수 없습니다.\n");
        }

        return context.toString();
    }
}
