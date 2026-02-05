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
     * - 기본: 캐시된 가격으로 빠르게 반환
     * - refresh=true: 현재가 갱신 후 반환 (느림)
     */
    @GetMapping("/portfolio")
    public ResponseEntity<Map<String, Object>> getPortfolio(
            @RequestParam(defaultValue = "false") boolean refresh) {
        if (refresh) {
            virtualTradeService.updatePortfolioPrices();
        }
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
     * - 봇 활성화 후 즉시 매수 로직 1회 실행
     */
    @PostMapping("/bot/start")
    public ResponseEntity<Map<String, Object>> startBot(
            @RequestParam(defaultValue = "VIRTUAL") String mode) {
        TradingMode tradingMode = parseTradingMode(mode);

        BotStatusDto status = autoTradingBotService.startBot(tradingMode);

        // 봇 시작 후 즉시 매수 로직 실행 (비동기)
        new Thread(() -> {
            try {
                Thread.sleep(1000); // 1초 대기 후 실행
                log.info("[봇 시작] 즉시 매수 로직 실행 시작");
                autoTradingBotService.executeBuyLogic();
            } catch (Exception e) {
                log.error("[봇 시작] 즉시 매수 로직 실행 실패: {}", e.getMessage());
            }
        }).start();

        Map<String, Object> response = buildSuccessResponse(status);
        response.put("message", String.format("자동매매 봇이 %s 모드로 시작되었습니다. 매수 로직을 즉시 실행합니다.",
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
     * 수동으로 매수 로직 실행 (테스트/디버깅용)
     * POST /api/paper-trading/bot/trigger-buy
     */
    @PostMapping("/bot/trigger-buy")
    public ResponseEntity<Map<String, Object>> triggerBuyLogic() {
        log.info("===== 수동 매수 로직 트리거 =====");

        BotStatusDto status = autoTradingBotService.getBotStatus();
        if (!status.getActive()) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "봇이 비활성화 상태입니다. 먼저 봇을 시작해주세요.");
            return ResponseEntity.badRequest().body(response);
        }

        // 매수 로직 수동 실행
        autoTradingBotService.executeBuyLogic();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "매수 로직이 수동으로 실행되었습니다. 로그를 확인해주세요.");
        response.put("botStatus", autoTradingBotService.getBotStatus());
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
