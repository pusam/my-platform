package com.myplatform.backend.controller;

import com.myplatform.backend.dto.StockConclusionDto;
import com.myplatform.backend.dto.StockDetailDto;
import com.myplatform.backend.service.StockConclusionService;
import com.myplatform.backend.service.StockDetailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 종목 종합 상세 API Controller
 *
 * 한 번의 API 호출로 종목의 모든 정보를 통합 제공:
 * - 현재가/등락률
 * - 수급 (체결강도, 외인/기관, 프로그램)
 * - 재무 (PER, PBR, ROE 등)
 * - 리스크 (공시, 뉴스, AI 분석)
 * - AI 매매 전략
 */
@RestController
@RequestMapping("/api/stock")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "종목 종합 상세", description = "종목의 모든 정보를 한 번에 조회")
public class StockDetailController {

    private final StockDetailService stockDetailService;
    private final StockConclusionService stockConclusionService;

    @GetMapping("/{stockCode}/summary")
    @Operation(
        summary = "종목 종합 상세 조회",
        description = "한 번의 API 호출로 종목의 모든 정보를 통합 제공합니다.\n\n" +
                     "**포함 정보:**\n" +
                     "- 현재가/등락률/거래량\n" +
                     "- 수급: 체결강도, 외인/기관 순매수, 프로그램 매매\n" +
                     "- 재무: PER, PBR, EPS, BPS, 시가총액\n" +
                     "- 리스크: 위험 공시, 관련 뉴스, AI 리스크 분석\n" +
                     "- 차트: 일봉, 거래량, 이동평균, VWAP\n" +
                     "- AI 분석: 종합 점수, 매매 전략, 매수/매도 근거"
    )
    public ResponseEntity<Map<String, Object>> getStockSummary(
            @Parameter(description = "종목코드 (6자리)", example = "005930")
            @PathVariable String stockCode) {

        log.info("[StockDetail API] 종목 {} 종합 상세 조회 요청", stockCode);
        long startTime = System.currentTimeMillis();

        Map<String, Object> response = new HashMap<>();

        try {
            StockDetailDto detail = stockDetailService.getStockDetail(stockCode);

            response.put("success", true);
            response.put("data", detail);
            response.put("timestamp", LocalDateTime.now());
            response.put("elapsed", System.currentTimeMillis() - startTime + "ms");

            log.info("[StockDetail API] 종목 {} 조회 완료: {}ms", stockCode,
                    System.currentTimeMillis() - startTime);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[StockDetail API] 종목 {} 조회 실패: {}", stockCode, e.getMessage(), e);

            response.put("success", false);
            response.put("message", "종목 상세 조회에 실패했습니다. 잠시 후 다시 시도해주세요.");
            response.put("timestamp", LocalDateTime.now());

            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/{stockCode}/quick")
    @Operation(
        summary = "종목 빠른 조회 (1단계)",
        description = "시세/수급/차트/재무 데이터를 빠르게 반환합니다. 평균 3~5초."
    )
    public ResponseEntity<Map<String, Object>> getStockQuick(
            @Parameter(description = "종목코드 (6자리)", example = "005930")
            @PathVariable String stockCode) {

        log.info("[StockDetail API] 종목 {} Quick 조회 요청", stockCode);
        long startTime = System.currentTimeMillis();

        Map<String, Object> response = new HashMap<>();
        try {
            StockDetailDto detail = stockDetailService.getStockDetailQuick(stockCode);
            response.put("success", true);
            response.put("data", detail);
            response.put("timestamp", LocalDateTime.now());
            response.put("elapsed", System.currentTimeMillis() - startTime + "ms");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[StockDetail API] Quick 조회 실패: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "빠른 조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/{stockCode}/conclusion")
    @Operation(
        summary = "종목 룰 기반 결론 한 줄",
        description = "여러 시그널을 단일 룰 엔진으로 합쳐 4-level 결론(STRONG_BUY/BUY/HOLD/WAIT) + " +
                     "한 줄 헤드라인 + 근거 factor 목록을 제공. 사용자가 점수 불일치로 혼란을 겪지 않도록 " +
                     "최종 권고를 명시. 데이터 없으면 dataAvailable=false."
    )
    public ResponseEntity<Map<String, Object>> getStockConclusion(
            @Parameter(description = "종목코드 (6자리)", example = "005930")
            @PathVariable String stockCode) {

        Map<String, Object> response = new HashMap<>();
        try {
            StockConclusionDto conclusion = stockConclusionService.getConclusion(stockCode);
            response.put("success", true);
            response.put("data", conclusion);
            response.put("timestamp", LocalDateTime.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[StockConclusion API] 종목 {} 결론 조회 실패: {}", stockCode, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "결론 조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/{stockCode}/heavy")
    @Operation(
        summary = "종목 무거운 데이터 조회 (2단계)",
        description = "리스크/AI분석/피어비교 등 시간이 걸리는 데이터를 반환합니다. 캐시 적용."
    )
    public ResponseEntity<Map<String, Object>> getStockHeavy(
            @Parameter(description = "종목코드 (6자리)", example = "005930")
            @PathVariable String stockCode) {

        log.info("[StockDetail API] 종목 {} Heavy 조회 요청", stockCode);
        long startTime = System.currentTimeMillis();

        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> heavyData = stockDetailService.getStockDetailHeavy(stockCode);
            response.put("success", true);
            response.put("data", heavyData);
            response.put("timestamp", LocalDateTime.now());
            response.put("elapsed", System.currentTimeMillis() - startTime + "ms");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[StockDetail API] Heavy 조회 실패: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "상세 분석 조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
