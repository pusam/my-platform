package com.myplatform.backend.repository;

import com.myplatform.backend.entity.FinanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FinanceRecordRepository extends JpaRepository<FinanceRecord, Long> {

    List<FinanceRecord> findByUsernameOrderByYearDescMonthDesc(String username);

    Optional<FinanceRecord> findByUsernameAndYearAndMonth(String username, Integer year, Integer month);

    void deleteByUsernameAndId(String username, Long id);
}
