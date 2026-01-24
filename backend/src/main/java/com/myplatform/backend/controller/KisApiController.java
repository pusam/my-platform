package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ContinuousBuyStockDto;
import com.myplatform.backend.dto.InvestorTrendDto;
import com.myplatform.backend.dto.SupplySurgeStockDto;
import com.myplatform.backend.service.KisApiService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    @Operation(summary = "투자자 매매동향", description = "외국인, 기관 순매수 상위 종목 조회")
    @GetMapping("/investor-trend")
    public ResponseEntity<ApiResponse<List<InvestorTrendDto>>> getInvestorTrend() {
        List<InvestorTrendDto> data = kisApiService.getInvestorTrend();
        if (data.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.fail("데이터를 가져올 수 없습니다. API 키를 확인하세요."));
        }
        return ResponseEntity.ok(ApiResponse.success("투자자 매매동향 조회 성공", data));
    }

    @Operation(summary = "연속 매수 종목", description="N일 연속 매수 상위 종목 조회")
    @GetMapping("/continuous-buy")
    public ResponseEntity<ApiResponse<List<ContinuousBuyStockDto>>> getContinuousBuyStocks() {
        List<ContinuousBuyStockDto> data = kisApiService.getContinuousBuyStocks();
        if (data.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.fail("데이터를 가져올 수 없습니다. API 키를 확인하세요."));
        }
        return ResponseEntity.ok(ApiResponse.success("연속 매수 종목 조회 성공", data));
    }

    @Operation(summary = "수급 급등 종목", description = "거래량 급증 종목 조회")
    @GetMapping("/supply-surge")
    public ResponseEntity<ApiResponse<List<SupplySurgeStockDto>>> getSupplySurgeStocks() {
        List<SupplySurgeStockDto> data = kisApiService.getSupplySurgeStocks();
        if (data.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.fail("데이터를 가져올 수 없습니다. API 키를 확인하세요."));
        }
        return ResponseEntity.ok(ApiResponse.success("수급 급등 종목 조회 성공", data));
    }
}

