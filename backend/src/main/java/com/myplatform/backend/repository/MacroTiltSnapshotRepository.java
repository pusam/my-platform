package com.myplatform.backend.repository;

import com.myplatform.backend.entity.MacroTiltSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 매크로 tilt 일일 스냅샷(P3-7) — UPSERT 조회 + SOX 추세용 최근 N행. */
public interface MacroTiltSnapshotRepository extends JpaRepository<MacroTiltSnapshot, Long> {

    /** UPSERT 조회 (일 1행 — findBy...orElseGet(new) 패턴, V37 선례). */
    Optional<MacroTiltSnapshot> findBySnapshotDate(java.time.LocalDate snapshotDate);

    /** SOX 추세 산출용 — 최근 스냅샷(최신순). ~5거래일 전 sox_level 과 비교. */
    List<MacroTiltSnapshot> findTop8ByOrderBySnapshotDateDesc();
}
