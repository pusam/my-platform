package com.myplatform.backend.controller;

import com.myplatform.backend.dto.MarketTimingDto;
import com.myplatform.backend.service.MarketTimingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 시장 타이밍 API 컨트롤러
 * - ADR (등락비율) 기반 시장 상태 분석
 * - 시장 데이터 수집
 */
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "시장 지표", description = "ADR 기반 시장 타이밍 분석 API")
public class MarketTimingController {

    private final MarketTimingService marketTimingService;

    /**
     * 현재 시장 상태 조회 (대시보드용)
     */
    @GetMapping("/status")
    @Operation(
        summary = "시장 상태 조회",
        description = "현재 시장의 ADR(등락비율)을 기반으로 시장 상태를 분석합니다.\n\n" +
                     "**ADR 해석:**\n" +
                     "- ADR >= 120: 과열 (현금 확보 필요)\n" +
                     "- 80 < ADR < 120: 보통 (정상 범위)\n" +
                     "- ADR <= 80: 침체 (저점 매수 기회)\n" +
                     "- ADR <= 60: 극심한 공포 (적극 매수 검토)"
    )
    public ResponseEntity<Map<String, Object>> getMarketStatus() {
        log.info("시장 상태 조회 API 호출");

        Map<String, Object> response = new HashMap<>();
        try {
            MarketTimingDto timing = marketTimingService.getCurrentMarketTiming();

            response.put("success", true);
            response.put("data", timing);
            response.put("message", "시장 상태 조회 완료");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("시장 상태 조회 오류: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "시장 상태 조회 실패: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * ADR 히스토리 조회 (차트용)
     */
    @GetMapping("/adr/history")
    @Operation(
        summary = "ADR 히스토리 조회",
        description = "과거 N일간의 ADR 추이를 조회합니다. 차트 표시용."
    )
    public ResponseEntity<Map<String, Object>> getAdrHistory(
            @Parameter(description = "조회할 일수 (기본 60일)")
            @RequestParam(defaultValue = "60") int days) {

        log.info("ADR 히스토리 조회 API 호출: {}일", days);

        Map<String, Object> response = new HashMap<>();
        try {
            List<MarketTimingDto.AdrHistoryDto> history = marketTimingService.getAdrHistory(days);

            response.put("success", true);
            response.put("data", history);
            response.put("count", history.size());
            response.put("message", "ADR 히스토리 조회 완료");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("ADR 히스토리 조회 오류: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "ADR 히스토리 조회 실패: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 시장 데이터 수집 (관리자용)
     */
    @PostMapping("/collect")
    @Operation(
        summary = "시장 데이터 수집",
        description = "네이버 금융에서 오늘의 코스피/코스닥 상승·하락·보합 종목 수를 수집합니다."
    )
    public ResponseEntity<Map<String, Object>> collectMarketData() {
        log.info("시장 데이터 수집 API 호출");

        Map<String, Object> response = new HashMap<>();
        try {
            marketTimingService.collectMarketData();

            // 수집 후 최신 상태 반환
            MarketTimingDto timing = marketTimingService.getCurrentMarketTiming();

            response.put("success", true);
            response.put("data", timing);
            response.put("message", "시장 데이터 수집 완료");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("시장 데이터 수집 오류: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "시장 데이터 수집 실패: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 기간별 시장 데이터 수집 (Backfill)
     */
    @PostMapping("/collect/period")
    @Operation(
        summary = "기간별 시장 데이터 수집",
        description = "특정 기간의 시장 데이터를 수집합니다 (Backfill).\n\n" +
                     "**주의사항:**\n" +
                     "- 네이버 금융 차단 방지를 위해 요청 간 1초 딜레이 적용\n" +
                     "- 주말(토/일)은 자동으로 스킵\n" +
                     "- 과거 데이터의 경우 지수 정보만 수집 가능 (상승/하락 종목 수는 제한적)"
    )
    public ResponseEntity<Map<String, Object>> collectMarketDataForPeriod(
            @Parameter(description = "시작 날짜 (yyyy-MM-dd)", example = "2024-01-01")
            @RequestParam String startDate,
            @Parameter(description = "종료 날짜 (yyyy-MM-dd)", example = "2024-01-31")
            @RequestParam String endDate) {

        log.info("기간별 시장 데이터 수집 API 호출: {} ~ {}", startDate, endDate);

        Map<String, Object> response = new HashMap<>();
        try {
            LocalDate start = LocalDate.parse(startDate, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate end = LocalDate.parse(endDate, DateTimeFormatter.ISO_LOCAL_DATE);

            // 유효성 검증
            if (start.isAfter(end)) {
                response.put("success", false);
                response.put("message", "시작 날짜가 종료 날짜보다 늦습니다.");
                return ResponseEntity.badRequest().body(response);
            }

            if (end.isAfter(LocalDate.now())) {
                end = LocalDate.now();
            }

            // 수집 실행
            Map<String, Object> result = marketTimingService.collectMarketDataForPeriod(start, end);

            response.put("success", true);
            response.put("data", result);
            response.put("message", String.format("기간별 수집 완료 (성공: %d, 실패: %d, 스킵: %d)",
                    result.get("successCount"), result.get("failCount"), result.get("skipCount")));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("기간별 시장 데이터 수집 오류: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "기간별 수집 실패: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 간단 시장 상태 조회 (위젯용)
     */
    @GetMapping("/status/simple")
    @Operation(
        summary = "간단 시장 상태 조회",
        description = "대시보드 위젯용 간단한 시장 상태 정보"
    )
    public ResponseEntity<Map<String, Object>> getSimpleStatus() {
        log.info("간단 시장 상태 조회 API 호출");

        Map<String, Object> response = new HashMap<>();
        try {
            MarketTimingDto timing = marketTimingService.getCurrentMarketTiming();

            // 간단 정보만 추출
            Map<String, Object> simpleData = new HashMap<>();
            simpleData.put("analysisDate", timing.getAnalysisDate());
            simpleData.put("combinedAdr", timing.getCombinedAdr());
            simpleData.put("condition", timing.getOverallCondition());
            simpleData.put("conditionEmoji", timing.getOverallCondition() != null ?
                    timing.getOverallCondition().getEmoji() : "❓ 데이터 없음");
            simpleData.put("diagnosis", timing.getDiagnosis());

            response.put("success", true);
            response.put("data", simpleData);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("간단 시장 상태 조회 오류: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
