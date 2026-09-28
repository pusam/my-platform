package com.myplatform.backend.youtubeopinion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface YtAnalysisRunRepository extends JpaRepository<YtAnalysisRun, Long> {

    List<YtAnalysisRun> findByStatus(YtAnalysisRun.Status status);

    boolean existsByStatus(YtAnalysisRun.Status status);

    List<YtAnalysisRun> findTop20ByVideoIdOrderByStartedAtDesc(String videoId);

    /** 오늘 계획된 Gemini 호출 수(실행별 청크 수 합) — 일일 상한 판정. 실패 실행도 센다(보수적). */
    @Query("SELECT COALESCE(SUM(r.chunkCount), 0) FROM YtAnalysisRun r WHERE r.startedAt >= :since")
    long sumChunksSince(@Param("since") LocalDateTime since);
}
