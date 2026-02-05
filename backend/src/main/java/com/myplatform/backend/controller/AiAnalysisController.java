package com.myplatform.backend.controller;

import com.myplatform.backend.dto.AiAnalysisResponseDto;
import com.myplatform.backend.service.AiStockAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 주식 분석 API
 * - AI 종합 점수 기반 종목 추천
 * - 단기/중장기 TOP PICK
 * - AI 4대장 앙상블 의견
 */
@RestController
@RequestMapping("/api/ai-analysis")
@RequiredArgsConstructor
@Slf4j
public class AiAnalysisController {

    private final AiStockAnalysisService aiAnalysisService;

    /**
     * AI 분석 결과 조회
     * - 단기/중장기 TOP PICK
     * - 시장 지표
     * - AI 앙상블 정보
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAnalysis() {
        Map<String, Object> response = new HashMap<>();
        try {
            AiAnalysisResponseDto analysis = aiAnalysisService.getAnalysis();
            response.put("success", true);
            response.put("data", analysis);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("AI 분석 조회 실패: {}", e.getMessage());
            response.put("success", false);
            response.put("message", "AI 분석 조회 중 오류가 발생했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * AI 분석 새로고침 (강제 재분석)
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshAnalysis() {
        Map<String, Object> response = new HashMap<>();
        try {
            AiAnalysisResponseDto analysis = aiAnalysisService.refreshAnalysis();
            response.put("success", true);
            response.put("data", analysis);
            response.put("message", "AI 분석이 새로고침되었습니다.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("AI 분석 새로고침 실패: {}", e.getMessage());
            response.put("success", false);
            response.put("message", "AI 분석 새로고침 중 오류가 발생했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
