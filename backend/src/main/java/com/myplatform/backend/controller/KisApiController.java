package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ContinuousBuyStockDto;
import com.myplatform.backend.dto.InvestorTrendDto;
import com.myplatform.backend.dto.SupplySurgeStockDto;
import com.myplatform.backend.service.KisApiService;
import com.myplatform.core.dto.ApiResponse;
import com.myplatform.core.util.ApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "KIS API", description = "한국투자증권 시장 데이터 API")
@RestController
@RequestMapping("/api/kis")
@SecurityRequirement(name = "JWT Bearer")
public class KisApiController {

    private final KisApiService kisApiService;

    public KisApiController(KisApiService kisApiService) {
        this.kisApiService = kisApiService;
    }

    // 빈 결과는 정상 케이스(장 시작 전·주말 등)라 200 + 빈 배열 — 키 미설정만 503.
    // 이전엔 빈 결과 전부를 503 "API 키" 오류로 돌려줘 프론트가 정상 상황을 장애로 표시했다(§4c).

    @Operation(summary = "투자자 매매동향", description = "외국인, 기관 순매수 상위 종목 조회")
    @GetMapping("/investor-trend")
    public ResponseEntity<ApiResponse<List<InvestorTrendDto>>> getInvestorTrend() {
        if (!kisApiService.isConfigured()) {
            return ApiResponses.error(HttpStatus.SERVICE_UNAVAILABLE, "데이터를 가져올 수 없습니다. API 키를 확인하세요.");
        }
        return ResponseEntity.ok(ApiResponse.success("투자자 매매동향 조회 성공", kisApiService.getInvestorTrend()));
    }

    @Operation(summary = "연속 매수 종목", description="N일 연속 매수 상위 종목 조회")
    @GetMapping("/continuous-buy")
    public ResponseEntity<ApiResponse<List<ContinuousBuyStockDto>>> getContinuousBuyStocks() {
        if (!kisApiService.isConfigured()) {
            return ApiResponses.error(HttpStatus.SERVICE_UNAVAILABLE, "데이터를 가져올 수 없습니다. API 키를 확인하세요.");
        }
        return ResponseEntity.ok(ApiResponse.success("연속 매수 종목 조회 성공", kisApiService.getContinuousBuyStocks()));
    }

    @Operation(summary = "수급 급등 종목", description = "거래량 급증 종목 조회")
    @GetMapping("/supply-surge")
    public ResponseEntity<ApiResponse<List<SupplySurgeStockDto>>> getSupplySurgeStocks() {
        if (!kisApiService.isConfigured()) {
            return ApiResponses.error(HttpStatus.SERVICE_UNAVAILABLE, "데이터를 가져올 수 없습니다. API 키를 확인하세요.");
        }
        return ResponseEntity.ok(ApiResponse.success("수급 급등 종목 조회 성공", kisApiService.getSupplySurgeStocks()));
    }
}

