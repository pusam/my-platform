package com.myplatform.backend.repository;

import com.myplatform.backend.entity.OvernightUsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

/** 간밤 미국장 tilt 일일 스냅샷(P3-5) — UPSERT 조회 (V39 MacroTiltSnapshot 선례). */
public interface OvernightUsSnapshotRepository extends JpaRepository<OvernightUsSnapshot, Long> {

    /** UPSERT 조회 (일 1행 — findBy...orElseGet(new) 패턴). */
    Optional<OvernightUsSnapshot> findBySnapshotDate(LocalDate snapshotDate);
}
