package com.myplatform.backend.controller;

import com.myplatform.backend.dto.PaperTradingDto.*;
import com.myplatform.backend.service.AutoTradingBotService;
import com.myplatform.backend.service.VirtualTradeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 모의투자 (Paper Trading) API Controller
 */
@RestController
@RequestMapping("/api/paper-trading")
@RequiredArgsConstructor
@Slf4j
public class PaperTradingController {

    private final VirtualTradeService virtualTradeService;
    private final AutoTradingBotService autoTradingBotService;

    /**
     * 계좌 요약 조회
     * GET /api/paper-trading/account/summary
     */
    @GetMapping("/account/summary")
    public ResponseEntity<Map<String, Object>> getAccountSummary() {
        Map<String, Object> response = new HashMap<>();
        try {
            AccountSummaryDto summary = virtualTradeService.getAccountSummary();
            response.put("success", true);
            response.put("data", summary);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("계좌 요약 조회 실패", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 계좌 초기화 (사용자 지정 금액)
     * POST /api/paper-trading/account/initialize
     * @param request 초기 자본금 (선택, 미입력 시 1,000만원)
     */
    @PostMapping("/account/initialize")
    public ResponseEntity<Map<String, Object>> initializeAccount(@RequestBody(required = false) InitializeAccountRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            java.math.BigDecimal initialBalance = (request != null) ? request.getInitialBalance() : null;
            AccountSummaryDto summary = virtualTradeService.initializeAccount(initialBalance);

            String formattedAmount = String.format("%,d", summary.getInitialBalance().longValue());
            response.put("success", true);
            response.put("data", summary);
            response.put("message", "계좌가 초기화되었습니다. 초기자본: " + formattedAmount + "원");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("계좌 초기화 실패", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 계좌 초기화 요청 DTO
     */
    @lombok.Data
    public static class InitializeAccountRequest {
        private java.math.BigDecimal initialBalance;
    }

    /**
     * 포트폴리오 조회
     * GET /api/paper-trading/portfolio
     */
    @GetMapping("/portfolio")
    public ResponseEntity<Map<String, Object>> getPortfolio() {
        Map<String, Object> response = new HashMap<>();
        try {
            // 현재가 업데이트
            virtualTradeService.updatePortfolioPrices();

            List<PortfolioItemDto> portfolio = virtualTradeService.getPortfolio();
            response.put("success", true);
            response.put("data", portfolio);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("포트폴리오 조회 실패", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 거래 내역 조회 (페이징)
     * GET /api/paper-trading/trades?page=0&size=20
     */
    @GetMapping("/trades")
    public ResponseEntity<Map<String, Object>> getTradeHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> response = new HashMap<>();
        try {
            Page<TradeHistoryDto> trades = virtualTradeService.getTradeHistory(page, size);
            response.put("success", true);
            response.put("data", trades.getContent());
            response.put("totalPages", trades.getTotalPages());
            response.put("totalElements", trades.getTotalElements());
            response.put("currentPage", page);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("거래 내역 조회 실패", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 거래 통계 조회
     * GET /api/paper-trading/statistics
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        Map<String, Object> response = new HashMap<>();
        try {
            TradeStatisticsDto statistics = virtualTradeService.getStatistics();
            response.put("success", true);
            response.put("data", statistics);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("거래 통계 조회 실패", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 수동 매수/매도
     * POST /api/paper-trading/trades
     */
    @PostMapping("/trades")
    public ResponseEntity<Map<String, Object>> placeTrade(@RequestBody TradeRequestDto request) {
        Map<String, Object> response = new HashMap<>();
        try {
            TradeHistoryDto result;

            if ("BUY".equalsIgnoreCase(request.getTradeType())) {
                result = virtualTradeService.buy(
                        request.getStockCode(),
                        request.getPrice(),
                        request.getQuantity(),
                        "MANUAL"
                );
                response.put("message", "매수 체결 완료");
            } else if ("SELL".equalsIgnoreCase(request.getTradeType())) {
                result = virtualTradeService.sell(
                        request.getStockCode(),
                        request.getPrice(),
                        request.getQuantity(),
                        "MANUAL"
                );
                response.put("message", "매도 체결 완료");
            } else {
                response.put("success", false);
                response.put("error", "유효하지 않은 거래 유형입니다. BUY 또는 SELL을 입력하세요.");
                return ResponseEntity.badRequest().body(response);
            }

            response.put("success", true);
            response.put("data", result);
            return ResponseEntity.ok(response);

        } catch (IllegalStateException e) {
            log.warn("거래 실패: {}", e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("거래 처리 실패", e);
            response.put("success", false);
            response.put("error", "거래 처리 중 오류가 발생했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 봇 상태 조회
     * GET /api/paper-trading/bot/status
     */
    @GetMapping("/bot/status")
    public ResponseEntity<Map<String, Object>> getBotStatus() {
        Map<String, Object> response = new HashMap<>();
        try {
            BotStatusDto status = autoTradingBotService.getBotStatus();
            response.put("success", true);
            response.put("data", status);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("봇 상태 조회 실패", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 봇 시작
     * POST /api/paper-trading/bot/start
     */
    @PostMapping("/bot/start")
    public ResponseEntity<Map<String, Object>> startBot() {
        Map<String, Object> response = new HashMap<>();
        try {
            BotStatusDto status = autoTradingBotService.startBot();
            response.put("success", true);
            response.put("data", status);
            response.put("message", "자동매매 봇이 시작되었습니다.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("봇 시작 실패", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 봇 중지
     * POST /api/paper-trading/bot/stop
     */
    @PostMapping("/bot/stop")
    public ResponseEntity<Map<String, Object>> stopBot() {
        Map<String, Object> response = new HashMap<>();
        try {
            BotStatusDto status = autoTradingBotService.stopBot();
            response.put("success", true);
            response.put("data", status);
            response.put("message", "자동매매 봇이 중지되었습니다.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("봇 중지 실패", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 포트폴리오 현재가 수동 업데이트
     * POST /api/paper-trading/portfolio/refresh
     */
    @PostMapping("/portfolio/refresh")
    public ResponseEntity<Map<String, Object>> refreshPortfolio() {
        Map<String, Object> response = new HashMap<>();
        try {
            virtualTradeService.updatePortfolioPrices();
            List<PortfolioItemDto> portfolio = virtualTradeService.getPortfolio();
            response.put("success", true);
            response.put("data", portfolio);
            response.put("message", "포트폴리오 현재가가 업데이트되었습니다.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("포트폴리오 업데이트 실패", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}
