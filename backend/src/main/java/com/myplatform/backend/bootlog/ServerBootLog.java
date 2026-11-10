package com.myplatform.backend.bootlog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 백엔드 부팅 1회 = 한 행(V74, 2026-11-10).
 *
 * <p>관제실 '장중 재시작' 카드가 읽는다 — 표본 시작일은 08:30~15:50 에 재시작이 없던 거래일이어야 한다(CLAUDE.md §7).
 * 쓰는 곳은 {@link ServerBootRecorder} 하나뿐이다.
 */
@Entity
@Table(name = "server_boot_log", indexes = @Index(name = "idx_sbl_started_at", columnList = "started_at"))
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerBootLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** JVM 이 뜬 시각(KST) — 재시작 순간에 가장 가깝다. */
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** 부팅 완료(ApplicationReadyEvent) 시각. */
    @Column(name = "ready_at", nullable = false)
    private LocalDateTime readyAt;

    /** 컨테이너 호스트 이름(= 컨테이너 id) — 같으면 재시작, 바뀌면 재생성(배포). */
    @Column(name = "host", length = 100)
    private String host;

    /** 이미지가 담은 커밋(APP_REVISION). 모르면 null. */
    @Column(name = "revision", length = 64)
    private String revision;
}
