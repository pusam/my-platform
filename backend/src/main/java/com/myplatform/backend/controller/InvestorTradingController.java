package com.myplatform.backend.controller;

import com.myplatform.backend.dto.InvestorTradingDto;
import com.myplatform.backend.service.InvestorTradingService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@Tag(name = "투자자 매매동향", description = "외국인/기관/프로그램 순매수 추적 API")
@RestController
@RequestMapping("/api/investor")
@SecurityRequirement(name = "JWT Bearer")
public class InvestorTradingController {

    private final InvestorTradingService investorTradingService;

    // 기본 관심 종목 (대형주 위주)
    private static final List<String> DEFAULT_STOCKS = Arrays.asList(
            "005930", // 삼성전자
            "000660", // SK하이닉스
            "373220", // LG에너지솔루션
            "207940", // 삼성바이오로직스
            "006400", // 삼성SDI
            "005380", // 현대차
            "035420", // NAVER
            "000270", // 기아
            "068270", // 셀트리온
            "012330"  // 현대모비스
    );

    public InvestorTradingController(InvestorTradingService investorTradingService) {
        this.investorTradingService = investorTradingService;
    }

    @Operation(summary = "종목 투자자 동향 조회", description = "특정 종목의 외국인/기관/프로그램 순매수를 조회합니다.")
    @GetMapping("/{stockCode}")
    public ResponseEntity<ApiResponse<InvestorTradingDto>> getInvestorTrading(
            @Parameter(description = "종목코드 (예: 005930)")
            @PathVariable String stockCode) {
        try {
            InvestorTradingDto result = investorTradingService.getInvestorTrading(stockCode);
            if (result == null) {
                return ResponseEntity.ok(ApiResponse.fail("종목 정보를 찾을 수 없습니다."));
            }
            return ResponseEntity.ok(ApiResponse.success("투자자 동향 조회 성공", result));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "주요 종목 수급 현황", description = "주요 종목들의 외국인/기관 순매수 현황을 조회합니다.")
    @GetMapping("/top")
    public ResponseEntity<ApiResponse<List<InvestorTradingDto>>> getTopStocksTrading() {
        try {
            List<InvestorTradingDto> results = investorTradingService.getMultipleStocksTrading(DEFAULT_STOCKS);
            return ResponseEntity.ok(ApiResponse.success("수급 현황 조회 성공", results));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "관심 종목 수급 조회", description = "지정한 종목들의 수급 현황을 조회합니다.")
    @PostMapping("/watchlist")
    public ResponseEntity<ApiResponse<List<InvestorTradingDto>>> getWatchlistTrading(
            @Parameter(description = "종목코드 목록")
            @RequestBody List<String> stockCodes) {
        try {
            if (stockCodes == null || stockCodes.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.fail("종목 코드를 입력해주세요."));
            }
            if (stockCodes.size() > 20) {
                return ResponseEntity.ok(ApiResponse.fail("최대 20개 종목까지 조회 가능합니다."));
            }
            List<InvestorTradingDto> results = investorTradingService.getMultipleStocksTrading(stockCodes);
            return ResponseEntity.ok(ApiResponse.success("관심 종목 수급 조회 성공", results));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "시계열 캐시 초기화", description = "특정 종목의 시계열 데이터를 초기화합니다.")
    @DeleteMapping("/{stockCode}/cache")
    public ResponseEntity<ApiResponse<Void>> clearCache(
            @PathVariable String stockCode) {
        investorTradingService.clearTimeSeriesCache(stockCode);
        return ResponseEntity.ok(ApiResponse.success("캐시 초기화 완료", null));
    }
}
