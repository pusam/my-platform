package com.myplatform.backend.youtubeopinion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface YtTranscriptRepository extends JpaRepository<YtTranscript, Long> {

    Optional<YtTranscript> findTopByVideoIdOrderByVersionDesc(String videoId);

    Optional<YtTranscript> findByVideoIdAndContentSha256(String videoId, String contentSha256);

    long countByVideoId(String videoId);

    /** 영상별 자막 버전 수 — 관리 목록 묶음 조회. [videoId, count] */
    @org.springframework.data.jpa.repository.Query(
            "SELECT t.videoId, COUNT(t) FROM YtTranscript t WHERE t.videoId IN :ids GROUP BY t.videoId")
    java.util.List<Object[]> countByVideoIds(@org.springframework.data.repository.query.Param("ids") java.util.Collection<String> ids);
}
