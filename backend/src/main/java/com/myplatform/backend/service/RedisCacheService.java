package com.myplatform.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * Redis L2 캐시 서비스
 * - Redis 없으면 모든 메서드 no-op (graceful fallback)
 * - 모든 연산 try-catch로 예외 전파 없음
 * - 키 컨벤션: java:cache:{cacheName}:{key}
 * - JSON 직렬화는 ObjectMapper로 직접 처리 (deprecated serializer 회피)
 */
@Service
@Slf4j
public class RedisCacheService {

    private static final String KEY_PREFIX = "java:cache:";

    private final StringRedisTemplate cacheRedisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public RedisCacheService(
            @Autowired(required = false) @Qualifier("cacheRedisTemplate") StringRedisTemplate cacheRedisTemplate
    ) {
        this.cacheRedisTemplate = cacheRedisTemplate;
        this.enabled = cacheRedisTemplate != null;

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        if (enabled) {
            log.info("[Redis L2] RedisCacheService 활성화됨");
        } else {
            log.info("[Redis L2] Redis 비활성화 - 모든 연산 no-op");
        }
    }

    /**
     * Redis에 데이터 저장 (Object → JSON string)
     */
    public void put(String cacheName, String key, Object value, Duration ttl) {
        if (!enabled) return;
        try {
            String redisKey = buildKey(cacheName, key);
            String json = objectMapper.writeValueAsString(value);
            cacheRedisTemplate.opsForValue().set(redisKey, json, ttl);
            log.debug("[Redis L2] PUT {} (TTL: {}s, size: {})", redisKey, ttl.getSeconds(), json.length());
        } catch (Exception e) {
            log.warn("[Redis L2] PUT 실패 - {}:{} : {}", cacheName, key, e.getMessage());
        }
    }

    /**
     * Redis에서 데이터 조회 (JSON string → Object)
     * @return 값이 없거나 에러 시 null
     */
    public <T> T get(String cacheName, String key, Class<T> type) {
        if (!enabled) return null;
        try {
            String redisKey = buildKey(cacheName, key);
            String json = cacheRedisTemplate.opsForValue().get(redisKey);
            if (json == null) {
                log.debug("[Redis L2] MISS {}", redisKey);
                return null;
            }
            log.debug("[Redis L2] HIT {}", redisKey);
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("[Redis L2] GET 실패 - {}:{} : {}", cacheName, key, e.getMessage());
            return null;
        }
    }

    /**
     * Redis에서 데이터 조회 (제네릭 타입용 - List, Map 등)
     */
    public <T> T get(String cacheName, String key, TypeReference<T> typeRef) {
        if (!enabled) return null;
        try {
            String redisKey = buildKey(cacheName, key);
            String json = cacheRedisTemplate.opsForValue().get(redisKey);
            if (json == null) {
                log.debug("[Redis L2] MISS {}", redisKey);
                return null;
            }
            log.debug("[Redis L2] HIT {}", redisKey);
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("[Redis L2] GET 실패 - {}:{} : {}", cacheName, key, e.getMessage());
            return null;
        }
    }

    /**
     * 특정 캐시 키 삭제
     */
    public void evict(String cacheName, String key) {
        if (!enabled) return;
        try {
            String redisKey = buildKey(cacheName, key);
            cacheRedisTemplate.delete(redisKey);
            log.debug("[Redis L2] EVICT {}", redisKey);
        } catch (Exception e) {
            log.warn("[Redis L2] EVICT 실패 - {}:{} : {}", cacheName, key, e.getMessage());
        }
    }

    /**
     * 특정 캐시명의 모든 키 삭제
     */
    public void evictAll(String cacheName) {
        if (!enabled) return;
        try {
            String pattern = KEY_PREFIX + cacheName + ":*";
            Set<String> keys = cacheRedisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                cacheRedisTemplate.delete(keys);
                log.info("[Redis L2] EVICT ALL {} - {}개 삭제", cacheName, keys.size());
            }
        } catch (Exception e) {
            log.warn("[Redis L2] EVICT ALL 실패 - {} : {}", cacheName, e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    private String buildKey(String cacheName, String key) {
        return KEY_PREFIX + cacheName + ":" + key;
    }
}
