package com.myplatform.backend.controller;

import com.myplatform.backend.dto.MarketIndicatorStockDto;
import com.myplatform.backend.service.MarketIndicatorService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "시장 지표", description = "주식 시장 지표 및 순위 API")
@RestController
@RequestMapping("/api/market")
@SecurityRequirement(name = "JWT Bearer")
public class MarketIndicatorController {

    private final MarketIndicatorService marketIndicatorService;

    public MarketIndicatorController(MarketIndicatorService marketIndicatorService) {
        this.marketIndicatorService = marketIndicatorService;
    }

    @Operation(
        summary = "급등주 (등락률 상위)",
        description = "당일 등락률 기준 가장 많이 오른 종목 TOP 50"
    )
    @GetMapping("/price-rise")
    public ResponseEntity<ApiResponse<List<MarketIndicatorStockDto>>> getPriceRise() {
        List<MarketIndicatorStockDto> data = marketIndicatorService.getPriceRiseTopStocks();
        if (data.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.fail("데이터를 가져올 수 없습니다. API 키를 확인하세요."));
        }
        return ResponseEntity.ok(ApiResponse.success("급등주 조회 성공", data));
    }

    @Operation(
        summary = "급락주 (등락률 하위)",
        description = "당일 등락률 기준 가장 많이 떨어진 종목 TOP 50"
    )
    @GetMapping("/price-fall")
    public ResponseEntity<ApiResponse<List<MarketIndicatorStockDto>>> getPriceFall() {
        List<MarketIndicatorStockDto> data = marketIndicatorService.getPriceFallTopStocks();
        if (data.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.fail("데이터를 가져올 수 없습니다. API 키를 확인하세요."));
        }
        return ResponseEntity.ok(ApiResponse.success("급락주 조회 성공", data));
    }
}
