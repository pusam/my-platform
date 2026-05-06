package com.myplatform.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 스케줄러 분산락 — Redis SET NX EX 기반 경량 lock.
 * - Redis 비활성화 환경에서는 항상 acquired=true 반환 (단일 인스턴스 fallback)
 * - 키 컨벤션: java:scheduler-lock:{name}
 * - TTL 만료 시 자동 해제. 명시 release() 호출 가능.
 */
@Service
@Slf4j
public class SchedulerLockService {

    private static final String KEY_PREFIX = "java:scheduler-lock:";

    private final StringRedisTemplate cacheRedisTemplate;
    private final boolean enabled;

    public SchedulerLockService(
            @Autowired(required = false) @Qualifier("cacheRedisTemplate") StringRedisTemplate cacheRedisTemplate
    ) {
        this.cacheRedisTemplate = cacheRedisTemplate;
        this.enabled = cacheRedisTemplate != null;
        if (enabled) {
            log.info("[SchedulerLock] Redis 분산락 활성화");
        } else {
            log.info("[SchedulerLock] Redis 비활성화 - 단일 인스턴스 fallback");
        }
    }

    /**
     * 락 획득 시도. true 면 획득 성공 → 호출자가 작업 진행.
     * false 면 다른 인스턴스가 잡고 있음 → 호출자는 즉시 return.
     * Redis 미사용 시 항상 true.
     */
    public boolean tryLock(String name, Duration ttl) {
        if (!enabled) return true;
        try {
            String key = KEY_PREFIX + name;
            String token = String.valueOf(System.currentTimeMillis());
            Boolean ok = cacheRedisTemplate.opsForValue().setIfAbsent(key, token, ttl);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("[SchedulerLock] tryLock 실패 ({}): {} - fail-open으로 진행", name, e.getMessage());
            // 락 시스템 자체가 죽으면 작업은 진행시키는 게 운영상 안전 (단일 인스턴스 추정)
            return true;
        }
    }

    /**
     * 락 명시적 해제. 작업 빠르게 끝났을 때 호출하면 다음 cron 까지 안 기다리고 다른 인스턴스가 잡을 수 있음.
     * TTL 만료에 맡겨도 무방 — 안 부르면 자동 해제.
     */
    public void release(String name) {
        if (!enabled) return;
        try {
            cacheRedisTemplate.delete(KEY_PREFIX + name);
        } catch (Exception e) {
            log.debug("[SchedulerLock] release 실패 ({}): {}", name, e.getMessage());
        }
    }
}
