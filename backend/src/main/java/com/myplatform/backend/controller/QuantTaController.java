package com.myplatform.backend.controller;

import com.myplatform.backend.service.QuantTaService;
import com.myplatform.backend.service.QuantTaService.ScreenerFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 기술적 분석 기반 퀀트 API
 * - /screen: 조건 조합 종목 필터링 (TA 스크리너)
 * - /correlation: 종목 상관관계 매트릭스
 *
 * AI/외부 호출 0건 — DB 캐시(stock_price_history)만 사용.
 */
@RestController
@RequestMapping("/api/quant-ta")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "기술적 분석 퀀트", description = "TA 지표 기반 스크리너 + 상관관계 (AI 호출 없음)")
public class QuantTaController {

    private final QuantTaService quantTaService;

    @PostMapping("/screen")
    @Operation(summary = "TA 스크리너", description = "RSI, 골든크로스, 거래량, 볼린저 등 조건 조합으로 종목 필터링.")
    public ResponseEntity<Map<String, Object>> screen(
            @RequestBody(required = false) ScreenRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (request == null) request = new ScreenRequest();
            if (request.getFilter() == null) request.setFilter(new ScreenerFilter());

            Map<String, Object> data = quantTaService.screen(request.getFilter(),
                    request.getLimit() != null ? request.getLimit() : 50);
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("TA 스크리너 오류", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/correlation")
    @Operation(summary = "상관관계 매트릭스", description = "지정한 종목들의 일변화율 기반 피어슨 상관계수 매트릭스.")
    public ResponseEntity<Map<String, Object>> correlation(
            @RequestBody CorrelationRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<String> codes = request != null ? request.getStockCodes() : null;
            int days = request != null && request.getDays() != null ? request.getDays() : 60;
            Map<String, Object> data = quantTaService.correlation(codes, days);
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("상관관계 계산 오류", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/universe-status")
    @Operation(summary = "데이터 universe 현황", description = "스크리너에 사용 가능한 종목 수(일봉 N일 이상 보유)를 반환합니다.")
    public ResponseEntity<Map<String, Object>> universeStatus() {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("data", quantTaService.getUniverseStatus());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("universe 현황 조회 오류", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/collect-history")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "일봉 일괄 수집 (admin)", description = "거래량 상위 N종목의 일봉을 KIS API로 비동기 수집합니다.")
    public ResponseEntity<Map<String, Object>> collectHistory(
            @RequestBody(required = false) BulkCollectRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            int topN = request != null && request.getTopN() != null ? request.getTopN() : 200;
            Map<String, Object> data = quantTaService.startBulkCollection(topN);
            response.put("success", Boolean.TRUE.equals(data.get("success")) || Boolean.TRUE.equals(data.get("started")));
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("일괄 수집 시작 오류", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/collect-history/progress")
    @Operation(summary = "일괄 수집 진행 상태", description = "현재 실행 중인 일괄 수집 작업의 진행률을 조회합니다.")
    public ResponseEntity<Map<String, Object>> collectHistoryProgress() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", quantTaService.getBulkProgress());
        return ResponseEntity.ok(response);
    }

    // ===== 요청 DTO =====

    @lombok.Data
    public static class ScreenRequest {
        private ScreenerFilter filter;
        private Integer limit;
    }

    @lombok.Data
    public static class CorrelationRequest {
        private List<String> stockCodes;
        private Integer days;
    }

    @lombok.Data
    public static class BulkCollectRequest {
        private Integer topN;
    }
}
