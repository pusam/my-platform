package com.myplatform.backend.crosscheck;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface ExternalCrosscheckRunRepository extends JpaRepository<ExternalCrosscheckRun, Long> {

    /** 최근 실행(새 것부터) — 관제실 규칙·조회 API. */
    @Transactional(readOnly = true)
    List<ExternalCrosscheckRun> findTop10ByOrderByStartedAtDesc();

    /** 수동 실행 하루 횟수 — DB 로 센다(재시작해도 줄지 않는다). */
    @Transactional(readOnly = true)
    long countByRunDateAndTriggerType(LocalDate runDate, String triggerType);

    /** 끝나지 못하고 남은 실행 — 다음 실행이 시작할 때 INTERRUPTED 로 마감한다. */
    @Transactional(readOnly = true)
    List<ExternalCrosscheckRun> findByStatusAndStartedAtBefore(String status, java.time.LocalDateTime before);

    /** 보존 기간이 지난 실행의 종목 행부터 — FK CASCADE 가 없는 H2 에서도 같은 결과가 되게 직접 지운다. */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM external_crosscheck_row WHERE run_id IN "
            + "(SELECT id FROM external_crosscheck_run WHERE run_date < :cutoff)", nativeQuery = true)
    int deleteRowsOfRunsBefore(@Param("cutoff") LocalDate cutoff);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM external_crosscheck_run WHERE run_date < :cutoff", nativeQuery = true)
    int deleteRunsBefore(@Param("cutoff") LocalDate cutoff);
}
