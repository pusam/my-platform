package com.myplatform.jwtredis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.TimeUnit;

public class RedisTokenService {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenService.class);
    private static final String TOKEN_PREFIX = "jwt:token:";
    private static final String REFRESH_PREFIX = "jwt:refresh:";

    private final RedisTemplate<String, String> redisTemplate;

    public RedisTokenService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void saveToken(String username, String token, long expirationMs) {
        try {
            String key = TOKEN_PREFIX + username;
            redisTemplate.opsForValue().set(key, token, expirationMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Redis 연결 실패 시 로그만 남기고 무시 (토큰 저장 실패는 치명 아님)
            log.warn("Redis save failed for {}: {}", username, e.getMessage());
        }
    }

    public String getToken(String username) {
        try {
            String key = TOKEN_PREFIX + username;
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis get failed for {}: {}", username, e.getMessage());
            return null;
        }
    }

    public void deleteToken(String username) {
        try {
            String key = TOKEN_PREFIX + username;
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis delete failed for {}: {}", username, e.getMessage());
        }
    }

    public boolean hasToken(String username) {
        try {
            String key = TOKEN_PREFIX + username;
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("Redis hasKey failed for {}: {}", username, e.getMessage());
            return false;
        }
    }

    // ========== Refresh Token 전용 ==========

    /**
     * Refresh Token 저장 — Access Token 과 키 분리해 양쪽이 충돌 없이 공존.
     * Token rotation 정책: refresh 호출 시 옛 RT 를 새 RT 로 덮어쓰기 (이 메서드 그대로 호출).
     */
    public void saveRefreshToken(String username, String refreshToken, long expirationMs) {
        try {
            String key = REFRESH_PREFIX + username;
            redisTemplate.opsForValue().set(key, refreshToken, expirationMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Redis save refresh token failed for {}: {}", username, e.getMessage());
        }
    }

    public String getRefreshToken(String username) {
        try {
            String key = REFRESH_PREFIX + username;
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis get refresh token failed for {}: {}", username, e.getMessage());
            return null;
        }
    }

    public void deleteRefreshToken(String username) {
        try {
            String key = REFRESH_PREFIX + username;
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis delete refresh token failed for {}: {}", username, e.getMessage());
        }
    }
}
