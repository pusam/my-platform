package com.myplatform.backend.controller;

import com.myplatform.backend.dto.SignalAccuracyDto;
import com.myplatform.backend.dto.SignalCompareDto;
import com.myplatform.backend.dto.SignalTimeseriesDto;
import com.myplatform.backend.service.SignalOutcomeService;
import com.myplatform.backend.service.SignalWeeklyReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/signal-outcomes")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "시그널 적중률", description = "각 시그널의 N일 후 가격 변동 기반 적중률 통계")
public class SignalOutcomeController {

    private final SignalOutcomeService signalOutcomeService;
    private final SignalWeeklyReportService signalWeeklyReportService;

    @GetMapping("/accuracy")
    @Operation(
        summary = "시그널별 적중률 통계",
        description = "최근 N일 발생 시그널의 3일 후 가격 변동 평가 결과를 집계. " +
                     "+3% 이상이면 hit. 시그널 종류별 총 발생 수 / 적중 수 / 적중률 / 평균 변동률."
    )
    public ResponseEntity<Map<String, Object>> getAccuracy(
            @RequestParam(defaultValue = "30") int days) {
        Map<String, Object> response = new HashMap<>();
        try {
            SignalAccuracyDto accuracy = signalOutcomeService.getAccuracy(days);
            response.put("success", true);
            response.put("data", accuracy);
            response.put("timestamp", LocalDateTime.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[SignalOutcome API] 적중률 조회 실패: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "적중률 조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/accuracy-by-band")
    @Operation(
        summary = "조건부 적중률 — 보드 종합점수(STRONG_BUY/BUY) 격리, 점수 구간/카테고리/재료/국면별 (V30~V32)",
        description = "최근 N일(기본 90) 평가 완료 시그널 중 보드 종합점수(STRONG_BUY/BUY)만 격리하고 " +
                     "phase-38 컷오프(2026-06-25, anti-추격 튜닝 완료) 이후분만 집계 — 현재 산식 점수의 예측력만 측정. " +
                     "다른 시그널 소스(AI_*/COMPOSITE_*/SURGE_*)는 점수 스케일이 달라 제외. 응답 since=실제 시작일. " +
                     "(1) signalScore 구간(55~64/65~74/75~84/85~100)별, (2) 카테고리 강세 표본별, " +
                     "(3) 재료 방향별, (4) 시장 국면(상승장/하락장/횡보장)별 집계. " +
                     "'75점과 90점이 다른가', '수급 주도 vs 기술 주도', '재료 유무', '하락장에서도 먹히나' 검증용. " +
                     "각 차원은 해당 스냅샷 컬럼(V30/V31/V32) 누적분만 포함 (NULL=미수집 제외)."
    )
    public ResponseEntity<Map<String, Object>> accuracyByBand(
            @RequestParam(defaultValue = "90") int days) {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("data", signalOutcomeService.getAccuracyByBand(days));
            response.put("timestamp", LocalDateTime.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[SignalOutcome API] 조건부 적중률 조회 실패: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "조건부 적중률 조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/timeseries")
    @Operation(
        summary = "시그널 일별 시계열 (phase 33)",
        description = "최근 N일(기본 60) 시그널별 일자별 hit-rate / 평균 변동률 / 평균 alpha. " +
                     "phase 변경 시점 전후 그래프 표시용. signalType 미지정 시 전체 시그널."
    )
    public ResponseEntity<Map<String, Object>> timeseries(
            @RequestParam(required = false) String signalType,
            @RequestParam(defaultValue = "60") int days) {
        Map<String, Object> response = new HashMap<>();
        try {
            SignalTimeseriesDto ts = signalOutcomeService.getTimeseries(signalType, days);
            response.put("success", true);
            response.put("data", ts);
            response.put("timestamp", LocalDateTime.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[SignalOutcome API] 시계열 조회 실패: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "시계열 조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/compare")
    @Operation(
        summary = "cutoff 전후 적중률 비교 (phase 31 검증용)",
        description = "특정 시점(cutoff) 기준 [cutoff - windowDays, cutoff) vs " +
                     "[cutoff, cutoff + windowDays) 구간의 시그널별 hit-rate / alpha / MFE / MAE 비교. " +
                     "phase 31 추격매수 방지 산식 변경이 실제로 alpha 를 개선했는지 검증하는 용도. " +
                     "signalType 미지정 시 전체. 표본 < 3 이면 sufficientSample=false 로 표시."
    )
    public ResponseEntity<Map<String, Object>> compare(
            @RequestParam(required = false) String signalType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cutoff,
            @RequestParam(defaultValue = "30") int windowDays) {
        Map<String, Object> response = new HashMap<>();
        try {
            SignalCompareDto compare = signalOutcomeService.compareAroundCutoff(
                    signalType, cutoff, windowDays);
            response.put("success", true);
            response.put("data", compare);
            response.put("timestamp", LocalDateTime.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[SignalOutcome API] 비교 조회 실패: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "비교 조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/weekly-report")
    @Operation(
        summary = "주간 시그널 예측력 측정 — 최신 스냅샷 (P1-6 상설화)",
        description = "매주 일요일 배치가 저장한 최신 주간 측정 리포트. 카테고리 × regime(BULL/BEAR/SIDEWAYS/" +
                     "UNKNOWN) × 밴드 적중률/평균 alpha/표본수 + 이번 주 vs 누적 추세 + 경고. " +
                     "표본 n<10 셀은 insufficientSample=true 로 표기(숨기지 않음). 아직 생성 전이면 data=null."
    )
    public ResponseEntity<Map<String, Object>> weeklyReport() {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("data", signalWeeklyReportService.getLatestReport().orElse(null));
            response.put("timestamp", LocalDateTime.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[SignalOutcome API] 주간 리포트 조회 실패: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "주간 리포트 조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/weekly-report/history")
    @Operation(
        summary = "주간 예측력 측정 — 최근 12주 히스토리 (추세)",
        description = "주차별 헤드라인 스냅샷(week_start/weekly_n/cumulative_n/supply_inverted). " +
                     "시간에 따른 예측력 변화·수급 역상관 지속 주차 추적용. 상세 크로스탭은 각 주 report_json."
    )
    public ResponseEntity<Map<String, Object>> weeklyReportHistory() {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("data", signalWeeklyReportService.getHistory());
            response.put("timestamp", LocalDateTime.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[SignalOutcome API] 주간 히스토리 조회 실패: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "주간 히스토리 조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
