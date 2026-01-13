package com.myplatform.jwtredis.config;

import com.myplatform.jwtredis.service.RedisTokenService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;

@AutoConfiguration
@ConditionalOnClass(RedisTemplate.class)
public class RedisTokenAutoConfiguration {

    @Bean
    @ConditionalOnBean(RedisTemplate.class)
    @ConditionalOnMissingBean
    public RedisTokenService redisTokenService(RedisTemplate<String, String> redisTemplate) {
        return new RedisTokenService(redisTemplate);
    }
}

