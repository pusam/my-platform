package com.myplatform.backend.repository;

import com.myplatform.backend.entity.WeeklyTradingReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WeeklyTradingReportRepository extends JpaRepository<WeeklyTradingReport, Long> {

    Optional<WeeklyTradingReport> findFirstByWeekStartAndWeekEndAndModeOrderByCreatedAtDesc(
            LocalDate start, LocalDate end, String mode);

    List<WeeklyTradingReport> findTop12ByModeOrderByWeekStartDesc(String mode);

    Optional<WeeklyTradingReport> findFirstByModeOrderByWeekStartDesc(String mode);
}
