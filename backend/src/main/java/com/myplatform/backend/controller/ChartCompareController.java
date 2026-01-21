package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ChartCompareDto;
import com.myplatform.backend.service.ChartCompareService;
import com.myplatform.backend.service.RealTimeDataCache;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "차트 비교", description = "지수 vs 종목 오버레이 차트 API")
@RestController
@RequestMapping("/api/chart")
@SecurityRequirement(name = "JWT Bearer")
public class ChartCompareController {

    private final ChartCompareService chartCompareService;
    private final RealTimeDataCache dataCache;

    public ChartCompareController(ChartCompareService chartCompareService, RealTimeDataCache dataCache) {
        this.chartCompareService = chartCompareService;
        this.dataCache = dataCache;
    }

    @Operation(summary = "지수 vs 종목 비교 차트",
            description = "코스닥/코스피 지수와 종목의 시초가 대비 등락률을 비교합니다. " +
                    "지수가 빠지는데 종목이 버티면 '개쎈 놈', 지수는 오르는데 종목만 기어가면 '버려야 할 종목'")
    @GetMapping("/compare/{stockCode}")
    public ResponseEntity<ApiResponse<ChartCompareDto>> getCompareChart(
            @Parameter(description = "종목코드 (예: 005930)")
            @PathVariable String stockCode,
            @Parameter(description = "지수코드 (0001: 코스피, 1001: 코스닥). 기본값: 코스닥")
            @RequestParam(defaultValue = "1001") String indexCode) {
        try {
            ChartCompareDto result = chartCompareService.getCompareChart(stockCode, indexCode);
            return ResponseEntity.ok(ApiResponse.success("차트 비교 조회 성공", result));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("차트 비교 조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "코스피 지수 비교", description = "코스피 지수와 종목을 비교합니다.")
    @GetMapping("/compare/{stockCode}/kospi")
    public ResponseEntity<ApiResponse<ChartCompareDto>> getKospiCompare(
            @PathVariable String stockCode) {
        return getCompareChart(stockCode, ChartCompareService.KOSPI);
    }

    @Operation(summary = "코스닥 지수 비교", description = "코스닥 지수와 종목을 비교합니다.")
    @GetMapping("/compare/{stockCode}/kosdaq")
    public ResponseEntity<ApiResponse<ChartCompareDto>> getKosdaqCompare(
            @PathVariable String stockCode) {
        return getCompareChart(stockCode, ChartCompareService.KOSDAQ);
    }

    @Operation(summary = "실시간 데이터 캐시 상태", description = "실시간 데이터 캐시 상태를 조회합니다.")
    @GetMapping("/cache/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCacheStatus() {
        return ResponseEntity.ok(ApiResponse.success("캐시 상태 조회", dataCache.getStatus()));
    }

    @Operation(summary = "캐시 초기화", description = "실시간 데이터 캐시를 초기화합니다. (장 시작 시 사용)")
    @PostMapping("/cache/clear")
    public ResponseEntity<ApiResponse<Void>> clearCache() {
        dataCache.clearAll();
        return ResponseEntity.ok(ApiResponse.success("캐시 초기화 완료", null));
    }
}
