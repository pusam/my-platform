package com.myplatform.backend.repository;

import com.myplatform.backend.entity.PatternDetection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface PatternDetectionRepository extends JpaRepository<PatternDetection, Long> {

    /** 쿨다운 dedup — 같은 (종목, 패턴)이 최근에 이미 기록됐는지 (박스 성장 등 연일 재확정 스팸 방지). */
    boolean existsByStockCodeAndPatternTypeAndDetectedDateGreaterThanEqual(
            String stockCode, String patternType, LocalDate since);

    /**
     * 미평가 행 조회 — 오래된 것부터. oldestAllowed 이전 행은 give-up(상폐·정지 등으로 히스토리가
     * 영영 안 쌓이는 행이 큐 머리를 막는 고사 방지 — SignalOutcome EVAL_GIVE_UP 과 동일 패턴).
     */
    List<PatternDetection> findByEvaluatedAtIsNullAndDetectedDateBetweenOrderByDetectedDateAsc(
            LocalDate oldestAllowed, LocalDate latest, Pageable pageable);

    /** give-up 잔량 가시화(§4c 생존편향 신호) — 평가 큐에서 빠진 행 수. */
    long countByEvaluatedAtIsNullAndDetectedDateBefore(LocalDate oldestAllowed);
}
