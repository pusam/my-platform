package com.myplatform.backend.controller;

import com.myplatform.backend.dto.BacktestDto;
import com.myplatform.backend.service.BacktestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 추천 시스템 트랙레코드 노출 — 기존 BacktestService(내부용)를 사용자에게 개방.
 * "이 추천 시스템 자체가 과거에 먹혔나"를 적중률/평균수익/MDD/Sharpe 로 보여준다.
 */
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "백테스트", description = "AI 전략 추천(TOP3 첫 추천)의 과거 N일 회고 성과 — 거래비용 차감 순수익 기준")
public class BacktestController {

    private final BacktestService backtestService;

    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 90;

    @GetMapping("/performance")
    @Operation(
        summary = "전략별 회고 성과",
        description = "최근 N일(기본 30, 최대 90) AI 전략 스냅샷 TOP3 첫 추천 종목의 현재가 대비 성과. " +
                     "수수료(0.015%×2)+세금(0.18%)+전략별 슬리피지 차감 순수익률 기준 " +
                     "적중률 / 평균수익 / MDD / Sharpe / 전략별 분해를 제공."
    )
    public ResponseEntity<Map<String, Object>> performance(
            @RequestParam(defaultValue = "30") int days) {
        Map<String, Object> response = new HashMap<>();
        try {
            int clamped = Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
            BacktestDto.PerformanceResponse perf = backtestService.getPerformance(clamped);
            response.put("success", true);
            response.put("data", perf);
            response.put("timestamp", LocalDateTime.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[Backtest API] 성과 조회 실패: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "백테스트 성과 조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
