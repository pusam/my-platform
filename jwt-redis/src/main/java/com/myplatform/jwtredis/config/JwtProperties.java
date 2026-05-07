package com.myplatform.jwtredis.config;

public class JwtProperties {
    private String secret;
    /** Legacy 단일 만료시간 — 새 코드는 access-expiration / refresh-expiration 사용. backward compat 위해 유지. */
    private long expiration = 86_400_000L;  // 24h
    /** Access Token TTL — 기본 15분. 짧게 유지해 탈취 시 노출 시간 최소화. */
    private long accessExpiration = 900_000L;
    /** Refresh Token TTL — 기본 7일. /api/auth/refresh 로 새 AT 발급용. */
    private long refreshExpiration = 604_800_000L;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpiration() {
        return expiration;
    }

    public void setExpiration(long expiration) {
        this.expiration = expiration;
    }

    public long getAccessExpiration() {
        return accessExpiration;
    }

    public void setAccessExpiration(long accessExpiration) {
        this.accessExpiration = accessExpiration;
    }

    public long getRefreshExpiration() {
        return refreshExpiration;
    }

    public void setRefreshExpiration(long refreshExpiration) {
        this.refreshExpiration = refreshExpiration;
    }
}
