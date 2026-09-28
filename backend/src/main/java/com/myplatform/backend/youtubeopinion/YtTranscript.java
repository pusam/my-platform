package com.myplatform.backend.youtubeopinion;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 관리자 등록 타임스탬프 자막 — 내용이 바뀔 때만 버전이 올라간다. V61. */
@Entity
@Table(name = "yt_transcript",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_ytt_video_version", columnNames = {"video_id", "version"}),
                @UniqueConstraint(name = "uq_ytt_video_hash", columnNames = {"video_id", "content_sha256"})
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YtTranscript {

    public enum Format { SRT, VTT, TEXT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false, length = 11)
    private String videoId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_format", nullable = false, length = 10)
    private Format sourceFormat;

    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(name = "cue_count", nullable = false)
    private Integer cueCount;

    @Column(name = "duration_sec", nullable = false)
    private Integer durationSec;

    /** 정규화 큐 JSON — 태그를 걷어낸 평문. 화면에 HTML 로 렌더링하지 않는다. */
    @Column(name = "cues_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String cuesJson;

    @Column(name = "uploaded_by", length = 50)
    private String uploadedBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
