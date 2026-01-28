package com.myplatform.backend.controller;

import com.myplatform.backend.dto.StockDiagnosisDto;
import com.myplatform.backend.service.StockAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 종목 분석 API 컨트롤러
 *
 * 마법의 공식 등에서 선택한 종목의 상세 진단(더블 체크) 제공
 */
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "종목 분석", description = "종목 상세 진단 API - 재무, 수급, 기술적 분석")
public class StockAnalysisController {

    private final StockAnalysisService stockAnalysisService;

    /**
     * 종목 상세 진단 (더블 체크)
     *
     * 마법의 공식 등에서 선택한 종목이 진짜 살만한지 분석:
     * 1. 재무 건전성: 영업이익 vs 순이익 비교 (일회성 이익 경고)
     * 2. 수급 현황: 최근 5일 외국인/기관 순매수
     * 3. 기술적 분석: 이평선 정배열, RSI 상태
     *
     * @param stockCode 종목코드 (예: 005930)
     * @return 상세 진단 결과
     */
    @GetMapping("/diagnosis/{stockCode}")
    @Operation(
        summary = "종목 상세 진단",
        description = "마법의 공식 등에서 선택한 종목의 '더블 체크' 분석을 수행합니다.\n\n" +
                     "**분석 항목:**\n" +
                     "1. 재무 건전성: 영업이익 vs 순이익 비교, 일회성 이익 경고\n" +
                     "2. 수급 현황: 최근 5일 외국인/기관 순매수 합계\n" +
                     "3. 기술적 분석: 이평선 정배열, 골든크로스, RSI 상태\n\n" +
                     "**종합 의견 레벨:**\n" +
                     "- STRONG_BUY: 매수 적기\n" +
                     "- BUY: 매수 고려\n" +
                     "- NEUTRAL: 관망 권고\n" +
                     "- CAUTION: 주의 요망\n" +
                     "- AVOID: 매수 비추천"
    )
    public ResponseEntity<Map<String, Object>> diagnoseStock(
            @Parameter(description = "종목코드 (예: 005930)")
            @PathVariable String stockCode) {

        log.info("종목 상세 진단 API 호출: {}", stockCode);

        Map<String, Object> response = new HashMap<>();
        try {
            StockDiagnosisDto diagnosis = stockAnalysisService.diagnose(stockCode);

            response.put("success", true);
            response.put("data", diagnosis);
            response.put("message", "종목 진단 완료");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("종목 진단 오류: {}", stockCode, e);
            response.put("success", false);
            response.put("message", "진단 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 간단 수급 체크
     * 특정 종목의 최근 5일 외국인/기관 수급만 빠르게 조회
     */
    @GetMapping("/supply-demand/{stockCode}")
    @Operation(
        summary = "수급 간단 체크",
        description = "특정 종목의 최근 5일 외국인/기관 순매수 현황만 빠르게 조회합니다."
    )
    public ResponseEntity<Map<String, Object>> checkSupplyDemand(
            @Parameter(description = "종목코드")
            @PathVariable String stockCode) {

        log.info("수급 체크 API 호출: {}", stockCode);

        Map<String, Object> response = new HashMap<>();
        try {
            StockDiagnosisDto diagnosis = stockAnalysisService.diagnose(stockCode);

            response.put("success", true);
            response.put("stockCode", stockCode);
            response.put("supplyDemand", diagnosis.getSupplyDemand());
            response.put("message", "수급 체크 완료");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("수급 체크 오류: {}", stockCode, e);
            response.put("success", false);
            response.put("message", "수급 체크 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 기술적 분석 조회
     * 특정 종목의 기술적 지표만 조회
     */
    @GetMapping("/technical/{stockCode}")
    @Operation(
        summary = "기술적 분석 조회",
        description = "특정 종목의 이평선, RSI 등 기술적 분석 결과를 조회합니다."
    )
    public ResponseEntity<Map<String, Object>> checkTechnical(
            @Parameter(description = "종목코드")
            @PathVariable String stockCode) {

        log.info("기술적 분석 API 호출: {}", stockCode);

        Map<String, Object> response = new HashMap<>();
        try {
            StockDiagnosisDto diagnosis = stockAnalysisService.diagnose(stockCode);

            response.put("success", true);
            response.put("stockCode", stockCode);
            response.put("technicalAnalysis", diagnosis.getTechnicalAnalysis());
            response.put("message", "기술적 분석 완료");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("기술적 분석 오류: {}", stockCode, e);
            response.put("success", false);
            response.put("message", "기술적 분석 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
