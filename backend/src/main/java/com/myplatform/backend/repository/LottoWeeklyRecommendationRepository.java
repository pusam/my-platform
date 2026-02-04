package com.myplatform.backend.repository;

import com.myplatform.backend.entity.LottoWeeklyRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface LottoWeeklyRecommendationRepository extends JpaRepository<LottoWeeklyRecommendation, Long> {

    Optional<LottoWeeklyRecommendation> findByGeneratedDate(LocalDate generatedDate);

    @Query("SELECT r FROM LottoWeeklyRecommendation r ORDER BY r.generatedDate DESC LIMIT 1")
    Optional<LottoWeeklyRecommendation> findLatestRecommendation();

    Optional<LottoWeeklyRecommendation> findByTargetDrawNo(Integer targetDrawNo);

    boolean existsByGeneratedDate(LocalDate generatedDate);
}
