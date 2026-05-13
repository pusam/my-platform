package com.myplatform.backend.repository;

import com.myplatform.backend.entity.SignalOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SignalOutcomeRepository extends JpaRepository<SignalOutcome, Long> {

    /** 같은 날 같은 시그널/종목 중복 INSERT 방지용 lookup. */
    @Query("SELECT s FROM SignalOutcome s WHERE s.signalType = :type AND s.stockCode = :code AND s.signalDate = :date")
    List<SignalOutcome> findExisting(@Param("type") String signalType,
                                     @Param("code") String stockCode,
                                     @Param("date") LocalDate signalDate);

    /** 평가 대상 — signalDate 가 cutoff 이하이고 아직 평가 안 된 항목. */
    @Query("SELECT s FROM SignalOutcome s WHERE s.signalDate <= :cutoff AND s.evaluatedAt IS NULL")
    List<SignalOutcome> findPendingEvaluation(@Param("cutoff") LocalDate cutoff);

    /** 시그널별 통계 — 지정 기간 내. [signalType, total, hitCount, avgPctChange] */
    @Query("""
        SELECT s.signalType,
               COUNT(s),
               SUM(CASE WHEN s.hit = TRUE THEN 1 ELSE 0 END),
               AVG(s.pctChange3d)
          FROM SignalOutcome s
         WHERE s.evaluatedAt IS NOT NULL
           AND s.signalDate >= :from
         GROUP BY s.signalType
         ORDER BY s.signalType
        """)
    List<Object[]> aggregateStats(@Param("from") LocalDate from);
}
