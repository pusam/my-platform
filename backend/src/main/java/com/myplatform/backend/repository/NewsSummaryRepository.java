package com.myplatform.backend.repository;

import com.myplatform.backend.entity.NewsSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NewsSummaryRepository extends JpaRepository<NewsSummary, Long> {

    // 오늘 요약된 뉴스 조회
    @Query("SELECT n FROM NewsSummary n WHERE n.summarizedAt >= :startOfDay ORDER BY n.summarizedAt DESC")
    List<NewsSummary> findTodayNews(@Param("startOfDay") LocalDateTime startOfDay);

    // 최근 N개 뉴스 조회
    List<NewsSummary> findTop10ByOrderBySummarizedAtDesc();

    // 특정 날짜 범위 뉴스 조회
    List<NewsSummary> findBySummarizedAtBetweenOrderBySummarizedAtDesc(
            LocalDateTime start, LocalDateTime end);

    // 중복 체크 (같은 제목의 뉴스가 오늘 이미 있는지)
    @Query("SELECT COUNT(n) > 0 FROM NewsSummary n WHERE n.title = :title AND n.summarizedAt >= :startOfDay")
    boolean existsByTitleToday(@Param("title") String title, @Param("startOfDay") LocalDateTime startOfDay);

    // URL 기준 중복 체크 (언론사 제목 수정 대응)
    boolean existsBySourceUrl(String sourceUrl);
}
