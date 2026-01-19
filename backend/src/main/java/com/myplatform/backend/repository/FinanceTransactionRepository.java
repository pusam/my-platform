package com.myplatform.backend.repository;

import com.myplatform.backend.entity.FinanceTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FinanceTransactionRepository extends JpaRepository<FinanceTransaction, Long> {

    List<FinanceTransaction> findByUsernameOrderByTransactionDateDesc(String username);

    @Query("SELECT t FROM FinanceTransaction t WHERE t.username = :username " +
           "AND YEAR(t.transactionDate) = :year AND MONTH(t.transactionDate) = :month " +
           "ORDER BY t.transactionDate DESC, t.createdAt DESC")
    List<FinanceTransaction> findByUsernameAndYearMonth(
            @Param("username") String username,
            @Param("year") int year,
            @Param("month") int month
    );

    @Query("SELECT DISTINCT YEAR(t.transactionDate) as year, MONTH(t.transactionDate) as month " +
           "FROM FinanceTransaction t WHERE t.username = :username " +
           "ORDER BY year DESC, month DESC")
    List<Object[]> findDistinctYearMonthByUsername(@Param("username") String username);
}
