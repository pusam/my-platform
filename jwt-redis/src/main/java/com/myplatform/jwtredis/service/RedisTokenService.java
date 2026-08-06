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
    //
    // 키 구조 (기기별 세션 분리, 2026-08-06):
    //   jwt:refresh:{username}:{deviceId}      — 기기(로그인)별 현재 RT. 멀티 디바이스가 서로 덮어쓰지 않음.
    //   jwt:refresh:{username}                 — deviceId 없는 legacy RT (마이그레이션 중에만 존재).
    //   jwt:refresh-prev:{username}[:{deviceId}] — 회전 직전 RT 를 짧은 유예 TTL 로 보존.
    //                                            멀티탭이 같은 RT 로 동시 갱신하는 경합을 흡수한다.
    // deviceId=null 은 legacy 키로 위임 — 호출부가 분기 없이 한 메서드만 쓰게.

    private static final String REFRESH_PREV_PREFIX = "jwt:refresh-prev:";

    private String refreshKey(String username, String deviceId) {
        return deviceId == null ? REFRESH_PREFIX + username : REFRESH_PREFIX + username + ":" + deviceId;
    }

    private String prevRefreshKey(String username, String deviceId) {
        return deviceId == null ? REFRESH_PREV_PREFIX + username : REFRESH_PREV_PREFIX + username + ":" + deviceId;
    }

    /** Legacy 시그니처 — 기기 미분리 단일 키 저장. 신규 코드는 deviceId 버전 사용. */
    public void saveRefreshToken(String username, String refreshToken, long expirationMs) {
        saveRefreshToken(username, null, refreshToken, expirationMs);
    }

    public void saveRefreshToken(String username, String deviceId, String refreshToken, long expirationMs) {
        try {
            redisTemplate.opsForValue().set(refreshKey(username, deviceId), refreshToken, expirationMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Redis save refresh token failed for {}: {}", username, e.getMessage());
        }
    }

    /** Legacy 시그니처 — 단일 키 조회. */
    public String getRefreshToken(String username) {
        return getRefreshToken(username, null);
    }

    public String getRefreshToken(String username, String deviceId) {
        try {
            return redisTemplate.opsForValue().get(refreshKey(username, deviceId));
        } catch (Exception e) {
            log.warn("Redis get refresh token failed for {}: {}", username, e.getMessage());
            return null;
        }
    }

    /** Legacy 시그니처 — 단일 키 삭제. */
    public void deleteRefreshToken(String username) {
        deleteRefreshToken(username, null);
    }

    public void deleteRefreshToken(String username, String deviceId) {
        try {
            redisTemplate.delete(refreshKey(username, deviceId));
        } catch (Exception e) {
            log.warn("Redis delete refresh token failed for {}: {}", username, e.getMessage());
        }
    }

    /** 회전 직전 RT 를 유예 TTL(짧게)로 보존 — 같은 RT 로 경합한 다른 탭의 갱신을 허용하기 위함. */
    public void savePreviousRefreshToken(String username, String deviceId, String refreshToken, long graceMs) {
        try {
            redisTemplate.opsForValue().set(prevRefreshKey(username, deviceId), refreshToken, graceMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Redis save previous refresh token failed for {}: {}", username, e.getMessage());
        }
    }

    public String getPreviousRefreshToken(String username, String deviceId) {
        try {
            return redisTemplate.opsForValue().get(prevRefreshKey(username, deviceId));
        } catch (Exception e) {
            log.warn("Redis get previous refresh token failed for {}: {}", username, e.getMessage());
            return null;
        }
    }

    public void deletePreviousRefreshToken(String username, String deviceId) {
        try {
            redisTemplate.delete(prevRefreshKey(username, deviceId));
        } catch (Exception e) {
            log.warn("Redis delete previous refresh token failed for {}: {}", username, e.getMessage());
        }
    }

    /**
     * 계정의 모든 기기 RT(+유예분·legacy 키) 일괄 삭제 — 계정 상태 변경(잠금·승인취소 등)
     * 또는 기기 식별 불가한 legacy 로그아웃용. 기기 단위 무효화에는 쓰지 말 것.
     * KEYS 스캔은 개인 플랫폼(사용자·기기 수 소규모) 전제 — 대규모라면 SCAN 으로 교체.
     */
    public void deleteAllRefreshTokens(String username) {
        try {
            redisTemplate.delete(REFRESH_PREFIX + username);
            redisTemplate.delete(REFRESH_PREV_PREFIX + username);
            java.util.Set<String> keys = redisTemplate.keys(REFRESH_PREFIX + username + ":*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
            java.util.Set<String> prevKeys = redisTemplate.keys(REFRESH_PREV_PREFIX + username + ":*");
            if (prevKeys != null && !prevKeys.isEmpty()) {
                redisTemplate.delete(prevKeys);
            }
        } catch (Exception e) {
            log.warn("Redis delete all refresh tokens failed for {}: {}", username, e.getMessage());
        }
    }
}
