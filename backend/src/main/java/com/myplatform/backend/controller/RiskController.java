package com.myplatform.backend.controller;

import com.myplatform.backend.dto.RiskAnalysisDto;
import com.myplatform.backend.service.RiskManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 리스크 분석 API 컨트롤러
 *
 * [엔드포인트]
 * - GET /api/risk/check?stockName=삼성전자 : 종합 리스크 분석
 * - GET /api/risk/quick?stockName=삼성전자 : 빠른 위험 체크 (공시만)
 * - GET /api/risk/safe?stockName=삼성전자 : 매수 가능 여부만 반환
 */
@RestController
@RequestMapping("/api/risk")
@RequiredArgsConstructor
@Slf4j
public class RiskController {

    private final RiskManagementService riskManagementService;

    /**
     * 종합 리스크 분석
     *
     * GET /api/risk/check?stockName=삼성전자
     *
     * @param stockName 종목명 (필수)
     * @return 리스크 분석 결과 (점수, 상태, 사유, 공시, 뉴스)
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkRisk(
            @RequestParam String stockName) {

        log.info("[RiskController] 리스크 분석 요청: {}", stockName);

        if (stockName == null || stockName.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "종목명을 입력해 주세요."));
        }

        try {
            RiskAnalysisDto result = riskManagementService.analyzeRisk(stockName.trim());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", result);

            // 매수 금지 여부를 명시적으로 표시
            if (result.getStatus() == RiskAnalysisDto.RiskStatus.DANGER) {
                response.put("tradingAllowed", false);
                response.put("warning", "⚠️ 이 종목은 현재 매수 금지 상태입니다. 리스크 점수: " + result.getRiskScore());
            } else {
                response.put("tradingAllowed", true);
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[RiskController] 리스크 분석 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "success", false,
                            "message", "리스크 분석 중 오류가 발생했습니다.",
                            "error", e.getMessage()
                    ));
        }
    }

    /**
     * 빠른 위험 체크 (공시만 확인)
     *
     * GET /api/risk/quick?stockName=삼성전자
     *
     * @param stockName 종목명
     * @return 위험 공시 존재 여부 (빠른 응답)
     */
    @GetMapping("/quick")
    public ResponseEntity<Map<String, Object>> quickCheck(
            @RequestParam String stockName) {

        log.info("[RiskController] 빠른 위험 체크: {}", stockName);

        if (stockName == null || stockName.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "종목명을 입력해 주세요."));
        }

        try {
            boolean hasDanger = riskManagementService.quickDangerCheck(stockName.trim());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("stockName", stockName);
            response.put("hasDangerousDisclosure", hasDanger);
            response.put("tradingAllowed", !hasDanger);

            if (hasDanger) {
                response.put("warning", "⚠️ 위험 공시가 발견되었습니다. 상세 분석을 권장합니다.");
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[RiskController] 빠른 위험 체크 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "체크 중 오류가 발생했습니다."));
        }
    }

    /**
     * 매수 가능 여부만 반환 (간편 API)
     *
     * GET /api/risk/safe?stockName=삼성전자
     *
     * @param stockName 종목명
     * @return 매수 가능 여부 (true/false)
     */
    @GetMapping("/safe")
    public ResponseEntity<Map<String, Object>> isSafeToBuy(
            @RequestParam String stockName) {

        log.info("[RiskController] 매수 가능 여부 확인: {}", stockName);

        if (stockName == null || stockName.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "종목명을 입력해 주세요."));
        }

        try {
            boolean safe = riskManagementService.isSafeToBuy(stockName.trim());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("stockName", stockName);
            response.put("safeToBuy", safe);
            response.put("tradingAllowed", safe);

            if (!safe) {
                response.put("message", "이 종목은 현재 리스크가 높아 매수를 권장하지 않습니다.");
            } else {
                response.put("message", "현재 특별한 위험 요소가 발견되지 않았습니다.");
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[RiskController] 매수 가능 여부 확인 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "확인 중 오류가 발생했습니다."));
        }
    }

    /**
     * 여러 종목 일괄 체크
     *
     * POST /api/risk/batch
     *
     * @param request { "stockNames": ["삼성전자", "SK하이닉스", ...] }
     * @return 종목별 매수 가능 여부
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> batchCheck(
            @RequestBody Map<String, Object> request) {

        @SuppressWarnings("unchecked")
        java.util.List<String> stockNames = (java.util.List<String>) request.get("stockNames");

        if (stockNames == null || stockNames.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "종목 목록을 입력해 주세요."));
        }

        log.info("[RiskController] 일괄 리스크 체크: {}건", stockNames.size());

        Map<String, Object> results = new HashMap<>();
        for (String stockName : stockNames) {
            try {
                boolean safe = riskManagementService.isSafeToBuy(stockName);
                results.put(stockName, Map.of(
                        "safeToBuy", safe,
                        "status", safe ? "SAFE" : "DANGER"
                ));
            } catch (Exception e) {
                results.put(stockName, Map.of(
                        "safeToBuy", false,
                        "status", "ERROR",
                        "error", e.getMessage()
                ));
            }
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", results
        ));
    }
}
