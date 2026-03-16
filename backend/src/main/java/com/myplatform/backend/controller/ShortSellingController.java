package com.myplatform.backend.controller;

import com.myplatform.backend.entity.ShortSellingBalance;
import com.myplatform.backend.service.ShortSellingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 공매도 잔고 API 컨트롤러
 */
@RestController
@RequestMapping("/api/short-selling")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "공매도 잔고", description = "공매도 잔고 조회 API")
public class ShortSellingController {

    private final ShortSellingService shortSellingService;

    /**
     * 공매도 비율 상위 종목 조회
     */
    @GetMapping("/top")
    @Operation(summary = "공매도 비율 상위 종목", description = "최근 거래일 기준 공매도 비율 상위 종목을 조회합니다.")
    public ResponseEntity<Map<String, Object>> getTopShortSelling(
            @Parameter(description = "조회 개수 (기본 20)")
            @RequestParam(defaultValue = "20") int limit) {

        Map<String, Object> response = new HashMap<>();
        try {
            List<ShortSellingBalance> topStocks = shortSellingService.getTopShortSellingStocks(limit);

            response.put("success", true);
            response.put("data", topStocks);
            response.put("message", String.format("공매도 상위 %d종목 조회 완료", topStocks.size()));

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("공매도 상위 종목 조회 실패: {}", e.getMessage());
            response.put("success", false);
            response.put("message", "조회 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 종목 공매도 이력 조회
     */
    @GetMapping("/{stockCode}")
    @Operation(summary = "종목별 공매도 이력", description = "특정 종목의 공매도 잔고 이력을 조회합니다.")
    public ResponseEntity<Map<String, Object>> getStockShortSelling(
            @Parameter(description = "종목코드 (예: 005930)")
            @PathVariable String stockCode) {

        Map<String, Object> response = new HashMap<>();
        try {
            List<ShortSellingBalance> history = shortSellingService.getStockShortSellingHistory(stockCode);

            response.put("success", true);
            response.put("data", history);
            response.put("message", String.format("%s 공매도 이력 %d건 조회 완료", stockCode, history.size()));

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("종목 공매도 이력 조회 실패 - {}: {}", stockCode, e.getMessage());
            response.put("success", false);
            response.put("message", "조회 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 공매도 잔고 수동 수집 트리거
     */
    @PostMapping("/collect")
    @Operation(summary = "공매도 잔고 수집", description = "네이버 금융에서 공매도 잔고 데이터를 수집합니다.")
    public ResponseEntity<Map<String, Object>> collectShortSelling() {

        Map<String, Object> response = new HashMap<>();
        try {
            shortSellingService.collectShortSellingData();

            response.put("success", true);
            response.put("message", "공매도 잔고 수집 완료");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("공매도 잔고 수집 실패: {}", e.getMessage());
            response.put("success", false);
            response.put("message", "수집 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
