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

    @Modifying
    @Query("DELETE FROM BotTradingPosition p WHERE p.strategy = :strategy AND p.stockCode = :stockCode")
    int deleteByStrategyAndStockCode(@Param("strategy") Strategy strategy, @Param("stockCode") String stockCode);
}
