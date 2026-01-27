package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.service.GeminiService;
import com.myplatform.backend.service.QuantScreenerService;
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
}
