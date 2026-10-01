package com.myplatform.backend.youtubeopinion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface YtAnalysisRunRepository extends JpaRepository<YtAnalysisRun, Long> {

    List<YtAnalysisRun> findByStatus(YtAnalysisRun.Status status);

    boolean existsByStatus(YtAnalysisRun.Status status);

    List<YtAnalysisRun> findTop20ByVideoIdOrderByStartedAtDesc(String videoId);

    /** 오늘 계획된 Gemini 호출 수(실행별 청크 수 합) — 일일 상한 판정. 실패 실행도 센다(보수적). */
    @Query("SELECT COALESCE(SUM(r.chunkCount), 0) FROM YtAnalysisRun r WHERE r.startedAt >= :since")
    long sumChunksSince(@Param("since") LocalDateTime since);

    // ---- CLAUDE 로컬 작업자 대기열(V66, 2026-10-01) ----

    List<YtAnalysisRun> findByAnalyzerAndStatusInOrderByIdAsc(String analyzer, Collection<YtAnalysisRun.Status> statuses);

    List<YtAnalysisRun> findByAnalyzerAndStatus(String analyzer, YtAnalysisRun.Status status);

    boolean existsByVideoIdAndStatusIn(String videoId, Collection<YtAnalysisRun.Status> statuses);

    /**
     * 원자적 임대 — 기대한 상태·토큰일 때만 RUNNING 으로 바꾼다. 둘이 동시에 집어도 한쪽만 1행을 얻는다(중복 실행 방지).
     * 대기(WAITING) 흔적은 지운다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE YtAnalysisRun r SET r.status = :running, r.leaseToken = :token, r.leaseUntil = :until, "
            + "r.attempts = r.attempts + 1, r.workerId = :worker, r.nextAttemptAt = NULL, r.waitReason = NULL "
            + "WHERE r.id = :id AND r.status = :expected AND COALESCE(r.leaseToken, '') = :expectedToken")
    int claim(@Param("id") long id, @Param("expected") YtAnalysisRun.Status expected,
              @Param("expectedToken") String expectedToken, @Param("running") YtAnalysisRun.Status running,
              @Param("token") String token, @Param("until") LocalDateTime until, @Param("worker") String worker);
}
