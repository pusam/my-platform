package com.myplatform.backend.controller;

import com.myplatform.backend.entity.EarningsDisclosure;
import com.myplatform.backend.service.EarningsDisclosureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 실적공시 API 컨트롤러
 *
 * [엔드포인트]
 * - GET  /api/earnings/recent         : 최근 실적공시 목록
 * - GET  /api/earnings/calendar       : 캘린더 데이터 (월별)
 * - GET  /api/earnings/watchlist      : 관심종목 실적공시
 * - GET  /api/earnings/search         : 종목명 검색
 * - GET  /api/earnings/stats          : 유형별 통계
 * - POST /api/earnings/collect        : 수동 수집 트리거
 * - GET  /api/earnings/summary       : 실적 요약 (DART 재무 + AI 코멘트)
 */
@RestController
@RequestMapping("/api/earnings")
@RequiredArgsConstructor
@Slf4j
public class EarningsDisclosureController {

    private final EarningsDisclosureService earningsService;

    /**
     * 최근 실적공시 목록
     * GET /api/earnings/recent?months=3
     */
    @GetMapping("/recent")
    public ResponseEntity<Map<String, Object>> getRecentDisclosures(
            @RequestParam(defaultValue = "3") int months) {
        try {
            List<EarningsDisclosure> disclosures = earningsService.getRecentDisclosures(months);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", disclosures,
                    "total", disclosures.size()
            ));
        } catch (Exception e) {
            log.error("[실적공시] 조회 실패: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "실적공시 조회 실패"));
        }
    }

    /**
     * 캘린더 데이터
     * GET /api/earnings/calendar?year=2026&month=3
     */
    @GetMapping("/calendar")
    public ResponseEntity<Map<String, Object>> getCalendarData(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        try {
            if (year == null) year = LocalDate.now().getYear();
            if (month == null) month = LocalDate.now().getMonthValue();

            Map<String, List<EarningsDisclosure>> calendar = earningsService.getCalendarData(year, month);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", calendar,
                    "year", year,
                    "month", month
            ));
        } catch (Exception e) {
            log.error("[실적공시] 캘린더 조회 실패: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "캘린더 데이터 조회 실패"));
        }
    }

    /**
     * 관심종목 실적공시
     * GET /api/earnings/watchlist
     */
    @GetMapping("/watchlist")
    public ResponseEntity<Map<String, Object>> getWatchlistDisclosures(Principal principal) {
        try {
            String username = principal != null ? principal.getName() : "admin";
            List<EarningsDisclosure> disclosures = earningsService.getWatchlistDisclosures(username);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", disclosures,
                    "total", disclosures.size()
            ));
        } catch (Exception e) {
            log.error("[실적공시] 관심종목 조회 실패: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "관심종목 실적공시 조회 실패"));
        }
    }

    /**
     * 종목명 검색
     * GET /api/earnings/search?q=삼성전자
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchDisclosures(@RequestParam String q) {
        try {
            if (q == null || q.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "검색어를 입력해 주세요."));
            }

            List<EarningsDisclosure> disclosures = earningsService.searchByCorpName(q.trim());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", disclosures,
                    "total", disclosures.size()
            ));
        } catch (Exception e) {
            log.error("[실적공시] 검색 실패: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "검색 실패"));
        }
    }

    /**
     * 유형별 통계
     * GET /api/earnings/stats?months=3
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(defaultValue = "3") int months) {
        try {
            Map<String, Long> stats = earningsService.getTypeStats(months);
            return ResponseEntity.ok(Map.of("success", true, "data", stats));
        } catch (Exception e) {
            log.error("[실적공시] 통계 조회 실패: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "통계 조회 실패"));
        }
    }

    /**
     * 실적 요약 (DART 재무 수치 + AI 코멘트)
     * GET /api/earnings/summary?corpCode=00126380&corpName=삼성전자&reportType=QUARTERLY
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getEarningsSummary(
            @RequestParam(required = false) String corpCode,
            @RequestParam String corpName,
            @RequestParam(defaultValue = "QUARTERLY") String reportType) {
        try {
            // corpCode 없으면 corpName으로 매핑
            if (corpCode == null || corpCode.isEmpty()) {
                corpCode = earningsService.findCorpCodeByName(corpName);
            }

            if (corpCode == null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "data", Map.of(
                                "corpName", corpName,
                                "financials", Collections.emptyList(),
                                "aiComment", "기업코드를 찾을 수 없어 재무 데이터를 조회할 수 없습니다."
                        )
                ));
            }

            Map<String, Object> summary = earningsService.getEarningsSummary(corpCode, corpName, reportType);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", summary
            ));
        } catch (Exception e) {
            log.error("[실적요약] 조회 실패: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "실적 요약 조회 실패: " + e.getMessage()));
        }
    }

    /**
     * 수동 수집 트리거
     * POST /api/earnings/collect
     */
    @PostMapping("/collect")
    public ResponseEntity<Map<String, Object>> collectNow() {
        try {
            if (!earningsService.isAvailable()) {
                return ResponseEntity.ok(Map.of(
                        "success", false,
                        "message", "DART API Key가 설정되지 않았습니다."
                ));
            }

            int collected = earningsService.collectEarningsDisclosures();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", String.format("실적공시 %d건 수집 완료", collected),
                    "collected", collected
            ));
        } catch (Exception e) {
            log.error("[실적공시] 수집 실패: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "수집 실패: " + e.getMessage()));
        }
    }
}
