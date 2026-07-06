package com.myplatform.backend.controller;

import com.myplatform.backend.dto.WeeklySignalAccuracyDto;
import com.myplatform.backend.service.SignalWeeklyReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 주간 예측력 측정 수동 트리거 (P1-6 상설화) — ADMIN 전용(SecurityConfig `/api/admin/**` → hasRole ADMIN).
 * 일요일 18:00 크론과 동일 로직을 즉시 실행(락 없이). 검증/ops 용.
 */
@RestController
@RequestMapping("/api/admin/signal-outcomes")
@RequiredArgsConstructor
@Slf4j
public class SignalWeeklyReportAdminController {

    private final SignalWeeklyReportService signalWeeklyReportService;

    /** 직전 완료 주 예측력 측정 즉시 생성 → 스냅샷 UPSERT + 텔레그램. @return 생성된 리포트. */
    @PostMapping("/weekly-report/run")
    public ResponseEntity<Map<String, Object>> runWeeklyReport() {
        log.info("[Admin] 주간 예측력 측정 수동 트리거");
        WeeklySignalAccuracyDto dto = signalWeeklyReportService.generateWeeklyReport("manual");
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", dto);
        return ResponseEntity.ok(body);
    }
}
