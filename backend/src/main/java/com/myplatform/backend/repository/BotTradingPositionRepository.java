package com.myplatform.backend.repository;

import com.myplatform.backend.entity.BotTradingPosition;
import com.myplatform.backend.entity.BotTradingPosition.Strategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    /** 봇이 현재 보유 중인 종목인지(모드 무관, 행 존재 = 활성 포지션) — 악재 알림 대상 판정용. */
    boolean existsByStockCode(String stockCode);

    // ⚠ @Modifying JPQL 은 활성 트랜잭션 필수 — 호출부(AutoTradingBotService)의 @Transactional 은
    //   self-invocation(프록시 미경유)이라 무효. 여기서 @Transactional 로 자체 트랜잭션을 연다
    //   (BotConfigRepository.tripDailyLossBreaker 와 동일 패턴). 없으면 TransactionRequiredException
    //   이 조용히 삼켜져 매도 후에도 행이 남고, 재시작 복원 시 고스트 포지션으로 부활한다.
    @Modifying
    @Transactional
    @Query("DELETE FROM BotTradingPosition p WHERE p.strategy = :strategy AND p.stockCode = :stockCode")
    int deleteByStrategyAndStockCode(@Param("strategy") Strategy strategy, @Param("stockCode") String stockCode);

    /** 모드 한정 삭제 — 모드 전환 시 옛 모드 포지션만 정리할 때. */
    @Modifying
    @Transactional
    @Query("DELETE FROM BotTradingPosition p WHERE p.strategy = :strategy AND p.stockCode = :stockCode AND p.tradingMode = :tradingMode")
    int deleteByStrategyAndStockCodeAndTradingMode(
            @Param("strategy") Strategy strategy,
            @Param("stockCode") String stockCode,
            @Param("tradingMode") String tradingMode);

    /** 전략 단위 일괄 삭제 — 15:10 스캘핑 청산 완료 시 현재 모드 스캘핑 행 정리(고스트 포함). */
    @Modifying
    @Transactional
    @Query("DELETE FROM BotTradingPosition p WHERE p.strategy = :strategy AND p.tradingMode = :tradingMode")
    int deleteByStrategyAndTradingMode(@Param("strategy") Strategy strategy, @Param("tradingMode") String tradingMode);

    /** 모드 단위 일괄 삭제 — 15:20 강제청산/NXT 재청산 완청산 확인 시 현재 모드 행 전체 정리(고스트 포함). */
    @Modifying
    @Transactional
    @Query("DELETE FROM BotTradingPosition p WHERE p.tradingMode = :tradingMode")
    int deleteByTradingMode(@Param("tradingMode") String tradingMode);
}
