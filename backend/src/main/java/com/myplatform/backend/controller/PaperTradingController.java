package com.myplatform.backend.controller;

import com.myplatform.backend.dto.PaperTradingDto.*;
import com.myplatform.backend.service.AutoTradingBotService;
import com.myplatform.backend.service.AutoTradingBotService.TradingMode;
import com.myplatform.backend.service.RealTradeService;
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
 * 모의투자/실전투자 (Trading) API Controller
 * 예외 처리는 GlobalExceptionHandler에서 일괄 처리
 */
@RestController
@RequestMapping("/api/paper-trading")
@RequiredArgsConstructor
@Slf4j
public class PaperTradingController {

    private final VirtualTradeService virtualTradeService;
    private final RealTradeService realTradeService;
    private final AutoTradingBotService autoTradingBotService;

    /**
     * 계좌 요약 조회
     * GET /api/paper-trading/account/summary
     */
    @GetMapping("/account/summary")
    public ResponseEntity<Map<String, Object>> getAccountSummary() {
        AccountSummaryDto summary = virtualTradeService.getAccountSummary();
        return ResponseEntity.ok(buildSuccessResponse(summary));
    }

    /**
     * 계좌 초기화 (사용자 지정 금액)
     * POST /api/paper-trading/account/initialize
     */
    @PostMapping("/account/initialize")
    public ResponseEntity<Map<String, Object>> initializeAccount(@RequestBody(required = false) InitializeAccountRequest request) {
        java.math.BigDecimal initialBalance = (request != null) ? request.getInitialBalance() : null;
        AccountSummaryDto summary = virtualTradeService.initializeAccount(initialBalance);

        String formattedAmount = String.format("%,d", summary.getInitialBalance().longValue());
        Map<String, Object> response = buildSuccessResponse(summary);
        response.put("message", "계좌가 초기화되었습니다. 초기자본: " + formattedAmount + "원");
        return ResponseEntity.ok(response);
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
        virtualTradeService.updatePortfolioPrices();
        List<PortfolioItemDto> portfolio = virtualTradeService.getPortfolio();
        return ResponseEntity.ok(buildSuccessResponse(portfolio));
    }

    /**
     * 거래 내역 조회 (페이징)
     * GET /api/paper-trading/trades?page=0&size=20
     */
    @GetMapping("/trades")
    public ResponseEntity<Map<String, Object>> getTradeHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<TradeHistoryDto> trades = virtualTradeService.getTradeHistory(page, size);

        Map<String, Object> response = buildSuccessResponse(trades.getContent());
        response.put("totalPages", trades.getTotalPages());
        response.put("totalElements", trades.getTotalElements());
        response.put("currentPage", page);
        return ResponseEntity.ok(response);
    }

    /**
     * 거래 통계 조회
     * GET /api/paper-trading/statistics
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        TradeStatisticsDto statistics = virtualTradeService.getStatistics();
        return ResponseEntity.ok(buildSuccessResponse(statistics));
    }

    /**
     * 수동 매수/매도
     * POST /api/paper-trading/trades
     */
    @PostMapping("/trades")
    public ResponseEntity<Map<String, Object>> placeTrade(@RequestBody TradeRequestDto request) {
        validateTradeType(request.getTradeType());

        TradeHistoryDto result;
        String message;

        if ("BUY".equalsIgnoreCase(request.getTradeType())) {
            result = virtualTradeService.buy(
                    request.getStockCode(),
                    request.getPrice(),
                    request.getQuantity(),
                    "MANUAL"
            );
            message = "매수 체결 완료";
        } else {
            result = virtualTradeService.sell(
                    request.getStockCode(),
                    request.getPrice(),
                    request.getQuantity(),
                    "MANUAL"
            );
            message = "매도 체결 완료";
        }

        Map<String, Object> response = buildSuccessResponse(result);
        response.put("message", message);
        return ResponseEntity.ok(response);
    }

    /**
     * 봇 상태 조회
     * GET /api/paper-trading/bot/status
     */
    @GetMapping("/bot/status")
    public ResponseEntity<Map<String, Object>> getBotStatus() {
        BotStatusDto status = autoTradingBotService.getBotStatus();
        return ResponseEntity.ok(buildSuccessResponse(status));
    }

    /**
     * 봇 시작 (모드 지정)
     * POST /api/paper-trading/bot/start?mode=VIRTUAL (기본) 또는 REAL
     */
    @PostMapping("/bot/start")
    public ResponseEntity<Map<String, Object>> startBot(
            @RequestParam(defaultValue = "VIRTUAL") String mode) {
        TradingMode tradingMode = parseTradingMode(mode);

        BotStatusDto status = autoTradingBotService.startBot(tradingMode);
        Map<String, Object> response = buildSuccessResponse(status);
        response.put("message", String.format("자동매매 봇이 %s 모드로 시작되었습니다.",
                tradingMode.getDisplayName()));
        return ResponseEntity.ok(response);
    }

    /**
     * 봇 중지
     * POST /api/paper-trading/bot/stop
     */
    @PostMapping("/bot/stop")
    public ResponseEntity<Map<String, Object>> stopBot() {
        BotStatusDto status = autoTradingBotService.stopBot();
        Map<String, Object> response = buildSuccessResponse(status);
        response.put("message", "자동매매 봇이 중지되었습니다.");
        return ResponseEntity.ok(response);
    }

    /**
     * 포트폴리오 현재가 수동 업데이트
     * POST /api/paper-trading/portfolio/refresh
     */
    @PostMapping("/portfolio/refresh")
    public ResponseEntity<Map<String, Object>> refreshPortfolio() {
        virtualTradeService.updatePortfolioPrices();
        List<PortfolioItemDto> portfolio = virtualTradeService.getPortfolio();
        Map<String, Object> response = buildSuccessResponse(portfolio);
        response.put("message", "포트폴리오 현재가가 업데이트되었습니다.");
        return ResponseEntity.ok(response);
    }

    // ========== 실전투자 API ==========

    /**
     * 실전투자 계좌 요약 조회
     * GET /api/paper-trading/real/account/summary
     */
    @GetMapping("/real/account/summary")
    public ResponseEntity<Map<String, Object>> getRealAccountSummary() {
        AccountSummaryDto summary = realTradeService.getAccountSummary();
        return ResponseEntity.ok(buildSuccessResponse(summary));
    }

    /**
     * 실전투자 포트폴리오 조회
     * GET /api/paper-trading/real/portfolio
     */
    @GetMapping("/real/portfolio")
    public ResponseEntity<Map<String, Object>> getRealPortfolio() {
        realTradeService.updatePortfolioPrices();
        List<PortfolioItemDto> portfolio = realTradeService.getPortfolio();
        return ResponseEntity.ok(buildSuccessResponse(portfolio));
    }

    /**
     * 실전투자 수동 매수/매도
     * POST /api/paper-trading/real/trades
     */
    @PostMapping("/real/trades")
    public ResponseEntity<Map<String, Object>> placeRealTrade(@RequestBody TradeRequestDto request) {
        validateTradeType(request.getTradeType());

        TradeHistoryDto result;
        String message;

        if ("BUY".equalsIgnoreCase(request.getTradeType())) {
            result = realTradeService.buy(
                    request.getStockCode(),
                    request.getPrice(),
                    request.getQuantity(),
                    "MANUAL"
            );
            message = "실전 매수 주문이 체결되었습니다.";
        } else {
            result = realTradeService.sell(
                    request.getStockCode(),
                    request.getPrice(),
                    request.getQuantity(),
                    "MANUAL"
            );
            message = "실전 매도 주문이 체결되었습니다.";
        }

        Map<String, Object> response = buildSuccessResponse(result);
        response.put("message", message);
        return ResponseEntity.ok(response);
    }

    /**
     * 현재 봇 모드 조회
     * GET /api/paper-trading/bot/mode
     */
    @GetMapping("/bot/mode")
    public ResponseEntity<Map<String, Object>> getBotMode() {
        TradingMode mode = autoTradingBotService.getCurrentMode();
        BotStatusDto status = autoTradingBotService.getBotStatus();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("mode", mode.name());
        response.put("modeName", mode.getDisplayName());
        response.put("active", status.getActive());
        response.put("status", status.getStatus());
        return ResponseEntity.ok(response);
    }

    // ========== 헬퍼 메서드 ==========

    /**
     * 성공 응답 생성
     */
    private Map<String, Object> buildSuccessResponse(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    /**
     * 거래 유형 유효성 검증
     */
    private void validateTradeType(String tradeType) {
        if (!"BUY".equalsIgnoreCase(tradeType) && !"SELL".equalsIgnoreCase(tradeType)) {
            throw new IllegalArgumentException("유효하지 않은 거래 유형입니다. BUY 또는 SELL을 입력하세요.");
        }
    }

    /**
     * 트레이딩 모드 파싱
     */
    private TradingMode parseTradingMode(String mode) {
        try {
            return TradingMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("유효하지 않은 모드입니다. VIRTUAL 또는 REAL을 입력하세요.");
        }
    }
}
