package com.myplatform.backend.repository;

import com.myplatform.backend.entity.CatalystAlertDedup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface CatalystAlertDedupRepository extends JpaRepository<CatalystAlertDedup, Long> {

    void deleteByAlertKey(String alertKey);

    void deleteByAlertDateBefore(LocalDate cutoff);
}
