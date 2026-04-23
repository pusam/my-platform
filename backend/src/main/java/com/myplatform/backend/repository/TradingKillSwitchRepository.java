package com.myplatform.backend.repository;

import com.myplatform.backend.entity.TradingKillSwitch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TradingKillSwitchRepository extends JpaRepository<TradingKillSwitch, Long> {
    Optional<TradingKillSwitch> findFirstByOrderByCreatedAtDesc();
}
