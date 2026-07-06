package com.myplatform.backend.controller;

import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 백테스트용 데이터 CSV export (P1-6 측정 전용) — ADMIN 전용(SecurityConfig `/api/admin/**`).
 *
 * <p>운영 DB 에 직접 접근할 수 없는 로컬 백테스트(python `app.backtest.run_composite --snapshot-csv`)에
 * 스냅샷을 공급하는 read-only 경로. <b>라이브 산식/시세경로와 무관</b> — 저장된 값을 그대로 내보낸다.
 *
 * <p>⚠ RecommendationSnapshot 은 <b>7일 보존</b>(deleteOlderThan) — export 커버리지는 최근 1주.
 * 장기 축적이 필요하면 주기적으로 export 를 받아 로컬에 누적할 것(리포트 한계 명시).
 */
@RestController
@RequestMapping("/api/admin/backtest-export")
@RequiredArgsConstructor
@Slf4j
public class BacktestExportAdminController {

    private final RecommendationSnapshotRepository snapshotRepository;

    /**
     * RecommendationSnapshot 구간 CSV — python load_snapshot_csv 스키마와 1:1.
     * 예: GET /api/admin/backtest-export/recommendation-snapshots?from=2026-06-29&to=2026-07-06
     */
    @GetMapping(value = "/recommendation-snapshots", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> exportRecommendationSnapshots(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<RecommendationSnapshot> rows = snapshotRepository
                .findBySnapshotAtBetweenOrderBySnapshotAtAsc(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        StringBuilder sb = new StringBuilder(
                "snapshot_date,stock_code,total_score,earnings,supply_demand,technical,sector_momentum,"
                        + "value_stability,growth,rank_order,change_rate\n");
        for (RecommendationSnapshot r : rows) {
            sb.append(r.getSnapshotAt()).append(',')
              .append(r.getStockCode()).append(',')
              .append(r.getTotalScore()).append(',')
              .append(r.getEarnings()).append(',')
              .append(r.getSupplyDemand()).append(',')
              .append(r.getTechnical()).append(',')
              .append(r.getSectorMomentum()).append(',')
              .append(r.getValueStability()).append(',')
              .append(r.getGrowth()).append(',')
              .append(r.getRankOrder()).append(',')
              .append(r.getChangeRate() != null ? r.getChangeRate().toPlainString() : "").append('\n');
        }
        log.info("[Admin] 스냅샷 CSV export: {}~{} {}행", from, to, rows.size());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(sb.toString());
    }
}
