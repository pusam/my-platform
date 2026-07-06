package com.myplatform.backend.repository;

import com.myplatform.backend.entity.SignalWeeklyAccuracy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SignalWeeklyAccuracyRepository extends JpaRepository<SignalWeeklyAccuracy, Long> {

    /** 같은 주 재생성 시 UPSERT 대상 lookup. */
    Optional<SignalWeeklyAccuracy> findByWeekStart(LocalDate weekStart);

    /** 최신 스냅샷 — 조회 API 기본. */
    Optional<SignalWeeklyAccuracy> findFirstByOrderByWeekStartDesc();

    /** 최근 N주 히스토리 (스트릭 계산·추세 조회용). weekStart 내림차순(최신 먼저). */
    List<SignalWeeklyAccuracy> findTop12ByOrderByWeekStartDesc();
}
