package com.myplatform.backend.repository;

import com.myplatform.backend.entity.StockWatchlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockWatchlistRepository extends JpaRepository<StockWatchlist, Long> {

    List<StockWatchlist> findByUsernameOrderByCreatedAtDesc(String username);

    Optional<StockWatchlist> findByUsernameAndStockCode(String username, String stockCode);

    boolean existsByUsernameAndStockCode(String username, String stockCode);

    List<StockWatchlist> findByIsActiveAndAlertTriggeredAndTargetPriceIsNotNull(
            Boolean isActive, Boolean alertTriggered);
}
