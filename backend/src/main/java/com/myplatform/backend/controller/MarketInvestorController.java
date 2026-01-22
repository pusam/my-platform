package com.myplatform.backend.controller;

import com.myplatform.backend.dto.MarketInvestorDto;
import com.myplatform.backend.dto.MarketInvestorDto.DailyTrend;
import com.myplatform.backend.service.MarketInvestorService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "시장 투자자 매매동향", description = "코스피/코스닥 외국인/기관/개인 매매동향 API")
@RestController
@RequestMapping("/api/market-investor")
@SecurityRequirement(name = "JWT Bearer")
public class MarketInvestorController {

    private final MarketInvestorService marketInvestorService;

    public MarketInvestorController(MarketInvestorService marketInvestorService) {
        this.marketInvestorService = marketInvestorService;
    }

    @Operation(summary = "코스피 투자자 매매동향", description = "코스피 시장의 외국인/기관/개인 순매수 현황을 조회합니다.")
    @GetMapping("/kospi")
    public ResponseEntity<ApiResponse<MarketInvestorDto>> getKospiInvestorTrend() {
        try {
            MarketInvestorDto result = marketInvestorService.getKospiInvestorTrend();
            return ResponseEntity.ok(ApiResponse.success("코스피 투자자 동향 조회 성공", result));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "코스닥 투자자 매매동향", description = "코스닥 시장의 외국인/기관/개인 순매수 현황을 조회합니다.")
    @GetMapping("/kosdaq")
    public ResponseEntity<ApiResponse<MarketInvestorDto>> getKosdaqInvestorTrend() {
        try {
            MarketInvestorDto result = marketInvestorService.getKosdaqInvestorTrend();
            return ResponseEntity.ok(ApiResponse.success("코스닥 투자자 동향 조회 성공", result));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "양 시장 투자자 매매동향", description = "코스피/코스닥 양 시장의 투자자 매매동향을 한번에 조회합니다.")
    @GetMapping("/all")
    public ResponseEntity<ApiResponse<Map<String, MarketInvestorDto>>> getAllMarketInvestorTrend() {
        try {
            Map<String, MarketInvestorDto> result = new HashMap<>();
            result.put("kospi", marketInvestorService.getKospiInvestorTrend());
            result.put("kosdaq", marketInvestorService.getKosdaqInvestorTrend());
            return ResponseEntity.ok(ApiResponse.success("양 시장 투자자 동향 조회 성공", result));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "과거 데이터 조회", description = "특정 기간의 투자자 매매동향 기록을 조회합니다.")
    @GetMapping("/history/{marketType}")
    public ResponseEntity<ApiResponse<List<DailyTrend>>> getHistoricalData(
            @Parameter(description = "시장 (KOSPI, KOSDAQ)") @PathVariable String marketType,
            @Parameter(description = "시작일 (yyyy-MM-dd)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "종료일 (yyyy-MM-dd)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            List<DailyTrend> result = marketInvestorService.getHistoricalData(marketType.toUpperCase(), startDate, endDate);
            return ResponseEntity.ok(ApiResponse.success("과거 데이터 조회 성공", result));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "수동 저장", description = "현재 투자자 매매동향을 수동으로 저장합니다. (관리자용)")
    @PostMapping("/save")
    public ResponseEntity<ApiResponse<Void>> saveManually() {
        try {
            marketInvestorService.saveDailyRecords();
            return ResponseEntity.ok(ApiResponse.success("저장 완료", null));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("저장 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "캐시 초기화", description = "캐시를 초기화합니다.")
    @PostMapping("/cache/clear")
    public ResponseEntity<ApiResponse<Void>> clearCache() {
        marketInvestorService.clearCache();
        return ResponseEntity.ok(ApiResponse.success("캐시 초기화 완료", null));
    }
}
