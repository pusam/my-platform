package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ChartPatternDto;
import com.myplatform.backend.dto.CompositeSignalDto;
import com.myplatform.backend.dto.SupportResistanceDto;
import com.myplatform.backend.dto.VolumeProfileDto;
import com.myplatform.backend.service.ChartPatternService;
import com.myplatform.backend.service.CompositeSignalService;
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
    private final ChartPatternService chartPatternService;
    private final CompositeSignalService compositeSignalService;

    @GetMapping("/scan/top-volume")
    @Operation(summary = "거래량 상위 종목 차트 패턴 자동 스캔",
            description = "watchlist 비어있을 때 fallback universe — stock_price MAX(volume) 상위 N개 자동 스캔. " +
                    "limit 5~50, 기본 30.")
    public ResponseEntity<Map<String, Object>> scanTopVolume(
            @RequestParam(defaultValue = "30") int limit) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<ChartPatternService.ScanResult> results = chartPatternService.scanTopVolumeStocks(limit);
            response.put("success", true);
            response.put("data", results);
            response.put("count", results.size());
            response.put("source", "TOP_VOLUME");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("거래량 상위 패턴 스캔 오류", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/scan/patterns")
    @Operation(summary = "다종목 차트 패턴 일괄 스캔",
            description = "여러 종목의 차트 패턴을 한 번에 검출. 종목별 가장 강한 패턴 1개씩. " +
                    "최대 50종목. 사용자 watchlist 등에서 universe 결정 후 호출.")
    public ResponseEntity<Map<String, Object>> scanPatterns(@RequestBody ScanRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<String> codes = request != null ? request.getStockCodes() : null;
            List<ChartPatternService.ScanResult> results = chartPatternService.scanForPatterns(codes);
            response.put("success", true);
            response.put("data", results);
            response.put("count", results.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("패턴 스캔 오류", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @lombok.Data
    public static class ScanRequest {
        private List<String> stockCodes;
    }

    @GetMapping("/{stockCode}/composite")
    @Operation(summary = "종합 신호 평가 (5가지)",
            description = "차트 패턴 / 지지선 / Volume Profile 저평가 / 수급 / AI 추천 — 매칭 개수 반환. " +
                    "사용자 의사결정 단순화용. 자동매매 신호로 사용 X.")
    public ResponseEntity<Map<String, Object>> compositeSignal(@PathVariable String stockCode) {
        Map<String, Object> response = new HashMap<>();
        try {
            CompositeSignalDto data = compositeSignalService.evaluate(stockCode);
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("종합 신호 평가 오류 [{}]", stockCode, e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/composite/batch")
    @Operation(summary = "종합 신호 다종목 일괄 평가",
            description = "여러 종목 5개 신호 평가 — 메인 대시보드 차트 신호 카드용. 최대 50종목.")
    public ResponseEntity<Map<String, Object>> compositeBatch(@RequestBody ScanRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<String> codes = request != null ? request.getStockCodes() : null;
            List<CompositeSignalDto> results = compositeSignalService.evaluateBatch(codes);
            response.put("success", true);
            response.put("data", results);
            response.put("count", results.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("종합 신호 batch 평가 오류", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/{stockCode}/volume-profile")
    @Operation(summary = "Volume Profile (가격대별 누적 거래량)",
            description = "일봉 90일 가격 범위 30 bin → 거래량 누적. POC / VAH / VAL 함께 반환.")
    public ResponseEntity<Map<String, Object>> volumeProfile(@PathVariable String stockCode) {
        Map<String, Object> response = new HashMap<>();
        try {
            VolumeProfileDto data = chartPatternService.computeVolumeProfile(stockCode);
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Volume Profile 오류 [{}]", stockCode, e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/{stockCode}/support-resistance")
    @Operation(summary = "지지/저항 레벨 검출",
            description = "일봉 90일 피벗을 가격대로 클러스터링하여 자주 터치된 가격대를 강한 레벨로 평가. " +
                    "현재가 기준 위쪽=저항, 아래쪽=지지. 사용자 참고용.")
    public ResponseEntity<Map<String, Object>> supportResistance(@PathVariable String stockCode) {
        Map<String, Object> response = new HashMap<>();
        try {
            SupportResistanceDto data = chartPatternService.detectSupportResistance(stockCode);
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("지지/저항 검출 오류 [{}]", stockCode, e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/{stockCode}/patterns")
    @Operation(summary = "차트 패턴 검출",
            description = "더블탑/바텀, 헤드앤숄더/역, 삼각수렴 패턴을 일봉 90일 기준으로 검출. " +
                    "사용자 참고용 인디케이터 — 자동매매 신호로 사용 금지.")
    public ResponseEntity<Map<String, Object>> patterns(@PathVariable String stockCode) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<ChartPatternDto> patterns = chartPatternService.detectPatterns(stockCode);
            response.put("success", true);
            response.put("data", patterns);
            response.put("count", patterns.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("차트 패턴 검출 오류 [{}]", stockCode, e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

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

    @PostMapping("/resolve-names")
    @Operation(summary = "종목명 해석", description = "종목 코드 리스트를 종목명으로 매핑합니다.")
    public ResponseEntity<Map<String, Object>> resolveNames(
            @RequestBody ResolveNamesRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<String> codes = request != null ? request.getStockCodes() : null;
            response.put("success", true);
            response.put("data", quantTaService.resolveNames(codes));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("종목명 해석 오류", e);
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

    @PostMapping("/backfill-names")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "종목명 일괄 보정 (admin)", description = "stockName이 비어있는 history 행을 stock_price/하드코딩 매핑으로 채워 넣습니다.")
    public ResponseEntity<Map<String, Object>> backfillNames() {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("data", quantTaService.backfillMissingNames());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("종목명 보정 오류", e);
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

    @lombok.Data
    public static class ResolveNamesRequest {
        private List<String> stockCodes;
    }
}
