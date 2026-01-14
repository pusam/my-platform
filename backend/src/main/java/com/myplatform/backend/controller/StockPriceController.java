package com.myplatform.backend.controller;

import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.service.StockPriceService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "주식 시세", description = "KRX 주식 시세 조회 API")
@RestController
@RequestMapping("/api/stock")
@SecurityRequirement(name = "JWT Bearer")
public class StockPriceController {

    private final StockPriceService stockPriceService;

    public StockPriceController(StockPriceService stockPriceService) {
        this.stockPriceService = stockPriceService;
    }

    @Operation(summary = "종목 검색", description = "종목명 또는 종목코드로 검색합니다.")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<StockPriceDto>>> searchStocks(
            @Parameter(description = "검색 키워드 (종목명 또는 종목코드)")
            @RequestParam String keyword) {
        try {
            List<StockPriceDto> results = stockPriceService.searchStocks(keyword);
            return ResponseEntity.ok(ApiResponse.success("종목 검색 완료", results));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("종목 검색 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "종목 시세 조회", description = "종목코드로 시세를 조회합니다.")
    @GetMapping("/{stockCode}")
    public ResponseEntity<ApiResponse<StockPriceDto>> getStockPrice(
            @Parameter(description = "종목코드 (예: 005930)")
            @PathVariable String stockCode) {
        StockPriceDto price = stockPriceService.getStockPrice(stockCode);
        if (price == null) {
            return ResponseEntity.ok(ApiResponse.fail("종목을 찾을 수 없습니다."));
        }
        return ResponseEntity.ok(ApiResponse.success("시세 조회 성공", price));
    }
}
