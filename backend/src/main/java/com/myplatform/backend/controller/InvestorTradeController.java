package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.dto.InvestorTradeDto;
import com.myplatform.backend.dto.StockInvestorDetailDto;
import com.myplatform.backend.service.InvestorTradeService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @Operation(summary = "투자자별 매매 데이터 수집", description = "한국투자증권 API를 통해 특정 일자의 투자자별 매매 데이터를 수집합니다.")
    @PostMapping("/collect")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> collectData(
            @RequestParam(required = false) String date) {
        
        LocalDate tradeDate = date != null ? LocalDate.parse(date) : LocalDate.now().minusDays(1);
        
        Map<String, Integer> result = investorTradeService.collectInvestorTradeData(tradeDate);
        
        return ResponseEntity.ok(ApiResponse.success("데이터 수집 완료", result));
    }

    @Operation(summary = "최근 N일 데이터 수집", description = "최근 N일간의 투자자별 매매 데이터를 수집합니다.")
    @PostMapping("/collect/recent")
    public ResponseEntity<ApiResponse<Map<String, Object>>> collectRecentData(
            @RequestParam(required = false, defaultValue = "5") Integer days) {

        Map<String, Object> result = investorTradeService.collectRecentData(days);

        return ResponseEntity.ok(ApiResponse.success("최근 데이터 수집 완료", result));
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
}
