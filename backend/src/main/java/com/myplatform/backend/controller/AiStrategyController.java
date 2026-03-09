package com.myplatform.backend.controller;

import com.myplatform.backend.dto.AiStrategySnapshotDto;
import com.myplatform.backend.dto.BacktestDto;
import com.myplatform.backend.entity.AiStrategySnapshot.StrategyType;
import com.myplatform.backend.service.AiStrategySnapshotService;
import com.myplatform.backend.service.BacktestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI 투자 전략 스냅샷 API
 *
 * [핵심 원칙]
 * - 모든 API는 DB 조회만 수행
 * - 외부 API(KIS 등) 호출 없음
 * - 100번 새로고침 = 0번 외부 API 호출
 *
 * [데이터 갱신]
 * - 스케줄러가 장중 자동 수집
 * - SCALPING: 2분 간격
 * - SWING, TURNAROUND, VALUE: 30분 간격
 */
@RestController
@RequestMapping("/api/ai-strategy")
@RequiredArgsConstructor
@Slf4j
public class AiStrategyController {

    private final AiStrategySnapshotService snapshotService;
    private final BacktestService backtestService;

    /**
     * 모든 전략의 최신 스냅샷 조회
     *
     * GET /api/ai-strategy/latest
     *
     * Response:
     * {
     *   "strategies": {
     *     "SCALPING": [...],
     *     "SWING": [...],
     *     "TURNAROUND": [...],
     *     "VALUE": [...]
     *   },
     *   "lastUpdated": {
     *     "SCALPING": "2024-01-15T10:30:00",
     *     "SWING": "2024-01-15T10:00:00",
     *     ...
     *   },
     *   "responseTime": "2024-01-15T10:35:12"
     * }
     */
    @GetMapping("/latest")
    public ResponseEntity<AiStrategySnapshotDto.AllStrategiesResponse> getLatestSnapshots() {
        log.debug("[AI Strategy API] /latest 요청");
        AiStrategySnapshotDto.AllStrategiesResponse response = snapshotService.getAllLatestSnapshots();
        return ResponseEntity.ok(response);
    }

    /**
     * 특정 전략의 최신 스냅샷 조회
     *
     * GET /api/ai-strategy/latest/{strategyType}
     *
     * @param strategyType SCALPING | SWING | TURNAROUND | VALUE
     */
    @GetMapping("/latest/{strategyType}")
    public ResponseEntity<List<AiStrategySnapshotDto>> getLatestByStrategy(
            @PathVariable String strategyType) {
        log.debug("[AI Strategy API] /latest/{} 요청", strategyType);

        try {
            StrategyType type = StrategyType.valueOf(strategyType.toUpperCase());
            List<AiStrategySnapshotDto> snapshots = snapshotService.getLatestByStrategy(type);
            return ResponseEntity.ok(snapshots);
        } catch (IllegalArgumentException e) {
            log.warn("[AI Strategy API] 잘못된 전략 유형: {}", strategyType);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 스냅샷 통계 조회 (관리용)
     *
     * GET /api/ai-strategy/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = snapshotService.getSnapshotStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * AI 전략 성과 분석 (백테스트)
     *
     * GET /api/ai-strategy/performance?days=30
     */
    @GetMapping("/performance")
    public ResponseEntity<BacktestDto.PerformanceResponse> getPerformance(
            @RequestParam(defaultValue = "30") int days) {
        log.debug("[AI Strategy API] /performance 요청 ({}일)", days);
        BacktestDto.PerformanceResponse response = backtestService.getPerformance(days);
        return ResponseEntity.ok(response);
    }

    /**
     * 수동 스냅샷 수집 (테스트/관리용)
     *
     * POST /api/ai-strategy/collect
     *
     * Response:
     * {
     *   "SCALPING": 5,
     *   "SWING": 5,
     *   "TURNAROUND": 5,
     *   "VALUE": 5
     * }
     */
    @PostMapping("/collect")
    public ResponseEntity<Map<String, Integer>> collectSnapshots() {
        log.info("[AI Strategy API] 수동 스냅샷 수집 시작");
        Map<String, Integer> result = snapshotService.collectAllSnapshotsManually();
        log.info("[AI Strategy API] 수동 스냅샷 수집 완료: {}", result);
        return ResponseEntity.ok(result);
    }

    /**
     * 특정 전략 수동 스냅샷 수집
     *
     * POST /api/ai-strategy/collect/{strategyType}
     */
    @PostMapping("/collect/{strategyType}")
    public ResponseEntity<String> collectByStrategy(@PathVariable String strategyType) {
        log.info("[AI Strategy API] 수동 스냅샷 수집 시작: {}", strategyType);

        try {
            StrategyType type = StrategyType.valueOf(strategyType.toUpperCase());
            snapshotService.collectAndSaveSnapshot(type);
            return ResponseEntity.ok("수집 완료: " + type);
        } catch (IllegalArgumentException e) {
            log.warn("[AI Strategy API] 잘못된 전략 유형: {}", strategyType);
            return ResponseEntity.badRequest().body("잘못된 전략 유형: " + strategyType);
        }
    }
}
