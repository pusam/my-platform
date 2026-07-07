package com.myplatform.backend.repository;

import com.myplatform.backend.entity.BotSellInflight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * SELL in-flight 마커 저장소 (P3-1 B안).
 *
 * <p>쓰기 메서드는 리포지토리 레벨 @Transactional — 호출측(RealTradeService.sell)이 NOT_SUPPORTED
 * (tx 밖)라 각 연산이 독립 커밋되고, 서비스(BotSellInflightService)가 DB 예외를 잡아 fail-open 으로
 * 변환할 때 rollback-only 오염이 서비스 반환을 깨지 않는다.
 */
public interface BotSellInflightRepository extends JpaRepository<BotSellInflight, Long> {

    Optional<BotSellInflight> findByStockCodeAndTradingMode(String stockCode, String tradingMode);

    /**
     * 만료 행 재획득 — 조건부 UPDATE(expires_at <= now)로 동시 재획득 경쟁을 DB 레벨에서 판정
     * (bot_config trip 의 조건부 UPDATE 선례). rowsAffected==1 인 쪽만 획득.
     */
    @Transactional
    @Modifying
    @Query("UPDATE BotSellInflight b SET b.acquiredAt = :now, b.expiresAt = :expiresAt, b.holder = :holder "
            + "WHERE b.stockCode = :stockCode AND b.tradingMode = :tradingMode AND b.expiresAt <= :now")
    int reacquireExpired(@Param("stockCode") String stockCode,
                         @Param("tradingMode") String tradingMode,
                         @Param("now") LocalDateTime now,
                         @Param("expiresAt") LocalDateTime expiresAt,
                         @Param("holder") String holder);

    @Transactional
    void deleteByStockCodeAndTradingMode(String stockCode, String tradingMode);
}
