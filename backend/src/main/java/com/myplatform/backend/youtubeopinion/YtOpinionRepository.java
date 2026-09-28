package com.myplatform.backend.youtubeopinion;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface YtOpinionRepository extends JpaRepository<YtOpinion, Long> {

    /**
     * 여러 종목의 <b>현재 실행</b> 의견을 한 번에 — 화면 목록이 종목마다 조회하지 않게.
     * 창 판정·자격(본인 현재 의견·검토 통과·원본 영상)은 {@link OpinionAggregator} 가 한 곳에서 한다.
     */
    @Query("SELECT o FROM YtOpinion o, YtVideo v WHERE v.videoId = o.videoId AND v.currentRunId = o.runId "
            + "AND o.stockCode IN :codes AND v.publishedAt >= :since")
    List<YtOpinion> findCurrentByStockCodes(@Param("codes") Collection<String> codes,
                                            @Param("since") LocalDateTime since);

    @Query("SELECT o FROM YtOpinion o, YtVideo v WHERE v.videoId = o.videoId AND v.currentRunId = o.runId "
            + "AND o.reviewStatus = :status ORDER BY o.id DESC")
    List<YtOpinion> findCurrentByReviewStatus(@Param("status") YtOpinion.ReviewStatus status, Pageable pageable);

    List<YtOpinion> findByRunId(Long runId);
}
