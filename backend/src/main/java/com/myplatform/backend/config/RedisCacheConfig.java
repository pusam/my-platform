package com.myplatform.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis L2 캐시 전용 설정
 * - cache.redis.enabled=true 일 때만 활성화
 * - jwt-redis 모듈의 RedisTemplate(String,String)과 별도 빈
 * - JSON 직렬화는 RedisCacheService에서 ObjectMapper로 직접 처리
 */
@Configuration
@ConditionalOnProperty(name = "cache.redis.enabled", havingValue = "true")
@Slf4j
public class RedisCacheConfig {

    @Bean("cacheRedisTemplate")
    public StringRedisTemplate cacheRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        log.info("[Redis L2] cacheRedisTemplate 초기화 완료");
        return template;
    }
}
