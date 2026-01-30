package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.service.AsyncCrawlerService;
import com.myplatform.backend.service.FinancialDataCrawlerService;
import com.myplatform.backend.service.GeminiService;
import com.myplatform.backend.service.QuantScreenerService;
import com.myplatform.backend.service.StockFinancialDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 퀀트 스크리너 API 컨트롤러
 * - 마법의 공식 (Magic Formula)
 * - PEG 스크리너
 * - 턴어라운드 스크리너
 */
@RestController
@RequestMapping("/api/screener")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "퀀트 스크리너", description = "실적 기반 저평가 종목 스크리닝 API")
public class QuantScreenerController {

    private final QuantScreenerService quantScreenerService;
    private final GeminiService geminiService;
    private final StockFinancialDataService stockFinancialDataService;
    private final FinancialDataCrawlerService financialDataCrawlerService;
    private final AsyncCrawlerService asyncCrawlerService;

    /**
     * 마법의 공식 스크리너
     * - (영업이익률 순위 + ROE 순위 + PER 순위) 합산으로 저평가 우량주 발굴
     */
    @GetMapping("/magic-formula")
    @Operation(summary = "마법의 공식 스크리너", description = "영업이익률, ROE, PER 순위를 합산하여 저평가 우량주를 스크리닝합니다.")
    public ResponseEntity<Map<String, Object>> getMagicFormulaStocks(
            @Parameter(description = "조회 개수 (기본: 30)") @RequestParam(defaultValue = "30") Integer limit,
            @Parameter(description = "최소 시가총액 (억원)") @RequestParam(required = false) BigDecimal minMarketCap) {

        log.info("마법의 공식 스크리너 API 호출 - limit: {}, minMarketCap: {}", limit, minMarketCap);

        Map<String, Object> response = new HashMap<>();
        try {
            List<ScreenerResultDto> results = quantScreenerService.getMagicFormulaStocks(limit, minMarketCap);
            response.put("success", true);
            response.put("data", results);
            response.put("count", results.size());
            response.put("message", "마법의 공식 스크리닝 완료");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("마법의 공식 스크리너 오류", e);
            response.put("success", false);
            response.put("message", "스크리닝 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * PEG 스크리너
     * - PEG = PER / EPS성장률
     * - PEG < 1.0인 저평가 성장주 발굴
     */
    @GetMapping("/peg")
    @Operation(summary = "PEG 스크리너", description = "PEG (PER/EPS성장률) 기준으로 저평가 성장주를 스크리닝합니다.")
    public ResponseEntity<Map<String, Object>> getLowPegStocks(
            @Parameter(description = "최대 PEG (기본: 1.0)") @RequestParam(required = false) BigDecimal maxPeg,
            @Parameter(description = "최소 EPS 성장률 % (기본: 10)") @RequestParam(required = false) BigDecimal minEpsGrowth,
            @Parameter(description = "조회 개수 (기본: 30)") @RequestParam(defaultValue = "30") Integer limit) {

        log.info("PEG 스크리너 API 호출 - maxPeg: {}, minEpsGrowth: {}, limit: {}", maxPeg, minEpsGrowth, limit);

        Map<String, Object> response = new HashMap<>();
        try {
            List<ScreenerResultDto> results = quantScreenerService.getLowPegStocks(maxPeg, minEpsGrowth, limit);
            response.put("success", true);
            response.put("data", results);
            response.put("count", results.size());
            response.put("message", "PEG 스크리닝 완료");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("PEG 스크리너 오류", e);
            response.put("success", false);
            response.put("message", "스크리닝 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 턴어라운드 스크리너
     * - 적자 → 흑자 전환 종목
     * - 순이익 급증 종목
     */
    @GetMapping("/turnaround")
    @Operation(summary = "턴어라운드 스크리너", description = "적자에서 흑자로 전환된 종목 또는 순이익이 급증한 종목을 스크리닝합니다.")
    public ResponseEntity<Map<String, Object>> getTurnaroundStocks(
            @Parameter(description = "조회 개수 (기본: 30)") @RequestParam(defaultValue = "30") Integer limit) {

        log.info("턴어라운드 스크리너 API 호출 - limit: {}", limit);

        Map<String, Object> response = new HashMap<>();
        try {
            List<ScreenerResultDto> results = quantScreenerService.getTurnaroundStocks(limit);
            response.put("success", true);
            response.put("data", results);
            response.put("count", results.size());
            response.put("message", "턴어라운드 스크리닝 완료");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("턴어라운드 스크리너 오류", e);
            response.put("success", false);
            response.put("message", "스크리닝 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 스크리너 요약
     * - 각 스크리너의 상위 종목 요약 정보
     */
    @GetMapping("/summary")
    @Operation(summary = "스크리너 요약", description = "모든 스크리너의 상위 종목 요약 정보를 조회합니다.")
    public ResponseEntity<Map<String, Object>> getScreenerSummary() {
        log.info("스크리너 요약 API 호출");

        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> summary = quantScreenerService.getScreenerSummary();
            response.put("success", true);
            response.put("data", summary);
            response.put("message", "스크리너 요약 조회 완료");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("스크리너 요약 조회 오류", e);
            response.put("success", false);
            response.put("message", "요약 조회 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 마법의 공식 AI 분석
     */
    @GetMapping("/magic-formula/ai-analysis")
    @Operation(summary = "마법의 공식 AI 분석", description = "Gemini AI가 마법의 공식 스크리닝 결과를 분석하여 추천합니다.")
    public ResponseEntity<Map<String, Object>> analyzeMagicFormula(
            @Parameter(description = "조회 개수 (기본: 10)") @RequestParam(defaultValue = "10") Integer limit,
            @Parameter(description = "최소 시가총액 (억원)") @RequestParam(required = false) BigDecimal minMarketCap) {

        log.info("마법의 공식 AI 분석 API 호출");

        Map<String, Object> response = new HashMap<>();
        try {
            if (!geminiService.isAvailable()) {
                response.put("success", false);
                response.put("message", "Gemini API 키가 설정되지 않았습니다.");
                return ResponseEntity.ok(response);
            }

            List<ScreenerResultDto> stocks = quantScreenerService.getMagicFormulaStocks(limit, minMarketCap);
            String analysis = geminiService.analyzeMagicFormula(stocks);

            response.put("success", true);
            response.put("analysis", analysis);
            response.put("analyzedCount", stocks.size());
            response.put("message", "AI 분석 완료");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("마법의 공식 AI 분석 오류", e);
            response.put("success", false);
            response.put("message", "AI 분석 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * PEG 스크리너 AI 분석
     */
    @GetMapping("/peg/ai-analysis")
    @Operation(summary = "PEG 스크리너 AI 분석", description = "Gemini AI가 PEG 스크리닝 결과를 분석하여 추천합니다.")
    public ResponseEntity<Map<String, Object>> analyzePegStocks(
            @Parameter(description = "최대 PEG (기본: 1.0)") @RequestParam(required = false) BigDecimal maxPeg,
            @Parameter(description = "최소 EPS 성장률 % (기본: 10)") @RequestParam(required = false) BigDecimal minEpsGrowth,
            @Parameter(description = "조회 개수 (기본: 10)") @RequestParam(defaultValue = "10") Integer limit) {

        log.info("PEG 스크리너 AI 분석 API 호출");

        Map<String, Object> response = new HashMap<>();
        try {
            if (!geminiService.isAvailable()) {
                response.put("success", false);
                response.put("message", "Gemini API 키가 설정되지 않았습니다.");
                return ResponseEntity.ok(response);
            }

            List<ScreenerResultDto> stocks = quantScreenerService.getLowPegStocks(maxPeg, minEpsGrowth, limit);
            String analysis = geminiService.analyzePegStocks(stocks);

            response.put("success", true);
            response.put("analysis", analysis);
            response.put("analyzedCount", stocks.size());
            response.put("message", "AI 분석 완료");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("PEG 스크리너 AI 분석 오류", e);
            response.put("success", false);
            response.put("message", "AI 분석 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 턴어라운드 스크리너 AI 분석
     */
    @GetMapping("/turnaround/ai-analysis")
    @Operation(summary = "턴어라운드 스크리너 AI 분석", description = "Gemini AI가 턴어라운드 스크리닝 결과를 분석하여 추천합니다.")
    public ResponseEntity<Map<String, Object>> analyzeTurnaroundStocks(
            @Parameter(description = "조회 개수 (기본: 10)") @RequestParam(defaultValue = "10") Integer limit) {

        log.info("턴어라운드 스크리너 AI 분석 API 호출");

        Map<String, Object> response = new HashMap<>();
        try {
            if (!geminiService.isAvailable()) {
                response.put("success", false);
                response.put("message", "Gemini API 키가 설정되지 않았습니다.");
                return ResponseEntity.ok(response);
            }

            List<ScreenerResultDto> stocks = quantScreenerService.getTurnaroundStocks(limit);
            String analysis = geminiService.analyzeTurnaroundStocks(stocks);

            response.put("success", true);
            response.put("analysis", analysis);
            response.put("analyzedCount", stocks.size());
            response.put("message", "AI 분석 완료");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("턴어라운드 스크리너 AI 분석 오류", e);
            response.put("success", false);
            response.put("message", "AI 분석 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * AI 기능 활성화 상태 확인
     */
    @GetMapping("/ai-status")
    @Operation(summary = "AI 기능 상태 확인", description = "Gemini AI 기능 활성화 상태를 확인합니다.")
    public ResponseEntity<Map<String, Object>> getAiStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("aiEnabled", geminiService.isAvailable());
        response.put("message", geminiService.isAvailable() ? "AI 기능 활성화됨" : "AI 기능 비활성화 (API 키 미설정)");
        return ResponseEntity.ok(response);
    }

    // ========== 재무 데이터 수집 API ==========

    /**
     * 재무 데이터 수동 수집
     */
    @PostMapping("/collect")
    @Operation(summary = "재무 데이터 수집", description = "외국인/기관 순매수 상위 종목의 재무 데이터를 수집합니다.")
    public ResponseEntity<Map<String, Object>> collectFinancialData() {
        log.info("재무 데이터 수동 수집 API 호출");

        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Integer> result = stockFinancialDataService.collectManually();
            response.put("success", true);
            response.put("data", result);
            response.put("message", "재무 데이터 수집 완료");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("재무 데이터 수집 오류", e);
            response.put("success", false);
            response.put("message", "수집 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 종목 재무 데이터 수집
     */
    @PostMapping("/collect/{stockCode}")
    @Operation(summary = "단일 종목 재무 데이터 수집", description = "특정 종목의 재무 데이터를 수집합니다.")
    public ResponseEntity<Map<String, Object>> collectSingleStock(
            @PathVariable String stockCode) {
        log.info("단일 종목 재무 데이터 수집 API 호출: {}", stockCode);

        Map<String, Object> response = new HashMap<>();
        try {
            boolean result = stockFinancialDataService.collectSingleStock(stockCode);
            response.put("success", result);
            response.put("stockCode", stockCode);
            response.put("message", result ? "재무 데이터 수집 완료" : "재무 데이터 수집 실패");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("단일 종목 재무 데이터 수집 오류: {}", stockCode, e);
            response.put("success", false);
            response.put("message", "수집 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 재무 데이터 삭제 후 재수집
     */
    @PostMapping("/recollect")
    @Operation(summary = "재무 데이터 재수집", description = "기존 데이터를 삭제하고 새로 수집합니다.")
    public ResponseEntity<Map<String, Object>> recollectFinancialData() {
        log.info("재무 데이터 재수집 API 호출");

        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> result = stockFinancialDataService.deleteAndRecollect();
            response.put("success", true);
            response.put("data", result);
            response.put("message", "재무 데이터 재수집 완료");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("재무 데이터 재수집 오류", e);
            response.put("success", false);
            response.put("message", "재수집 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 전 종목 재무 데이터 수집
     * - StockShortData에 있는 모든 종목의 재무 데이터를 수집
     * - 2000개 이상의 종목 대상, Rate Limit 고려
     * - 소요시간: 약 10-15분 예상
     */
    @PostMapping("/collect-all")
    @Operation(summary = "전 종목 재무 데이터 수집",
               description = "StockShortData 테이블에 있는 모든 종목의 재무 데이터를 수집합니다. " +
                           "KIS API Rate Limit 고려하여 종목당 300ms 대기합니다. " +
                           "약 2000개 종목 기준 10-15분 소요됩니다.")
    public ResponseEntity<Map<String, Object>> collectAllStocksFinancialData() {
        log.info("전 종목 재무 데이터 수집 API 호출");

        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> result = stockFinancialDataService.collectAllStocksFinancialData();
            response.put("success", true);
            response.put("data", result);
            response.put("message", "전 종목 재무 데이터 수집 완료");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("전 종목 재무 데이터 수집 오류", e);
            response.put("success", false);
            response.put("message", "수집 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 재무 데이터 수집 상태 조회
     * - 총 건수, 마지막 업데이트 시간, 영업이익률 현황
     */
    @GetMapping("/collect-status")
    @Operation(summary = "재무 데이터 수집 상태", description = "현재 수집된 재무 데이터 현황을 조회합니다.")
    public ResponseEntity<Map<String, Object>> getCollectStatus() {
        Map<String, Object> response = new HashMap<>();
        try {
            long totalCount = stockFinancialDataService.getDataCount();
            var lastUpdatedAt = stockFinancialDataService.getLastUpdatedAt();
            long withOperatingMargin = financialDataCrawlerService.countWithOperatingMargin();
            long missingOperatingMargin = financialDataCrawlerService.countMissingOperatingMargin();

            response.put("success", true);
            response.put("totalRecords", totalCount);
            response.put("lastUpdatedAt", lastUpdatedAt);
            response.put("withOperatingMargin", withOperatingMargin);
            response.put("missingOperatingMargin", missingOperatingMargin);
            response.put("message", totalCount > 0
                    ? String.format("재무 데이터 %d건 (영업이익률: %d건, 미수집: %d건)",
                            totalCount, withOperatingMargin, missingOperatingMargin)
                    : "수집된 재무 데이터가 없습니다. POST /api/screener/collect-all을 호출하세요.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("재무 데이터 상태 조회 오류", e);
            response.put("success", false);
            response.put("message", "상태 조회 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ========== 영업이익률 크롤링 API ==========

    /**
     * 전 종목 영업이익률 크롤링
     * - 네이버 금융에서 영업이익률, 순이익률, ROE, 부채비율 크롤링
     * - 2000개 종목 기준 약 15-20분 소요
     */
    @PostMapping("/crawl-operating-margin")
    @Operation(summary = "영업이익률 크롤링",
               description = "네이버 금융에서 영업이익률 등 재무 지표를 크롤링합니다. " +
                           "Rate Limit 고려하여 종목당 500ms 대기합니다. " +
                           "약 2000개 종목 기준 15-20분 소요됩니다.")
    public ResponseEntity<Map<String, Object>> crawlOperatingMargin(
            @Parameter(description = "기존 데이터 강제 업데이트 여부 (기본: false)")
            @RequestParam(defaultValue = "false") boolean forceUpdate) {

        log.info("영업이익률 크롤링 API 호출 - forceUpdate: {}", forceUpdate);

        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> result = financialDataCrawlerService.crawlAllOperatingMargin(forceUpdate);
            response.put("success", true);
            response.put("data", result);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("영업이익률 크롤링 오류", e);
            response.put("success", false);
            response.put("message", "크롤링 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 단일 종목 영업이익률 크롤링
     */
    @PostMapping("/crawl-operating-margin/{stockCode}")
    @Operation(summary = "단일 종목 영업이익률 크롤링",
               description = "특정 종목의 영업이익률을 네이버 금융에서 크롤링합니다.")
    public ResponseEntity<Map<String, Object>> crawlSingleOperatingMargin(
            @PathVariable String stockCode) {

        log.info("단일 종목 영업이익률 크롤링 API 호출: {}", stockCode);

        Map<String, Object> response = new HashMap<>();
        try {
            boolean success = financialDataCrawlerService.crawlSingleStock(stockCode);
            response.put("success", success);
            response.put("stockCode", stockCode);
            response.put("message", success ? "크롤링 완료" : "크롤링 실패");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("단일 종목 영업이익률 크롤링 오류: {}", stockCode, e);
            response.put("success", false);
            response.put("message", "크롤링 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ========== 비동기 크롤링 API (SSE 연동) ==========

    /**
     * 영업이익률 크롤링 (비동기)
     * - 즉시 응답하고 백그라운드에서 크롤링 수행
     * - SSE로 진행률 실시간 전송 (/api/sse/subscribe?taskType=crawl-operating-margin)
     */
    @PostMapping("/crawl-operating-margin/async")
    @Operation(summary = "영업이익률 크롤링 (비동기)",
               description = "즉시 응답하고 백그라운드에서 크롤링을 수행합니다.\n\n" +
                           "**SSE 연동 방법:**\n" +
                           "1. 먼저 `/api/sse/subscribe?taskType=crawl-operating-margin` 구독\n" +
                           "2. 이 API 호출\n" +
                           "3. SSE로 진행률 수신 (PROGRESS, COMPLETE 이벤트)")
    public ResponseEntity<Map<String, Object>> crawlOperatingMarginAsync(
            @Parameter(description = "기존 데이터 강제 업데이트 여부 (기본: false)")
            @RequestParam(defaultValue = "false") boolean forceUpdate) {

        log.info("영업이익률 비동기 크롤링 API 호출 - forceUpdate: {}", forceUpdate);

        Map<String, Object> response = new HashMap<>();

        // 이미 실행 중인지 확인
        if (asyncCrawlerService.isTaskRunning("crawl-operating-margin")) {
            response.put("success", false);
            response.put("message", "이미 크롤링이 진행 중입니다. SSE를 구독하여 진행률을 확인하세요.");
            response.put("taskType", "crawl-operating-margin");
            return ResponseEntity.ok(response);
        }

        // 비동기 작업 시작
        asyncCrawlerService.crawlAllOperatingMarginAsync(forceUpdate);

        response.put("success", true);
        response.put("message", "영업이익률 크롤링이 시작되었습니다. SSE를 구독하여 진행률을 확인하세요.");
        response.put("taskType", "crawl-operating-margin");
        response.put("sseEndpoint", "/api/sse/subscribe?taskType=crawl-operating-margin");
        return ResponseEntity.ok(response);
    }

    /**
     * 분기별 재무제표 수집 (비동기)
     * - 즉시 응답하고 백그라운드에서 수집 수행
     * - SSE로 진행률 실시간 전송 (/api/sse/subscribe?taskType=collect-finance)
     */
    @PostMapping("/collect/finance/async")
    @Operation(summary = "분기별 재무제표 수집 (비동기)",
               description = "즉시 응답하고 백그라운드에서 수집을 수행합니다.\n\n" +
                           "**SSE 연동 방법:**\n" +
                           "1. 먼저 `/api/sse/subscribe?taskType=collect-finance` 구독\n" +
                           "2. 이 API 호출\n" +
                           "3. SSE로 진행률 수신 (PROGRESS, COMPLETE 이벤트)")
    public ResponseEntity<Map<String, Object>> collectQuarterlyFinanceAsync() {
        log.info("분기별 재무제표 비동기 수집 API 호출");

        Map<String, Object> response = new HashMap<>();

        // 이미 실행 중인지 확인
        if (asyncCrawlerService.isTaskRunning("collect-finance")) {
            response.put("success", false);
            response.put("message", "이미 수집이 진행 중입니다. SSE를 구독하여 진행률을 확인하세요.");
            response.put("taskType", "collect-finance");
            return ResponseEntity.ok(response);
        }

        // 비동기 작업 시작
        asyncCrawlerService.collectQuarterlyFinanceAsync();

        response.put("success", true);
        response.put("message", "분기별 재무제표 수집이 시작되었습니다. SSE를 구독하여 진행률을 확인하세요.");
        response.put("taskType", "collect-finance");
        response.put("sseEndpoint", "/api/sse/subscribe?taskType=collect-finance");
        return ResponseEntity.ok(response);
    }

    /**
     * 종목명 일괄 수정 (비동기)
     */
    @PostMapping("/fix-stock-names/async")
    @Operation(summary = "종목명 일괄 수정 (비동기)",
               description = "즉시 응답하고 백그라운드에서 수정을 수행합니다.")
    public ResponseEntity<Map<String, Object>> fixAllStockNamesAsync() {
        log.info("종목명 일괄 수정 비동기 API 호출");

        Map<String, Object> response = new HashMap<>();

        if (asyncCrawlerService.isTaskRunning("fix-stock-names")) {
            response.put("success", false);
            response.put("message", "이미 수정 작업이 진행 중입니다.");
            return ResponseEntity.ok(response);
        }

        asyncCrawlerService.fixAllStockNamesAsync();

        response.put("success", true);
        response.put("message", "종목명 수정이 시작되었습니다. SSE를 구독하여 진행률을 확인하세요.");
        response.put("taskType", "fix-stock-names");
        response.put("sseEndpoint", "/api/sse/subscribe?taskType=fix-stock-names");
        return ResponseEntity.ok(response);
    }

    /**
     * 크롤링 작업 상태 확인
     */
    @GetMapping("/async-status")
    @Operation(summary = "비동기 작업 상태 확인",
               description = "현재 진행 중인 비동기 크롤링/수집 작업 상태를 확인합니다.")
    public ResponseEntity<Map<String, Object>> getAsyncStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("crawlOperatingMargin", Map.of(
                "running", asyncCrawlerService.isTaskRunning("crawl-operating-margin"),
                "taskType", "crawl-operating-margin"
        ));
        response.put("collectFinance", Map.of(
                "running", asyncCrawlerService.isTaskRunning("collect-finance"),
                "taskType", "collect-finance"
        ));
        response.put("fixStockNames", Map.of(
                "running", asyncCrawlerService.isTaskRunning("fix-stock-names"),
                "taskType", "fix-stock-names"
        ));
        return ResponseEntity.ok(response);
    }

    /**
     * 단일 종목 재무비율 조회 (크롤링만, 저장 안함)
     */
    @GetMapping("/crawl-preview/{stockCode}")
    @Operation(summary = "크롤링 미리보기",
               description = "특정 종목의 재무 지표를 크롤링하여 결과만 확인합니다 (저장 안함).")
    public ResponseEntity<Map<String, Object>> previewCrawl(@PathVariable String stockCode) {
        log.info("크롤링 미리보기 API 호출: {}", stockCode);

        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, BigDecimal> ratios = financialDataCrawlerService.crawlFinancialRatios(stockCode);
            response.put("success", ratios != null && !ratios.isEmpty());
            response.put("stockCode", stockCode);
            response.put("data", ratios);
            response.put("message", ratios != null && !ratios.isEmpty()
                    ? "크롤링 성공"
                    : "데이터를 찾을 수 없습니다");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("크롤링 미리보기 오류: {}", stockCode, e);
            response.put("success", false);
            response.put("message", "크롤링 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 종목명 일괄 수정
     * - 종목코드가 종목명으로 저장된 데이터 수정
     * - StockShortData 또는 네이버 금융에서 종목명 조회
     */
    @PostMapping("/fix-stock-names")
    @Operation(summary = "종목명 일괄 수정",
               description = "종목코드가 종목명으로 잘못 저장된 데이터를 수정합니다. " +
                           "StockShortData 또는 네이버 금융에서 종목명을 조회하여 업데이트합니다.")
    public ResponseEntity<Map<String, Object>> fixAllStockNames() {
        log.info("종목명 일괄 수정 API 호출");

        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> result = financialDataCrawlerService.fixAllStockNames();
            response.put("success", true);
            response.put("data", result);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("종목명 일괄 수정 오류", e);
            response.put("success", false);
            response.put("message", "종목명 수정 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ========== 분기별 재무제표 수집 API (PEG, 턴어라운드용) ==========

    /**
     * 분기별 재무제표 수집
     * - 네이버 금융에서 최근 4개 분기 데이터 크롤링
     * - 매출액, 영업이익, 당기순이익, EPS 수집
     * - EPS 성장률 계산 및 PEG 업데이트
     * - 과거 분기 데이터 저장 (턴어라운드 분석 가능)
     *
     * Rate Limit 고려: 종목당 600ms 대기
     * 약 2000개 종목 기준 20-25분 소요
     */
    @PostMapping("/collect/finance")
    @Operation(summary = "분기별 재무제표 수집",
               description = "네이버 금융에서 최근 4개 분기의 재무제표(매출액, 영업이익, 당기순이익, EPS)를 크롤링합니다.\n\n" +
                           "**수집 데이터:**\n" +
                           "- 매출액, 영업이익, 당기순이익, EPS (분기별)\n" +
                           "- EPS 성장률 계산 (전년 동기 대비)\n" +
                           "- PEG = PER / EPS성장률 자동 계산\n" +
                           "- 순이익 증가율 계산 (직전 분기 대비)\n\n" +
                           "**용도:**\n" +
                           "- PEG 스크리너: EPS 성장률 기반 저평가 성장주 발굴\n" +
                           "- 턴어라운드 스크리너: 과거 분기 데이터로 적자→흑자 전환 종목 발굴\n\n" +
                           "**소요시간:** 종목당 600ms 대기, 약 2000개 종목 기준 20-25분")
    public ResponseEntity<Map<String, Object>> collectQuarterlyFinance() {
        log.info("분기별 재무제표 수집 API 호출");

        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> result = financialDataCrawlerService.collectQuarterlyFinancialStatements();
            response.put("success", true);
            response.put("data", result);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("분기별 재무제표 수집 오류", e);
            response.put("success", false);
            response.put("message", "분기별 재무제표 수집 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 단일 종목 분기별 재무제표 수집
     */
    @PostMapping("/collect/finance/{stockCode}")
    @Operation(summary = "단일 종목 분기별 재무제표 수집",
               description = "특정 종목의 분기별 재무제표를 네이버 금융에서 크롤링합니다.")
    public ResponseEntity<Map<String, Object>> collectSingleQuarterlyFinance(
            @Parameter(description = "종목코드 (예: 005930)")
            @PathVariable String stockCode) {

        log.info("단일 종목 분기별 재무제표 수집 API 호출: {}", stockCode);

        Map<String, Object> response = new HashMap<>();
        try {
            boolean success = financialDataCrawlerService.collectSingleStockQuarterlyData(stockCode);
            response.put("success", success);
            response.put("stockCode", stockCode);
            response.put("message", success ? "분기별 재무제표 수집 완료" : "분기별 재무제표 수집 실패 (데이터 없음)");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("단일 종목 분기별 재무제표 수집 오류: {}", stockCode, e);
            response.put("success", false);
            response.put("message", "수집 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
