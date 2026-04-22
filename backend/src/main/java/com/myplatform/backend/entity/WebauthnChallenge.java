package com.myplatform.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "webauthn_challenges")
public class WebauthnChallenge {

    public enum Ceremony { REGISTER, LOGIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_key", nullable = false, length = 64)
    private String sessionKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Ceremony ceremony;

    @Column(nullable = false, length = 64)
    private byte[] challenge;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionKey() { return sessionKey; }
    public void setSessionKey(String sessionKey) { this.sessionKey = sessionKey; }
    public Ceremony getCeremony() { return ceremony; }
    public void setCeremony(Ceremony ceremony) { this.ceremony = ceremony; }
    public byte[] getChallenge() { return challenge; }
    public void setChallenge(byte[] challenge) { this.challenge = challenge; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
