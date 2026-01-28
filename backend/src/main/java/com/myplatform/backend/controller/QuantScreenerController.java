package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ScreenerResultDto;
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
     */
    @GetMapping("/collect-status")
    @Operation(summary = "재무 데이터 수집 상태", description = "현재 수집된 재무 데이터 현황을 조회합니다.")
    public ResponseEntity<Map<String, Object>> getCollectStatus() {
        Map<String, Object> response = new HashMap<>();
        try {
            long totalCount = stockFinancialDataService.getDataCount();
            long withOperatingMargin = financialDataCrawlerService.countWithOperatingMargin();
            long missingOperatingMargin = financialDataCrawlerService.countMissingOperatingMargin();

            response.put("success", true);
            response.put("totalRecords", totalCount);
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
}
