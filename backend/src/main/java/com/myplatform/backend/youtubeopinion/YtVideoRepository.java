package com.myplatform.backend.youtubeopinion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface YtVideoRepository extends JpaRepository<YtVideo, Long> {

    Optional<YtVideo> findByVideoId(String videoId);

    List<YtVideo> findByVideoIdIn(Collection<String> videoIds);

    List<YtVideo> findTop50ByOrderByCreatedAtDesc();

    /** 집계 창 커버리지 — 게시 시각 기준. */
    List<YtVideo> findByPublishedAtGreaterThanEqual(LocalDateTime since);

    List<YtVideo> findByStatus(YtVideo.Status status);
}
