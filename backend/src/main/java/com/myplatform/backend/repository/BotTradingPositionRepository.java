package com.myplatform.backend.repository;

import com.myplatform.backend.entity.BotTradingPosition;
import com.myplatform.backend.entity.BotTradingPosition.Strategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BotTradingPositionRepository extends JpaRepository<BotTradingPosition, Long> {

    List<BotTradingPosition> findByStrategy(Strategy strategy);

    Optional<BotTradingPosition> findByStrategyAndStockCode(Strategy strategy, String stockCode);

    /** 모드별 단일 포지션 조회 — 같은 종목을 VIRTUAL/REAL 양쪽에서 동시 보유 가능하므로 모드 인자 필요. */
    Optional<BotTradingPosition> findByStrategyAndStockCodeAndTradingMode(
            Strategy strategy, String stockCode, String tradingMode);

    /** 시작 복구 시 현재 모드 포지션만 in-memory 로 가져오기 위함. */
    List<BotTradingPosition> findByTradingMode(String tradingMode);

    @Modifying
    @Query("DELETE FROM BotTradingPosition p WHERE p.strategy = :strategy AND p.stockCode = :stockCode")
    int deleteByStrategyAndStockCode(@Param("strategy") Strategy strategy, @Param("stockCode") String stockCode);

    /** 모드 한정 삭제 — 모드 전환 시 옛 모드 포지션만 정리할 때. */
    @Modifying
    @Query("DELETE FROM BotTradingPosition p WHERE p.strategy = :strategy AND p.stockCode = :stockCode AND p.tradingMode = :tradingMode")
    int deleteByStrategyAndStockCodeAndTradingMode(
            @Param("strategy") Strategy strategy,
            @Param("stockCode") String stockCode,
            @Param("tradingMode") String tradingMode);
}
