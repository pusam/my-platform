package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 자동매매 봇 설정 엔티티
 * - 봇 상태(활성화/비활성화)를 DB에 저장하여 서버 재시작 시에도 유지
 */
@Entity
@Table(name = "bot_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 설정 키 (예: "trading_bot")
     */
    @Column(name = "config_key", nullable = false, unique = true, length = 50)
    private String configKey;

    /**
     * 봇 활성화 여부
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = false;

    /**
     * 매매 모드 (VIRTUAL / REAL)
     */
    @Column(name = "trading_mode", length = 20)
    @Builder.Default
    private String tradingMode = "VIRTUAL";

    /**
     * 마지막 상태 변경 시간
     */
    @Column(name = "last_status_change")
    private LocalDateTime lastStatusChange;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
