package com.myplatform.backend.repository;

import com.myplatform.backend.entity.RecurringFinanceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecurringFinanceHistoryRepository extends JpaRepository<RecurringFinanceHistory, Long> {

    // 특정 고정 수입/지출의 변경 이력 조회
    List<RecurringFinanceHistory> findByRecurringIdOrderByEffectiveDateDesc(Long recurringId);
}
