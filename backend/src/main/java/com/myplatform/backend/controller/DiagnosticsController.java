package com.myplatform.backend.controller;

import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @GetMapping("/data")
    @Operation(
        summary = "데이터 누적 상태 진단",
        description = "recommendation_snapshot / signal_outcome 카운트와 마지막 날짜, 시그널 타입별 분포. " +
                     "검증 API 가 비어있을 때 '데이터가 없는 건지 / 검증 조건 미달인지' 구분용."
    )
    public ResponseEntity<Map<String, Object>> data() {
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

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recommendationSnapshot", snap);
        data.put("signalOutcome", outcome);
        data.put("now", now);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }
}
