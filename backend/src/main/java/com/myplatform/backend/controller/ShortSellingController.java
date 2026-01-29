package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ShortSqueezeDto;
import com.myplatform.backend.entity.StockShortData;
import com.myplatform.backend.service.ShortSellingDataCollector;
import com.myplatform.backend.service.ShortSellingService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.myplatform.backend.service.NaverFinanceCrawler;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 공매도 분석 API
 * - 숏스퀴즈 후보 종목 조회
 * - 대차잔고/공매도 데이터 분석
 */
@Tag(name = "공매도 분석", description = "공매도 및 숏스퀴즈 분석 API")
@RestController
@RequestMapping("/api/short-selling")
@RequiredArgsConstructor
@SecurityRequirement(name = "JWT Bearer")
public class ShortSellingController {

    private final ShortSellingService shortSellingService;
    private final ShortSellingDataCollector shortSellingDataCollector;
    private final NaverFinanceCrawler naverFinanceCrawler;

    @Operation(summary = "숏스퀴즈 후보 종목 조회",
               description = "대차잔고 감소 + 외국인 매수 + 주가 상승 조건을 만족하는 종목을 분석합니다.")
    @GetMapping("/squeeze-candidates")
    public ResponseEntity<ApiResponse<List<ShortSqueezeDto>>> getSqueezeCandidates(
            @Parameter(description = "조회할 종목 수 (기본 30)")
            @RequestParam(required = false, defaultValue = "30") Integer limit) {

        List<ShortSqueezeDto> candidates = shortSellingService.getShortSqueezeCandidates(limit);

        return ResponseEntity.ok(ApiResponse.success(candidates));
    }

    @Operation(summary = "대차잔고 상위 종목",
               description = "대차잔고 비율이 높은 종목을 조회합니다 (공매도 세력이 많이 쌓인 종목).")
    @GetMapping("/top-loan-balance")
    public ResponseEntity<ApiResponse<List<ShortSqueezeDto>>> getTopLoanBalanceStocks(
            @Parameter(description = "조회할 종목 수 (기본 50)")
            @RequestParam(required = false, defaultValue = "50") Integer limit) {

        List<ShortSqueezeDto> stocks = shortSellingService.getTopLoanBalanceStocks(limit);

        return ResponseEntity.ok(ApiResponse.success(stocks));
    }

    @Operation(summary = "공매도 비중 상위 종목",
               description = "당일 공매도 거래 비중이 높은 종목을 조회합니다.")
    @GetMapping("/top-short-ratio")
    public ResponseEntity<ApiResponse<List<ShortSqueezeDto>>> getTopShortRatioStocks(
            @Parameter(description = "조회할 종목 수 (기본 50)")
            @RequestParam(required = false, defaultValue = "50") Integer limit) {

        List<ShortSqueezeDto> stocks = shortSellingService.getTopShortRatioStocks(limit);

        return ResponseEntity.ok(ApiResponse.success(stocks));
    }

    @Operation(summary = "특정 종목 공매도 추이",
               description = "특정 종목의 공매도 및 대차잔고 히스토리를 조회합니다.")
    @GetMapping("/history/{stockCode}")
    public ResponseEntity<ApiResponse<List<StockShortData>>> getStockShortHistory(
            @Parameter(description = "종목코드")
            @PathVariable String stockCode,
            @Parameter(description = "조회 기간 (일)")
            @RequestParam(required = false, defaultValue = "30") Integer days) {

        List<StockShortData> history = shortSellingService.getStockShortHistory(stockCode, days);

        if (history.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.fail("해당 종목의 공매도 데이터가 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @Operation(summary = "특정 종목 상세 분석",
               description = "특정 종목의 숏스퀴즈 분석 및 기술적 지표(MA, RSI, 골든크로스 등)를 조회합니다.")
    @GetMapping("/analysis/{stockCode}")
    public ResponseEntity<ApiResponse<ShortSqueezeDto>> getStockDetailedAnalysis(
            @Parameter(description = "종목코드")
            @PathVariable String stockCode) {

        ShortSqueezeDto analysis = shortSellingService.getStockDetailedAnalysis(stockCode);

        if (analysis == null) {
            return ResponseEntity.ok(ApiResponse.fail("해당 종목의 분석 데이터가 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(analysis));
    }

    // ========== 데이터 수집 API ==========

    @Operation(summary = "공매도/대차잔고 데이터 수집",
               description = "특정 날짜의 공매도 및 대차잔고 데이터를 수집합니다.")
    @PostMapping("/collect")
    public ResponseEntity<ApiResponse<Map<String, Object>>> collectData(
            @Parameter(description = "수집할 날짜 (미지정시 오늘)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate) {

        if (tradeDate == null) {
            tradeDate = LocalDate.now();
        }

        Map<String, Object> result = shortSellingDataCollector.collectManually(tradeDate);

        if ((boolean) result.get("success")) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.ok(ApiResponse.fail((String) result.get("message")));
        }
    }

    @Operation(summary = "과거 데이터 일괄 수집 (백필)",
               description = "지정된 기간의 공매도/대차잔고 데이터를 일괄 수집합니다.")
    @PostMapping("/collect/historical")
    public ResponseEntity<ApiResponse<Map<String, Object>>> collectHistoricalData(
            @Parameter(description = "시작 날짜", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "종료 날짜", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        if (startDate.isAfter(endDate)) {
            return ResponseEntity.ok(ApiResponse.fail("시작 날짜가 종료 날짜보다 뒤일 수 없습니다."));
        }

        int collected = shortSellingDataCollector.collectHistoricalData(startDate, endDate);

        Map<String, Object> result = Map.of(
                "collectedCount", collected,
                "startDate", startDate.toString(),
                "endDate", endDate.toString(),
                "message", String.format("%s ~ %s 기간 %d건 수집 완료", startDate, endDate, collected)
        );

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(summary = "최근 데이터 업데이트 날짜",
               description = "가장 최근에 수집된 데이터의 날짜를 조회합니다.")
    @GetMapping("/latest-date")
    public ResponseEntity<ApiResponse<LocalDate>> getLatestDataDate() {
        LocalDate latestDate = shortSellingService.getLatestTradeDate();

        if (latestDate == null) {
            return ResponseEntity.ok(ApiResponse.fail("수집된 데이터가 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(latestDate));
    }

    // ========== 네이버 금융 크롤링 API ==========

    @Operation(summary = "네이버 공매도 데이터 크롤링",
               description = "네이버 금융에서 특정 종목의 공매도 거래 현황을 크롤링합니다.")
    @GetMapping("/naver/short/{stockCode}")
    public ResponseEntity<ApiResponse<List<NaverFinanceCrawler.ShortSellingData>>> getNaverShortData(
            @Parameter(description = "종목코드")
            @PathVariable String stockCode,
            @Parameter(description = "조회 기간 (일)")
            @RequestParam(required = false, defaultValue = "30") Integer days) {

        List<NaverFinanceCrawler.ShortSellingData> data = naverFinanceCrawler.crawlShortSellingData(stockCode, days);

        if (data.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.fail("공매도 데이터를 조회할 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @Operation(summary = "네이버 대차잔고 데이터 크롤링",
               description = "네이버 금융에서 특정 종목의 대차거래 현황을 크롤링합니다.")
    @GetMapping("/naver/loan/{stockCode}")
    public ResponseEntity<ApiResponse<List<NaverFinanceCrawler.LoanBalanceData>>> getNaverLoanData(
            @Parameter(description = "종목코드")
            @PathVariable String stockCode,
            @Parameter(description = "조회 기간 (일)")
            @RequestParam(required = false, defaultValue = "30") Integer days) {

        List<NaverFinanceCrawler.LoanBalanceData> data = naverFinanceCrawler.crawlLoanBalanceData(stockCode, days);

        if (data.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.fail("대차잔고 데이터를 조회할 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @Operation(summary = "네이버 공매도/대차잔고 통합 데이터",
               description = "네이버 금융에서 공매도와 대차잔고 데이터를 통합하여 조회합니다.")
    @GetMapping("/naver/combined/{stockCode}")
    public ResponseEntity<ApiResponse<Map<LocalDate, NaverFinanceCrawler.CombinedShortData>>> getNaverCombinedData(
            @Parameter(description = "종목코드")
            @PathVariable String stockCode,
            @Parameter(description = "조회 기간 (일)")
            @RequestParam(required = false, defaultValue = "30") Integer days) {

        Map<LocalDate, NaverFinanceCrawler.CombinedShortData> data = naverFinanceCrawler.crawlCombinedData(stockCode, days);

        if (data.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.fail("데이터를 조회할 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
