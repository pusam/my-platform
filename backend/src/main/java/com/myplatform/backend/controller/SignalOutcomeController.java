package com.myplatform.backend.controller;

import com.myplatform.backend.dto.SignalAccuracyDto;
import com.myplatform.backend.dto.SignalCompareDto;
import com.myplatform.backend.service.SignalOutcomeService;
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
}
