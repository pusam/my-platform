package com.myplatform.backend.controller;

import com.myplatform.backend.dto.StockDetailDto;
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
            response.put("message", "종목 상세 조회 실패: " + e.getMessage());
            response.put("timestamp", LocalDateTime.now());

            return ResponseEntity.internalServerError().body(response);
        }
    }
}
