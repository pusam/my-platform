package com.myplatform.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "webauthn_credentials")
public class WebauthnCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "credential_id", nullable = false, length = 512)
    private byte[] credentialId;

    @Column(name = "public_key", nullable = false, length = 65535)
    private byte[] publicKey;

    @Column(name = "sign_count", nullable = false)
    private long signCount;

    @Column(name = "transports", length = 128)
    private String transports;

    @Column(name = "aaguid", length = 16)
    private byte[] aaguid;

    @Column(name = "device_name", length = 100)
    private String deviceName;

    @Column(name = "user_handle", nullable = false, length = 64)
    private byte[] userHandle;

    @Column(name = "backup_eligible", nullable = false)
    private boolean backupEligible;

    @Column(name = "backup_state", nullable = false)
    private boolean backupState;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public byte[] getCredentialId() { return credentialId; }
    public void setCredentialId(byte[] credentialId) { this.credentialId = credentialId; }
    public byte[] getPublicKey() { return publicKey; }
    public void setPublicKey(byte[] publicKey) { this.publicKey = publicKey; }
    public long getSignCount() { return signCount; }
    public void setSignCount(long signCount) { this.signCount = signCount; }
    public String getTransports() { return transports; }
    public void setTransports(String transports) { this.transports = transports; }
    public byte[] getAaguid() { return aaguid; }
    public void setAaguid(byte[] aaguid) { this.aaguid = aaguid; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public byte[] getUserHandle() { return userHandle; }
    public void setUserHandle(byte[] userHandle) { this.userHandle = userHandle; }
    public boolean isBackupEligible() { return backupEligible; }
    public void setBackupEligible(boolean backupEligible) { this.backupEligible = backupEligible; }
    public boolean isBackupState() { return backupState; }
    public void setBackupState(boolean backupState) { this.backupState = backupState; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
