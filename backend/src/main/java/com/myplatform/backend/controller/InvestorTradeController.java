package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.dto.InvestorSurgeDto;
import com.myplatform.backend.dto.InvestorTradeDto;
import com.myplatform.backend.dto.StockInvestorDetailDto;
import com.myplatform.backend.service.InvestorSurgeService;
import com.myplatform.backend.service.InvestorTradeService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "투자자 매매", description = "투자자별 매매 정보 조회 API")
@RestController
@RequestMapping("/api/investor")
@RequiredArgsConstructor
@SecurityRequirement(name = "JWT Bearer")
public class InvestorTradeController {

    private final InvestorTradeService investorTradeService;
    private final InvestorSurgeService investorSurgeService;
    private final com.myplatform.backend.service.KoreaInvestmentService koreaInvestmentService;

    @Operation(summary = "투자자별 상위 매수/매도 종목 조회")
    @GetMapping("/top-trades")
    public ResponseEntity<ApiResponse<List<InvestorTradeDto>>> getTopTrades(
            @RequestParam String investorType,
            @RequestParam String tradeType,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        
        List<InvestorTradeDto> trades = investorTradeService.getTopTradesByInvestor(
                investorType.toUpperCase(), tradeType.toUpperCase(), limit);
        
        return ResponseEntity.ok(ApiResponse.success(trades));
    }

    @Operation(summary = "전체 투자자 상위 매매 종목 조회")
    @GetMapping("/all-top-trades")
    public ResponseEntity<ApiResponse<Map<String, List<InvestorTradeDto>>>> getAllTopTrades(
            @RequestParam String tradeType,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        
        Map<String, List<InvestorTradeDto>> trades = investorTradeService.getAllInvestorTopTrades(
                tradeType.toUpperCase(), limit);
        
        return ResponseEntity.ok(ApiResponse.success(trades));
    }

    @Operation(summary = "종목별 투자자 매매 상세 조회")
    @GetMapping("/stock/{stockCode}")
    public ResponseEntity<ApiResponse<StockInvestorDetailDto>> getStockInvestorDetail(
            @PathVariable String stockCode,
            @RequestParam(required = false, defaultValue = "30") Integer days) {
        
        StockInvestorDetailDto detail = investorTradeService.getStockInvestorDetail(stockCode, days);
        
        if (detail == null) {
            return ResponseEntity.ok(ApiResponse.fail("해당 종목의 투자자 매매 데이터가 없습니다."));
        }
        
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    @Operation(summary = "투자자별 매매 데이터 수집", description = "한국투자증권 API를 통해 투자자별 매매 데이터를 수집합니다. KIS API는 당일 데이터만 반환합니다.")
    @PostMapping("/collect")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> collectData(
            @RequestParam(required = false) String date) {

        // KIS API는 당일 데이터만 반환하므로, date 파라미터는 무시하고 오늘 날짜 사용
        LocalDate tradeDate = LocalDate.now();

        Map<String, Integer> result = investorTradeService.collectInvestorTradeData(tradeDate);

        return ResponseEntity.ok(ApiResponse.success("데이터 수집 완료 (" + tradeDate + ")", result));
    }

    @Operation(summary = "최근 N일 데이터 수집", description = "최근 N일간의 투자자별 매매 데이터를 수집합니다.")
    @PostMapping("/collect/recent")
    public ResponseEntity<ApiResponse<Map<String, Object>>> collectRecentData(
            @RequestParam(required = false, defaultValue = "5") Integer days) {

        Map<String, Object> result = investorTradeService.collectRecentData(days);

        return ResponseEntity.ok(ApiResponse.success("최근 데이터 수집 완료", result));
    }

    @Operation(summary = "전체 데이터 삭제 후 재수집", description = "기존 데이터를 모두 삭제하고 새로 수집합니다.")
    @PostMapping("/recollect")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteAndRecollect() {

        Map<String, Object> result = investorTradeService.deleteAllAndRecollect();

        return ResponseEntity.ok(ApiResponse.success("데이터 삭제 후 재수집 완료", result));
    }

    @Operation(summary = "연속 매수 종목 조회", description = "특정 투자자가 N일 연속 순매수 상위에 오른 종목을 조회합니다.")
    @GetMapping("/consecutive-buy")
    public ResponseEntity<ApiResponse<List<ConsecutiveBuyDto>>> getConsecutiveBuyStocks(
            @RequestParam String investorType,
            @RequestParam(required = false, defaultValue = "3") Integer minDays) {

        List<ConsecutiveBuyDto> stocks = investorTradeService.getConsecutiveBuyStocks(
                investorType.toUpperCase(), minDays);

        return ResponseEntity.ok(ApiResponse.success(stocks));
    }

    @Operation(summary = "전체 투자자 연속 매수 종목 조회", description = "외국인, 기관의 연속 매수 종목을 모두 조회합니다.")
    @GetMapping("/consecutive-buy/all")
    public ResponseEntity<ApiResponse<Map<String, List<ConsecutiveBuyDto>>>> getAllConsecutiveBuyStocks(
            @RequestParam(required = false, defaultValue = "3") Integer minDays) {

        Map<String, List<ConsecutiveBuyDto>> stocks = investorTradeService.getAllConsecutiveBuyStocks(minDays);

        return ResponseEntity.ok(ApiResponse.success(stocks));
    }

    // ========== 수급 급증 관련 API ==========

    @Operation(summary = "수급 급증 종목 조회", description = "장중 외국인/기관 순매수 급증 종목을 조회합니다.")
    @GetMapping("/surge")
    public ResponseEntity<ApiResponse<List<InvestorSurgeDto>>> getSurgeStocks(
            @RequestParam String investorType,
            @RequestParam(required = false) BigDecimal minChange) {

        List<InvestorSurgeDto> stocks = investorSurgeService.getSurgeStocks(
                investorType.toUpperCase(), minChange);

        return ResponseEntity.ok(ApiResponse.success(stocks));
    }

    @Operation(summary = "전체 투자자 수급 급증 종목 조회", description = "외국인, 기관의 수급 급증 종목을 모두 조회합니다.")
    @GetMapping("/surge/all")
    public ResponseEntity<ApiResponse<Map<String, List<InvestorSurgeDto>>>> getAllSurgeStocks(
            @RequestParam(required = false) BigDecimal minChange) {

        Map<String, List<InvestorSurgeDto>> stocks = investorSurgeService.getAllSurgeStocks(minChange);

        return ResponseEntity.ok(ApiResponse.success(stocks));
    }

    @Operation(summary = "외국인+기관 공통 순매수 종목 조회", description = "외국인과 기관이 동시에 순매수한 종목을 조회합니다.")
    @GetMapping("/surge/common")
    public ResponseEntity<ApiResponse<List<InvestorSurgeDto>>> getCommonSurgeStocks(
            @RequestParam(required = false) BigDecimal minChange) {

        List<InvestorSurgeDto> stocks = investorSurgeService.getCommonSurgeStocks(minChange);

        return ResponseEntity.ok(ApiResponse.success(stocks));
    }

    @Operation(summary = "종목 장중 수급 추이", description = "특정 종목의 당일 시간대별 수급 변화를 조회합니다.")
    @GetMapping("/surge/trend/{stockCode}")
    public ResponseEntity<ApiResponse<List<InvestorSurgeDto>>> getStockIntradayTrend(
            @PathVariable String stockCode,
            @RequestParam(required = false, defaultValue = "FOREIGN") String investorType) {

        List<InvestorSurgeDto> trend = investorSurgeService.getStockIntradayTrend(
                stockCode, investorType.toUpperCase());

        return ResponseEntity.ok(ApiResponse.success(trend));
    }

    @Operation(summary = "장중 스냅샷 수동 수집", description = "수동으로 현재 시점의 수급 스냅샷을 수집합니다.")
    @PostMapping("/surge/collect")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> collectSurgeSnapshot() {

        Map<String, Integer> result = investorSurgeService.collectSnapshotManually();

        return ResponseEntity.ok(ApiResponse.success("스냅샷 수집 완료", result));
    }

    @Operation(summary = "API 테스트", description = "KIS API 응답을 직접 확인합니다.")
    @GetMapping("/test-api")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testApi() {
        Map<String, Object> result = new java.util.HashMap<>();

        result.put("serverTime", java.time.LocalDateTime.now().toString());
        result.put("serverDate", java.time.LocalDate.now().toString());
        result.put("dayOfWeek", java.time.LocalDate.now().getDayOfWeek().toString());
        result.put("apiConfigured", koreaInvestmentService.isConfigured());

        try {
            // 외국인 순매수 API 테스트
            com.fasterxml.jackson.databind.JsonNode foreignBuy = koreaInvestmentService.getForeignInstitutionTotal("1", true, true);

            if (foreignBuy != null) {
                result.put("foreignBuy_rtCd", foreignBuy.has("rt_cd") ? foreignBuy.get("rt_cd").asText() : "없음");
                result.put("foreignBuy_msg", foreignBuy.has("msg1") ? foreignBuy.get("msg1").asText() : "없음");
                result.put("foreignBuy_count", foreignBuy.has("output") && foreignBuy.get("output").isArray()
                        ? foreignBuy.get("output").size() : 0);
            } else {
                result.put("foreignBuy_error", "응답이 null입니다");
            }
        } catch (Exception e) {
            result.put("foreignBuy_error", e.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
