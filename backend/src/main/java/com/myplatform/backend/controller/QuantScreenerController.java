package com.myplatform.backend.controller;

import com.myplatform.backend.dto.EarningSurpriseDto;
import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.service.AsyncCrawlerService;
import com.myplatform.backend.service.EarningSurpriseService;
import com.myplatform.backend.service.FinancialDataCrawlerService;
import com.myplatform.backend.service.GeminiService;
import com.myplatform.backend.service.QuantScreenerService;
import com.myplatform.backend.service.RedisCacheService;
import com.myplatform.backend.service.StockFinancialDataCollector;
import com.myplatform.backend.service.StockFinancialDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
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
    private final EarningSurpriseService earningSurpriseService;
    private final GeminiService geminiService;
    private final RedisCacheService redisCacheService;

    // 스크리너 AI 분석 — 같은 조건 반복 호출 시 Gemini 쿼터 보호 (5분 캐시)
    private static final String CACHE_SCREENER_AI = "screenerAi";
    private static final Duration SCREENER_AI_TTL = Duration.ofMinutes(5);
    private final StockFinancialDataService stockFinancialDataService;
    private final FinancialDataCrawlerService financialDataCrawlerService;
    private final AsyncCrawlerService asyncCrawlerService;
    private final StockFinancialDataCollector stockFinancialDataCollector;

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

            String cacheKey = "magic_" + limit + "_" + (minMarketCap != null ? minMarketCap : "none");
            String cached = redisCacheService.get(CACHE_SCREENER_AI, cacheKey, String.class);
            if (cached != null) {
                response.put("success", true);
                response.put("analysis", cached);
                response.put("cached", true);
                response.put("message", "AI 분석 완료 (캐시)");
                return ResponseEntity.ok(response);
            }

            List<ScreenerResultDto> stocks = quantScreenerService.getMagicFormulaStocks(limit, minMarketCap);
            String analysis = geminiService.analyzeMagicFormula(stocks);
            if (analysis != null) {
                redisCacheService.put(CACHE_SCREENER_AI, cacheKey, analysis, SCREENER_AI_TTL);
            }

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

            String cacheKey = "peg_" + limit + "_" + (maxPeg != null ? maxPeg : "none")
                    + "_" + (minEpsGrowth != null ? minEpsGrowth : "none");
            String cached = redisCacheService.get(CACHE_SCREENER_AI, cacheKey, String.class);
            if (cached != null) {
                response.put("success", true);
                response.put("analysis", cached);
                response.put("cached", true);
                response.put("message", "AI 분석 완료 (캐시)");
                return ResponseEntity.ok(response);
            }

            List<ScreenerResultDto> stocks = quantScreenerService.getLowPegStocks(maxPeg, minEpsGrowth, limit);
            String analysis = geminiService.analyzePegStocks(stocks);
            if (analysis != null) {
                redisCacheService.put(CACHE_SCREENER_AI, cacheKey, analysis, SCREENER_AI_TTL);
            }

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

            String cacheKey = "turnaround_" + limit;
            String cached = redisCacheService.get(CACHE_SCREENER_AI, cacheKey, String.class);
            if (cached != null) {
                response.put("success", true);
                response.put("analysis", cached);
                response.put("cached", true);
                response.put("message", "AI 분석 완료 (캐시)");
                return ResponseEntity.ok(response);
            }

            List<ScreenerResultDto> stocks = quantScreenerService.getTurnaroundStocks(limit);
            String analysis = geminiService.analyzeTurnaroundStocks(stocks);
            if (analysis != null) {
                redisCacheService.put(CACHE_SCREENER_AI, cacheKey, analysis, SCREENER_AI_TTL);
            }

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

    // ========== 어닝 서프라이즈 API ==========

    /**
     * 어닝 서프라이즈 종목 조회
     * - 영업이익 전분기 대비 20%+ 변동 종목
     * - 적자→흑자 전환 종목
     */
    @GetMapping("/earning-surprise")
    @Operation(summary = "어닝 서프라이즈 스크리너",
               description = "분기 실적 비교로 영업이익 20% 이상 변동 종목을 감지합니다.")
    public ResponseEntity<Map<String, Object>> getEarningSurprises(
            @Parameter(description = "서프라이즈 유형 필터 (POSITIVE, NEGATIVE, TURNAROUND)")
            @RequestParam(required = false) String type) {

        log.info("어닝 서프라이즈 API 호출 - type: {}", type);

        Map<String, Object> response = new HashMap<>();
        try {
            List<EarningSurpriseDto> surprises = earningSurpriseService.detectEarningSurprises();

            // 유형 필터 적용
            if (type != null && !type.isEmpty()) {
                try {
                    EarningSurpriseDto.SurpriseType filterType = EarningSurpriseDto.SurpriseType.valueOf(type.toUpperCase());
                    surprises = surprises.stream()
                            .filter(s -> s.getSurpriseType() == filterType)
                            .collect(java.util.stream.Collectors.toList());
                } catch (IllegalArgumentException e) {
                    log.warn("잘못된 서프라이즈 유형: {}", type);
                }
            }

            response.put("success", true);
            response.put("data", surprises);
            response.put("count", surprises.size());
            response.put("message", "어닝 서프라이즈 스크리닝 완료");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("어닝 서프라이즈 스크리너 오류", e);
            response.put("success", false);
            response.put("message", "스크리닝 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
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
        if (!isValidStockCode(stockCode)) return invalidStockCode(stockCode);

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
     * 원버튼 전체 데이터 수집 (비동기)
     * - 즉시 응답하고 백그라운드에서 수집 수행
     * - SSE로 진행률 실시간 전송 (/api/sse/subscribe?taskType=collect-all-in-one)
     * - 1단계: 기본 재무 데이터 수집 (KIS API)
     * - 2단계: 영업이익률 크롤링 (네이버 금융)
     * - 3단계: 분기별 재무제표 수집 (네이버 금융)
     */
    @PostMapping("/collect-all-in-one")
    @Operation(summary = "원버튼 전체 데이터 수집 (비동기)",
               description = "즉시 응답하고 백그라운드에서 수집을 수행합니다.\n\n" +
                           "**SSE 연동 방법:**\n" +
                           "1. 먼저 `/api/sse/subscribe?taskType=collect-all-in-one` 구독\n" +
                           "2. 이 API 호출\n" +
                           "3. SSE로 진행률 수신 (PROGRESS, COMPLETE 이벤트)")
    public ResponseEntity<Map<String, Object>> collectAllInOne() {
        log.info("=== 원버튼 전체 데이터 수집 (비동기) 요청 ===");

        Map<String, Object> response = new HashMap<>();

        // 이미 실행 중인지 확인
        if (asyncCrawlerService.isTaskRunning("collect-all-in-one")) {
            response.put("success", false);
            response.put("message", "이미 전체 수집이 진행 중입니다. SSE를 구독하여 진행률을 확인하세요.");
            response.put("taskType", "collect-all-in-one");
            return ResponseEntity.ok(response);
        }

        // 비동기 작업 시작
        asyncCrawlerService.collectAllInOneAsync();

        response.put("success", true);
        response.put("message", "전체 데이터 수집이 시작되었습니다. SSE를 구독하여 진행률을 확인하세요.");
        response.put("taskType", "collect-all-in-one");
        response.put("sseEndpoint", "/api/sse/subscribe?taskType=collect-all-in-one");
        return ResponseEntity.ok(response);
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
     * - 총 건수, 마지막 업데이트 시간, 영업이익률 현황, 성장률 데이터 현황
     * - 마지막 자동 수집 결과 (08:30, 15:40 스케줄러)
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

            // 성장률 데이터 현황 (PEG 스크리너용)
            long withGrowthData = financialDataCrawlerService.countWithGrowthData();

            // 마지막 자동 수집 결과 (08:30, 15:40 스케줄러)
            Map<String, Object> lastAutoCollect = asyncCrawlerService.getLastAutoCollectStatus();

            response.put("success", true);
            response.put("totalRecords", totalCount);
            response.put("lastUpdatedAt", lastUpdatedAt);
            response.put("withOperatingMargin", withOperatingMargin);
            response.put("missingOperatingMargin", missingOperatingMargin);
            response.put("withGrowthData", withGrowthData);  // PEG 스크리너 사용 가능 종목 수
            response.put("lastAutoCollect", lastAutoCollect);  // 마지막 자동 수집 결과
            response.put("message", totalCount > 0
                    ? String.format("재무 데이터 %d건 (영업이익률: %d건, 성장률: %d건)",
                            totalCount, withOperatingMargin, withGrowthData)
                    : "수집된 재무 데이터가 없습니다. 원버튼 전체 수집을 실행하세요.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("재무 데이터 상태 조회 오류", e);
            response.put("success", false);
            response.put("message", "상태 조회 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ========== 성장률 계산 API ==========

    /**
     * 성장률 계산 및 업데이트
     * - 과거 데이터 기반으로 epsGrowth, profitGrowth, revenueGrowth 계산
     * - PEG = PER / epsGrowth 자동 계산
     * - PEG 스크리너 데이터 보강용
     */
    @PostMapping("/calculate-growth")
    @Operation(summary = "성장률 계산",
               description = "DB에 저장된 과거 데이터를 기반으로 성장률(YoY)을 계산합니다.\n\n" +
                           "**계산 항목:**\n" +
                           "- EPS 성장률 (전년 동기 대비)\n" +
                           "- 순이익 성장률\n" +
                           "- 매출 성장률\n" +
                           "- PEG = PER / EPS성장률\n\n" +
                           "**용도:** PEG 스크리너에서 성장률 데이터가 없는 종목 보강")
    public ResponseEntity<Map<String, Object>> calculateGrowthRates() {
        log.info("성장률 계산 API 호출");

        Map<String, Object> response = new HashMap<>();
        try {
            int updatedCount = stockFinancialDataCollector.calculateAndUpdateGrowthRates();
            response.put("success", true);
            response.put("updatedCount", updatedCount);
            response.put("message", String.format("성장률 계산 완료 - %d건 업데이트", updatedCount));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("성장률 계산 오류", e);
            response.put("success", false);
            response.put("message", "성장률 계산 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ========== 비동기 크롤링 API (SSE 연동) ==========


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
        // crawl-operating-margin · collect-finance 항목은 뺐다 — 두 작업 모두 2026-09-23 에 은퇴해
        // 영원히 running=false 인 상태를 보고하고 있었다(없는 작업의 상태를 '정상'처럼 보여주지 않는다).
        response.put("fixStockNames", Map.of(
                "running", asyncCrawlerService.isTaskRunning("fix-stock-names"),
                "taskType", "fix-stock-names"
        ));
        return ResponseEntity.ok(response);
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
     * 경로로 받은 종목코드가 형식에 맞는가 — 순수({@code QuantScreenerStockCodeTest}).
     *
     * <p><b>왜 필요한가</b>(2026-09-23): 분기 크롤 엔드포인트를 지우자
     * {@code POST /collect/finance} 가 {@code /collect/{stockCode}} 에 잡혀
     * <b>stockCode="finance" 로 수집이 돌았고 그게 성공(200 · "재무 데이터 수집 완료")으로 응답됐다.</b>
     * KIS 는 없는 코드에도 200 을 주므로 수집기가 빈 행을 저장했다 — 실제로
     * {@code stock_code='finance'} 행이 하나 생겼다. §4c(없는 것을 있는 것처럼) 그대로다.
     *
     * <p>기준은 종목마스터 규약과 같다 — <b>6자리 영숫자</b>(`\d{6}` 로 좁히면 종류주식이 빠진다).
     */
    static boolean isValidStockCode(String stockCode) {
        return com.myplatform.backend.util.StockCodeFormat.isValid(stockCode);
    }

    /** 형식 위반 응답 — 성공으로 위장하지 않는다. */
    private ResponseEntity<Map<String, Object>> invalidStockCode(String stockCode) {
        log.warn("잘못된 종목코드 형식으로 호출됨: {}", stockCode);
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("stockCode", stockCode);
        body.put("message", "종목코드 형식이 아닙니다(6자리 영숫자).");
        return ResponseEntity.badRequest().body(body);
    }

    // ⚠ 분기별 재무제표 수집 엔드포인트 3종(전종목 비동기/동기, 단일종목)은 2026-09-23 제거했다.
    //   네이버 레거시 금융 페이지가 SPA 로 이전해 소스가 죽었고(2026-09-22 실측 성공 0 / 실패 2,662),
    //   분기 재무 단일 출처는 KIS V55(stock_quarterly_financial)다. 되살리지 말 것.
}
