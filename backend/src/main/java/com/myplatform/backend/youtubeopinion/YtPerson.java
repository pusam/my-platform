package com.myplatform.backend.youtubeopinion;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 유튜브 의견 발언자 — 관리자가 명시 등록한 인물만(모델이 만들지 않는다). V61. */
@Entity
@Table(name = "yt_person",
        uniqueConstraints = @UniqueConstraint(name = "uq_ytp_name_key", columnNames = "name_key"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YtPerson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    /** {@link NameKeys#key} — 같은 인물 판정 키. */
    @Column(name = "name_key", nullable = false, length = 50)
    private String nameKey;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
