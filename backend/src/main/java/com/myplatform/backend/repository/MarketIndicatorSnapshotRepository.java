package com.myplatform.backend.repository;

import com.myplatform.backend.entity.MarketIndicatorSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface MarketIndicatorSnapshotRepository extends JpaRepository<MarketIndicatorSnapshot, Long> {

    Optional<MarketIndicatorSnapshot> findByIndicatorTypeAndSnapshotDate(String indicatorType, LocalDate snapshotDate);

    @Query("SELECT m FROM MarketIndicatorSnapshot m WHERE m.indicatorType = :indicatorType ORDER BY m.snapshotDate DESC LIMIT 1")
    Optional<MarketIndicatorSnapshot> findLatestByIndicatorType(String indicatorType);

    boolean existsByIndicatorTypeAndSnapshotDate(String indicatorType, LocalDate snapshotDate);

    void deleteBySnapshotDateBefore(LocalDate date);
}
