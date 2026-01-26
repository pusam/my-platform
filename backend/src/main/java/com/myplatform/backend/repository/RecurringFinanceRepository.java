package com.myplatform.backend.repository;

import com.myplatform.backend.entity.RecurringFinance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface RecurringFinanceRepository extends JpaRepository<RecurringFinance, Long> {

    // 사용자의 활성화된 고정 수입/지출 목록
    List<RecurringFinance> findByUsernameAndIsActiveTrueOrderByTypeAscCategoryAsc(String username);

    // 사용자의 모든 고정 수입/지출 목록
    List<RecurringFinance> findByUsernameOrderByTypeAscCategoryAsc(String username);

    // 특정 유형의 고정 수입/지출 목록
    List<RecurringFinance> findByUsernameAndTypeAndIsActiveTrueOrderByCategoryAsc(String username, String type);

    // 특정 월에 적용되는 고정 수입/지출 목록
    @Query("SELECT r FROM RecurringFinance r WHERE r.username = :username AND r.isActive = true " +
           "AND r.startDate <= :targetDate " +
           "AND (r.endDate IS NULL OR r.endDate >= :targetDate)")
    List<RecurringFinance> findActiveRecurringForMonth(
            @Param("username") String username,
            @Param("targetDate") LocalDate targetDate);

    // 사용자 소유 확인
    Optional<RecurringFinance> findByIdAndUsername(Long id, String username);
}
