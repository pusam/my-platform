package com.myplatform.backend.controller;

import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.service.StockPriceService;
import com.myplatform.core.dto.ApiResponse;
import com.myplatform.core.util.ApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
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

    /** 검색 키워드 길이 cap — @Cacheable 키 메모리 / 다운스트림 LIKE 부하 차단. */
    private static final int MAX_KEYWORD_LEN = 50;

    @Operation(summary = "종목 검색", description = "종목명 또는 종목코드로 검색합니다.")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<StockPriceDto>>> searchStocks(
            @Parameter(description = "검색 키워드 (종목명 또는 종목코드)")
            @RequestParam String keyword) {
        try {
            // 입력 정규화 + DoS 방어: trim → 길이 cap. @Cacheable 키 계산 전에 처리.
            String safe = keyword == null ? "" : keyword.trim();
            if (safe.length() > MAX_KEYWORD_LEN) {
                safe = safe.substring(0, MAX_KEYWORD_LEN);
            }
            List<StockPriceDto> results = stockPriceService.searchStocks(safe);
            return ResponseEntity.ok(ApiResponse.success("종목 검색 완료", results));
        } catch (Exception e) {
            return ApiResponses.error(e, "종목 검색 실패: ");
        }
    }

    @Operation(summary = "종목 시세 조회", description = "종목코드로 시세를 조회합니다.")
    @GetMapping("/{stockCode}")
    public ResponseEntity<ApiResponse<StockPriceDto>> getStockPrice(
            @Parameter(description = "종목코드 (예: 005930)")
            @PathVariable String stockCode) {
        StockPriceDto price = stockPriceService.getStockPrice(stockCode);
        if (price == null) {
            return ApiResponses.error(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다.");
        }
        return ResponseEntity.ok(ApiResponse.success("시세 조회 성공", price));
    }
}
