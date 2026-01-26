package com.myplatform.backend.controller;

import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.service.InvestorDailyTradeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * 투자자별 일별 상위 매수/매도 종목 API
 */
@RestController
@RequestMapping("/api/investor-trades")
public class InvestorDailyTradeController {

    private final InvestorDailyTradeService tradeService;

    public InvestorDailyTradeController(InvestorDailyTradeService tradeService) {
        this.tradeService = tradeService;
    }

    /**
     * 데이터 수집 (당일)
     * POST /api/investor-trades/collect
     */
    @PostMapping("/collect")
    public ResponseEntity<Map<String, Object>> collectTodayTrades() {
        Map<String, Integer> result = tradeService.collectAllInvestorTrades();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "투자자별 거래 데이터 수집 완료",
                "data", result
        ));
    }

    /**
     * 데이터 수집 (특정 일자)
     * POST /api/investor-trades/collect/{date}
     */
    @PostMapping("/collect/{date}")
    public ResponseEntity<Map<String, Object>> collectTradesByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Map<String, Integer> result = tradeService.collectAllInvestorTrades(date);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", date + " 투자자별 거래 데이터 수집 완료",
                "data", result
        ));
    }

    /**
     * 특정 일자의 모든 투자자 거래 조회
     * GET /api/investor-trades?date=2025-01-20
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getTradesByDate(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            // 가장 최근 데이터 날짜 조회
            date = tradeService.getLatestTradeDate();
            if (date == null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "data", Map.of("trades", Map.of()),
                        "message", "수집된 데이터가 없습니다."
                ));
            }
        }

        List<InvestorDailyTrade> trades = tradeService.getTradesByDate(date);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "date", date.toString(),
                "data", groupTradesByInvestor(trades)
        ));
    }

    /**
     * 특정 시장의 투자자별 거래 조회
     * GET /api/investor-trades/market/{marketType}?date=2025-01-20
     */
    @GetMapping("/market/{marketType}")
    public ResponseEntity<Map<String, Object>> getTradesByMarket(
            @PathVariable String marketType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }

        List<InvestorDailyTrade> trades = tradeService.getTradesByMarketAndDate(marketType.toUpperCase(), date);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", groupTradesByInvestor(trades)
        ));
    }

    /**
     * 특정 투자자 유형의 거래 조회
     * GET /api/investor-trades/investor/{investorType}?date=2025-01-20
     */
    @GetMapping("/investor/{investorType}")
    public ResponseEntity<Map<String, Object>> getTradesByInvestor(
            @PathVariable String investorType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }

        List<InvestorDailyTrade> trades = tradeService.getTradesByInvestorAndDate(
                investorType.toUpperCase(), date);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", groupByTradeType(trades)
        ));
    }

    /**
     * 특정 시장, 투자자 유형의 거래 조회
     * GET /api/investor-trades/market/{marketType}/investor/{investorType}?date=2025-01-20
     */
    @GetMapping("/market/{marketType}/investor/{investorType}")
    public ResponseEntity<Map<String, Object>> getTradesByMarketAndInvestor(
            @PathVariable String marketType,
            @PathVariable String investorType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }

        List<InvestorDailyTrade> trades = tradeService.getTradesByMarketInvestorAndDate(
                marketType.toUpperCase(), investorType.toUpperCase(), date);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", groupByTradeType(trades)
        ));
    }

    /**
     * 연기금 거래 조회 (코스피)
     * GET /api/investor-trades/pension/kospi?date=2025-01-20
     */
    @GetMapping("/pension/kospi")
    public ResponseEntity<Map<String, Object>> getPensionKospiTrades(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }

        List<InvestorDailyTrade> trades = tradeService.getTradesByMarketInvestorAndDate(
                "KOSPI", InvestorDailyTradeService.INVESTOR_PENSION, date);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", groupByTradeType(trades)
        ));
    }

    /**
     * 연기금 거래 조회 (코스닥)
     * GET /api/investor-trades/pension/kosdaq?date=2025-01-20
     */
    @GetMapping("/pension/kosdaq")
    public ResponseEntity<Map<String, Object>> getPensionKosdaqTrades(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }

        List<InvestorDailyTrade> trades = tradeService.getTradesByMarketInvestorAndDate(
                "KOSDAQ", InvestorDailyTradeService.INVESTOR_PENSION, date);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", groupByTradeType(trades)
        ));
    }

    /**
     * 기간별 투자자 거래 조회
     * GET /api/investor-trades/investor/{investorType}/range?startDate=2025-01-01&endDate=2025-01-20
     */
    @GetMapping("/investor/{investorType}/range")
    public ResponseEntity<Map<String, Object>> getTradesByInvestorRange(
            @PathVariable String investorType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<InvestorDailyTrade> trades = tradeService.getTradesByInvestorAndDateRange(
                investorType.toUpperCase(), startDate, endDate);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", groupByDate(trades)
        ));
    }

    /**
     * 기간별 특정 종목의 투자자 거래 조회
     * GET /api/investor-trades/stock/{stockCode}/range?startDate=2025-01-01&endDate=2025-01-20
     */
    @GetMapping("/stock/{stockCode}/range")
    public ResponseEntity<Map<String, Object>> getTradesByStockRange(
            @PathVariable String stockCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<InvestorDailyTrade> trades = tradeService.getTradesByStockAndDateRange(
                stockCode, startDate, endDate);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", trades
        ));
    }

    /**
     * 기간별 누적 통계 조회
     * GET /api/investor-trades/stats/{investorType}/{marketType}?startDate=2025-01-01&endDate=2025-01-20
     */
    @GetMapping("/stats/{investorType}/{marketType}")
    public ResponseEntity<Map<String, Object>> getAccumulatedStats(
            @PathVariable String investorType,
            @PathVariable String marketType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<Map<String, Object>> stats = tradeService.getAccumulatedTrades(
                investorType.toUpperCase(), marketType.toUpperCase(), startDate, endDate);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", stats
        ));
    }

    /**
     * 데이터 존재하는 날짜 목록 조회
     * GET /api/investor-trades/dates/{marketType}/{investorType}
     */
    @GetMapping("/dates/{marketType}/{investorType}")
    public ResponseEntity<Map<String, Object>> getAvailableDates(
            @PathVariable String marketType,
            @PathVariable String investorType) {

        List<LocalDate> dates = tradeService.getAvailableDates(
                marketType.toUpperCase(), investorType.toUpperCase());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", dates
        ));
    }

    /**
     * 데이터 재수집
     * POST /api/investor-trades/recollect/{marketType}/{investorType}/{date}
     */
    @PostMapping("/recollect/{marketType}/{investorType}/{date}")
    public ResponseEntity<Map<String, Object>> recollect(
            @PathVariable String marketType,
            @PathVariable String investorType,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        int count = tradeService.recollect(
                marketType.toUpperCase(), investorType.toUpperCase(), date);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "재수집 완료",
                "count", count
        ));
    }

    /**
     * 중복 데이터 정리
     * POST /api/investor-trades/cleanup-duplicates
     */
    @PostMapping("/cleanup-duplicates")
    public ResponseEntity<Map<String, Object>> cleanupDuplicates() {
        int deleted = tradeService.cleanupDuplicates();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "중복 데이터 정리 완료",
                "deletedCount", deleted
        ));
    }

    /**
     * 특정 날짜 데이터 삭제
     * DELETE /api/investor-trades/{date}
     */
    @DeleteMapping("/{date}")
    public ResponseEntity<Map<String, Object>> deleteByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        tradeService.deleteByDate(date);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", date + " 데이터 삭제 완료"
        ));
    }

    /**
     * 투자자별로 그룹화
     */
    private Map<String, Object> groupTradesByInvestor(List<InvestorDailyTrade> trades) {
        Map<String, Map<String, List<InvestorDailyTrade>>> grouped = new LinkedHashMap<>();

        for (InvestorDailyTrade trade : trades) {
            String investor = trade.getInvestorType();
            String tradeType = trade.getTradeType();

            grouped.computeIfAbsent(investor, k -> new LinkedHashMap<>())
                   .computeIfAbsent(tradeType, k -> new ArrayList<>())
                   .add(trade);
        }

        return Map.of("trades", grouped);
    }

    /**
     * 거래 유형별로 그룹화
     */
    private Map<String, Object> groupByTradeType(List<InvestorDailyTrade> trades) {
        Map<String, List<InvestorDailyTrade>> grouped = new LinkedHashMap<>();
        grouped.put("BUY", new ArrayList<>());
        grouped.put("SELL", new ArrayList<>());

        for (InvestorDailyTrade trade : trades) {
            grouped.get(trade.getTradeType()).add(trade);
        }

        return Map.of(
                "buyTrades", grouped.get("BUY"),
                "sellTrades", grouped.get("SELL")
        );
    }

    /**
     * 날짜별로 그룹화
     */
    private Map<String, Object> groupByDate(List<InvestorDailyTrade> trades) {
        Map<LocalDate, Map<String, List<InvestorDailyTrade>>> grouped = new LinkedHashMap<>();

        for (InvestorDailyTrade trade : trades) {
            LocalDate date = trade.getTradeDate();
            String tradeType = trade.getTradeType();

            grouped.computeIfAbsent(date, k -> new LinkedHashMap<>())
                   .computeIfAbsent(tradeType, k -> new ArrayList<>())
                   .add(trade);
        }

        return Map.of("dateGrouped", grouped);
    }
}
