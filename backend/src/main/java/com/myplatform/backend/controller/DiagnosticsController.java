package com.myplatform.backend.controller;

import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.service.PriceScalingDiagnosticService;
import com.myplatform.backend.service.RecommendationService;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 시스템 검증 / 운영 진단 — phase 35b.
 *
 * <p>운영자가 "데이터가 누적되고 있는지 / cron 이 도는지 / 평가 batch 가 작동하는지" 즉시 확인하는
 * 용도. 종목별 매매 정보가 아니라 시스템 상태 메타데이터만 노출하므로 permitAll.
 */
@RestController
@RequestMapping("/api/diagnostics")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "운영 진단", description = "데이터 누적 / cron / 평가 batch 상태")
public class DiagnosticsController {

    private final RecommendationSnapshotRepository snapshotRepo;
    private final SignalOutcomeRepository outcomeRepo;
    private final RecommendationService recommendationService;
    private final PriceScalingDiagnosticService priceScalingDiagnosticService;
    private final com.myplatform.backend.service.PythonBackendHealthTracker pythonBackendHealthTracker;
    private final com.myplatform.backend.service.EarningSurpriseService earningSurpriseService;

    @GetMapping("/python-health")
    @Operation(
        summary = "python-backend 호출 헬스",
        description = "regime / chart-timing / chart-sector 소스별 성공·실패·연속실패·가용여부. " +
                     "best-effort 클라이언트가 조용히 죽는지 가시화(차트 베타 빈 게 '신호 없음'인지 '다운'인지 구분)."
    )
    public ResponseEntity<Map<String, Object>> pythonHealth() {
        return ResponseEntity.ok(pythonBackendHealthTracker.snapshot());
    }

    @GetMapping("/data")
    @Operation(
        summary = "데이터 누적 상태 진단",
        description = "recommendation_snapshot / signal_outcome 카운트와 마지막 날짜, 시그널 타입별 분포 + " +
                     "메모리 캐시(phase 36b) 점수 분포. refresh=true 면 캐시 무효화 + 백그라운드 " +
                     "calculate 트리거 (응답엔 직전 캐시, 1~2분 후 재호출 시 fresh)."
    )
    public ResponseEntity<Map<String, Object>> data(
            @RequestParam(defaultValue = "false") boolean refresh) {
        if (refresh) {
            try {
                recommendationService.warmCache();
                log.info("[Diagnostics] refresh=true — warmCache 트리거됨");
            } catch (Exception e) {
                log.warn("[Diagnostics] warmCache 실패: {}", e.getMessage());
            }
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since24h = now.minusHours(24);

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("totalCount", snapshotRepo.count());
        snap.put("latestSnapshotAt", snapshotRepo.findMaxSnapshotAt().orElse(null));
        snap.put("countLast24h", snapshotRepo.countSince(since24h));
        snap.put("strongBuyCountLast24h", snapshotRepo.countStrongBuySince(since24h));
        snap.put("buyPlusCountLast24h", snapshotRepo.countBuyPlusSince(since24h));

        // 점수 분포 — 산식이 빡센지 시장이 약한지 판별 (phase 35c)
        Object[] stats = null;
        try {
            Object raw = snapshotRepo.scoreStatsSince(since24h);
            // JPA 가 단일 row aggregate 를 Object[] 또는 Object[][1] 로 반환할 수 있어 양쪽 처리
            if (raw instanceof Object[] arr) {
                if (arr.length > 0 && arr[0] instanceof Object[] inner) stats = inner;
                else stats = arr;
            }
        } catch (Exception e) {
            log.debug("[Diagnostics] scoreStats 조회 실패: {}", e.getMessage());
        }
        Map<String, Object> scoreDist = new LinkedHashMap<>();
        if (stats != null && stats.length >= 3) {
            scoreDist.put("min", stats[0]);
            scoreDist.put("max", stats[1]);
            scoreDist.put("avg", stats[2]);
        } else {
            scoreDist.put("min", null);
            scoreDist.put("max", null);
            scoreDist.put("avg", null);
        }
        snap.put("scoreDistributionLast24h", scoreDist);

        Map<String, Long> byType = new LinkedHashMap<>();
        for (Object[] row : outcomeRepo.countByType()) {
            byType.put((String) row[0], ((Number) row[1]).longValue());
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("totalCount", outcomeRepo.count());
        outcome.put("evaluatedCount", outcomeRepo.countEvaluated());
        outcome.put("latestSignalDate", outcomeRepo.findMaxSignalDate().orElse(null));
        outcome.put("byType", byType);

        // phase 36b — 메모리 캐시 점수 분포 (DB 스냅샷과 별개, calculate() 후 즉시 갱신)
        Map<String, Object> cache;
        try {
            cache = recommendationService.getCacheDiagnostics();
        } catch (Exception e) {
            log.debug("[Diagnostics] getCacheDiagnostics 실패: {}", e.getMessage());
            cache = Map.of("error", e.getMessage());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recommendationSnapshot", snap);
        data.put("recommendationCache", cache);
        data.put("signalOutcome", outcome);
        data.put("now", now);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/earnings-source")
    @Operation(
        summary = "실적(earnings) 입력 소스 진단 — R1 전환 판단용",
        description = "분기 원본 테이블 수집 커버리지 + (compare=true 면) 레거시/분기 두 경로의 " +
                     "서프라이즈 건수 비교. 종목 코드는 노출하지 않는다(집계값만). " +
                     "recommendation.earnings.quarterly-source 플래그를 켤지 판단하는 근거."
    )
    public ResponseEntity<Map<String, Object>> earningsSource(
            @RequestParam(defaultValue = "false") boolean compare) {
        return ResponseEntity.ok(earningSurpriseService.diagnostics(compare));
    }

    @GetMapping("/price-scaling")
    @Operation(
        summary = "P0-2 ×10 가격 스케일 이상치 진단·분류 (읽기 전용)",
        description = "stock_price 이력에서 정상가(median) 대비 ×N(기본 5배+)으로 튄 행을 찾아 두 가설" +
                     "(BATCH_SCALED=응답 자체 ×N / CURRENT_FIELD_OUTLIER=현재가 필드 매핑)으로 분류하고, " +
                     "market·세션시간대(NXT 단독 구간 08~09·15:40~20:00)별로 군집을 집계한다. 가격 보정 없음."
    )
    public ResponseEntity<Map<String, Object>> priceScaling(
            @RequestParam(defaultValue = "720") int hoursBack,
            @RequestParam(defaultValue = "200") int maxEvents) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", priceScalingDiagnosticService.scan(hoursBack, maxEvents));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/stale-feeds")
    @Operation(
        summary = "P2-11 스테일/동결 피드 + 손상 등락률 진단 (읽기 전용)",
        description = "stock_price 이력에서 정규장(09:00~15:30) 현재가가 tickThreshold 틱 이상 연속 동일(동결)인 " +
                     "종목을 찾아 활성/정지 교차로 분류(ACTIVE_FROZEN=이상 / SUSPENDED_FROZEN=정상)하고, " +
                     "등락률 ±31% 초과(손상 의심) 행을 함께 보고한다. 가격 보정 없음 — 관측 전용."
    )
    public ResponseEntity<Map<String, Object>> staleFeeds(
            @RequestParam(defaultValue = "720") int hoursBack,
            @RequestParam(defaultValue = "20") int tickThreshold,
            @RequestParam(defaultValue = "200") int maxEvents) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", priceScalingDiagnosticService.scanStaleFeeds(hoursBack, tickThreshold, maxEvents));
        return ResponseEntity.ok(response);
    }
}
