package com.myplatform.backend.crosscheck;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ExternalCrosscheckRowRepository extends JpaRepository<ExternalCrosscheckRow, Long> {

    @Transactional(readOnly = true)
    List<ExternalCrosscheckRow> findByRunIdOrderByIdAsc(Long runId);
}
