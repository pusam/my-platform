package com.myplatform.backend.controller;

import com.myplatform.backend.dto.BuyChecklistDto;
import com.myplatform.backend.dto.CatalystHistoryDto;
import com.myplatform.backend.dto.SignalHistoryDto;
import com.myplatform.backend.dto.StockCatalystDto;
import com.myplatform.backend.dto.StockConclusionDto;
import com.myplatform.backend.dto.StockDetailDto;
import com.myplatform.backend.service.BuyChecklistService;
import com.myplatform.backend.service.CatalystHistoryService;
import com.myplatform.backend.service.IntradayChartService;
import com.myplatform.backend.service.SignalHistoryService;
import com.myplatform.backend.service.StockCatalystService;
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
    private final BuyChecklistService buyChecklistService;
    private final StockCatalystService stockCatalystService;
    private final CatalystHistoryService catalystHistoryService;
    private final IntradayChartService intradayChartService;
    private final SignalHistoryService signalHistoryService;

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

    @GetMapping("/{stockCode}/checklist")
    @Operation(
        summary = "수동 매수 체크리스트",
        description = "자동매매 봇 hard rule(거래상태/공매도/연속매수/복합신호/종합결론)을 사용자에게 노출. " +
                     "5개 중 N개 충족에 따라 STRONG/MODERATE/CAUTION/NOT_RECOMMENDED 4단계 권고. " +
                     "프론트 모달에서 매수 버튼 클릭 직전 표시 권장."
    )
    public ResponseEntity<Map<String, Object>> getBuyChecklist(
            @Parameter(description = "종목코드 (6자리)", example = "005930")
            @PathVariable String stockCode) {

        Map<String, Object> response = new HashMap<>();
        try {
            BuyChecklistDto checklist = buyChecklistService.evaluate(stockCode);
            response.put("success", true);
            response.put("data", checklist);
            response.put("timestamp", LocalDateTime.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[BuyChecklist API] 종목 {} 체크리스트 조회 실패: {}", stockCode, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "체크리스트 조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/{stockCode}/intraday-candles")
    @Operation(
        summary = "당일 분봉 차트 (5분봉 합성)",
        description = "KIS 당일분봉(1분×페이지네이션)을 5분봉으로 합성해 반환 — 종목상세 '1일' 차트용. " +
                     "candles 는 최신→과거(일봉 ChartData 규약 동형), date 는 HH:mm. " +
                     "장전/휴장/수집 실패면 빈 배열 + dataAvailable=false(§4c — 프론트가 '분봉 없음' 안내)."
    )
    public ResponseEntity<Map<String, Object>> getIntradayCandles(
            @Parameter(description = "종목코드 (6자리)", example = "005930")
            @PathVariable String stockCode) {

        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("data", intradayChartService.getIntradayCandles(stockCode));
            response.put("timestamp", LocalDateTime.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[Intraday API] 종목 {} 분봉 조회 실패: {}", stockCode, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "분봉 조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/{stockCode}/catalyst")
    @Operation(
        summary = "종목 재료 태그 (V31)",
        description = "최근 7일 뉴스를 Gemini 로 분류한 재료 태그 (수주/실적/M&A/신사업/규제/소송/지배구조/기타/없음 + 호재/악재). " +
                     "종목·일자별 1회 분류 후 캐시. stockName 미지정 시 캐시 lookup 만 (신규 분류 안 함). " +
                     "분류 불가(뉴스/Gemini 미가용)면 data=null — 프론트는 배지 생략. 산식 미편입(표시·검증용)."
    )
    public ResponseEntity<Map<String, Object>> getCatalyst(
            @Parameter(description = "종목코드 (6자리)", example = "005930")
            @PathVariable String stockCode,
            @Parameter(description = "종목명 (뉴스 검색용)", example = "삼성전자")
            @RequestParam(required = false) String stockName) {

        Map<String, Object> response = new HashMap<>();
        try {
            StockCatalystDto catalyst = StockCatalystDto.from(
                    stockCatalystService.getCatalyst(stockCode, stockName));
            response.put("success", true);
            response.put("data", catalyst);
            response.put("timestamp", LocalDateTime.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[Catalyst API] 종목 {} 재료 조회 실패: {}", stockCode, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "재료 조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/{stockCode}/catalyst-history")
    @Operation(
        summary = "종목 재료 이력 (stock_catalyst 최근 30일)",
        description = "이 종목에 과거 어떤 재료(수주/실적/M&A/… + 호재/악재/중립)가 언제 포착됐는지 타임라인 + " +
                     "각 날짜의 일봉 등락률(가격 없으면 null). 기존 일캐시 read-only 재사용 — 신규 Gemini 분류 안 함(§4b). " +
                     "재료 없음(NONE)은 제외, 항목 0건이면 프론트 미렌더. 산식 미편입(표시·검증용)."
    )
    public ResponseEntity<Map<String, Object>> getCatalystHistory(
            @Parameter(description = "종목코드 (6자리)", example = "005930")
            @PathVariable String stockCode) {

        Map<String, Object> response = new HashMap<>();
        try {
            CatalystHistoryDto history = catalystHistoryService.getHistory(stockCode);
            response.put("success", true);
            response.put("data", history);
            response.put("timestamp", LocalDateTime.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[CatalystHistory API] 종목 {} 재료 이력 조회 실패: {}", stockCode, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "재료 이력 조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/{stockCode}/signal-history")
    @Operation(
        summary = "종목 신호 이력 실적 (signal_outcome 최근 90일)",
        description = "이 종목에 과거 시그널(STRONG_BUY/BUY 등)이 언제 떴고 3거래일 평가에서 맞았는지 " +
                     "타임라인 + 요약(총/적중/평균 alpha). 평가 전 행은 pending=true(평가 대기) 로 구분 — " +
                     "미평가를 미스로 위장하지 않음(§4c). 기존 signal_outcome 재사용, read-only(산식 미편입)."
    )
    public ResponseEntity<Map<String, Object>> getSignalHistory(
            @Parameter(description = "종목코드 (6자리)", example = "005930")
            @PathVariable String stockCode) {

        Map<String, Object> response = new HashMap<>();
        try {
            SignalHistoryDto history = signalHistoryService.getHistory(stockCode);
            response.put("success", true);
            response.put("data", history);
            response.put("timestamp", LocalDateTime.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[SignalHistory API] 종목 {} 신호 이력 조회 실패: {}", stockCode, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "신호 이력 조회에 실패했습니다.");
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
