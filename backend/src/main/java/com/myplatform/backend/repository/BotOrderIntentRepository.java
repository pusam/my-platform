package com.myplatform.backend.repository;

import com.myplatform.backend.entity.BotOrderIntent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface BotOrderIntentRepository extends JpaRepository<BotOrderIntent, Long> {

    Optional<BotOrderIntent> findByStockCodeAndSideAndTradeDateAndReason(
            String stockCode, String side, LocalDate tradeDate, String reason);
}
