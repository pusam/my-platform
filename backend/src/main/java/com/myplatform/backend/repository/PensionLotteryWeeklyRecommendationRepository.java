package com.myplatform.backend.repository;

import com.myplatform.backend.entity.PensionLotteryWeeklyRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface PensionLotteryWeeklyRecommendationRepository extends JpaRepository<PensionLotteryWeeklyRecommendation, Long> {

    Optional<PensionLotteryWeeklyRecommendation> findByGeneratedDate(LocalDate generatedDate);

    @Query("SELECT r FROM PensionLotteryWeeklyRecommendation r ORDER BY r.generatedDate DESC LIMIT 1")
    Optional<PensionLotteryWeeklyRecommendation> findLatestRecommendation();

    Optional<PensionLotteryWeeklyRecommendation> findByTargetDrawNo(Integer targetDrawNo);

    boolean existsByGeneratedDate(LocalDate generatedDate);
}
