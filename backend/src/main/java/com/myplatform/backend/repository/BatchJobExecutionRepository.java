package com.myplatform.backend.repository;

import com.myplatform.backend.entity.BatchJobExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BatchJobExecutionRepository extends JpaRepository<BatchJobExecution, Long> {

    Page<BatchJobExecution> findByOrderByStartedAtDesc(Pageable pageable);

    Page<BatchJobExecution> findByJobNameOrderByStartedAtDesc(String jobName, Pageable pageable);

    @Query("SELECT DISTINCT b.jobName FROM BatchJobExecution b ORDER BY b.jobName")
    List<String> findDistinctJobNames();

    long countByStatusAndStartedAtAfter(String status, LocalDateTime after);

    List<BatchJobExecution> findByStatusAndStartedAtAfterOrderByStartedAtDesc(String status, LocalDateTime after);

    @Modifying
    @Query("DELETE FROM BatchJobExecution b WHERE b.createdAt < :before")
    int deleteByCreatedAtBefore(@Param("before") LocalDateTime before);
}
