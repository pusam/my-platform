package com.myplatform.jwtredis.service;

import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.TimeUnit;

public class RedisTokenService {

    private final RedisTemplate<String, String> redisTemplate;
    private static final String TOKEN_PREFIX = "jwt:token:";

    public RedisTokenService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void saveToken(String username, String token, long expirationMs) {
        try {
            String key = TOKEN_PREFIX + username;
            redisTemplate.opsForValue().set(key, token, expirationMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Redis 연결 실패 시 무시 (로그만 남김)
            System.err.println("Redis save failed: " + e.getMessage());
        }
    }

    public String getToken(String username) {
        try {
            String key = TOKEN_PREFIX + username;
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            System.err.println("Redis get failed: " + e.getMessage());
            return null;
        }
    }

    public void deleteToken(String username) {
        try {
            String key = TOKEN_PREFIX + username;
            redisTemplate.delete(key);
        } catch (Exception e) {
            System.err.println("Redis delete failed: " + e.getMessage());
        }
    }

    public boolean hasToken(String username) {
        try {
            String key = TOKEN_PREFIX + username;
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            System.err.println("Redis hasKey failed: " + e.getMessage());
            return false;
        }
    }
}

