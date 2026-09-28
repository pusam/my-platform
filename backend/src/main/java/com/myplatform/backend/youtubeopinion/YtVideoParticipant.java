package com.myplatform.backend.youtubeopinion;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 영상별 출연자 — 발언자 연결은 이 명단 안에서만 한다. V61. */
@Entity
@Table(name = "yt_video_participant",
        uniqueConstraints = @UniqueConstraint(name = "uq_ytvp_video_person", columnNames = {"video_id", "person_id"}),
        indexes = @Index(name = "idx_ytvp_person", columnList = "person_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YtVideoParticipant {

    /** HOST=채널 운영자, GUEST=출연자. */
    public enum Role { HOST, GUEST }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false, length = 11)
    private String videoId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 10)
    private Role role;
}
