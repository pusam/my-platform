package com.myplatform.backend.repository;

import com.myplatform.backend.entity.TradingAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * INSERT 와 SELECT 만 사용. UPDATE/DELETE 는 Repository 메서드로 노출하지 않는다.
 */
public interface TradingAuditLogRepository extends JpaRepository<TradingAuditLog, Long> {

    /**
     * 오늘(00:00 KST) 이후 성공한 매수 누적 금액. 일일 한도 체크용.
     */
    @Query("""
        SELECT COALESCE(SUM(t.totalAmount), 0)
          FROM TradingAuditLog t
         WHERE t.action = com.myplatform.backend.entity.TradingAuditLog.Action.BUY
           AND t.mode   = com.myplatform.backend.entity.TradingAuditLog.Mode.REAL
           AND t.success = TRUE
           AND t.createdAt >= :since
        """)
    BigDecimal sumSuccessfulRealBuyAmountSince(@Param("since") LocalDateTime since);
}
