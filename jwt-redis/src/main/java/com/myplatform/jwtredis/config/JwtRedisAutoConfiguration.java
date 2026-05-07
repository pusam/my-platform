package com.myplatform.jwtredis.config;

import com.myplatform.jwtredis.entrypoint.JwtAuthenticationEntryPoint;
import com.myplatform.jwtredis.filter.JwtAuthenticationFilter;
import com.myplatform.jwtredis.provider.JwtTokenProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.UserDetailsService;

@AutoConfiguration
public class JwtRedisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtTokenProvider jwtTokenProvider(JwtProperties jwtProperties) {
        // access/refresh 분리 — 새 properties 가 0 이면 legacy expiration 으로 fallback.
        long access = jwtProperties.getAccessExpiration() > 0
                ? jwtProperties.getAccessExpiration()
                : jwtProperties.getExpiration();
        long refresh = jwtProperties.getRefreshExpiration() > 0
                ? jwtProperties.getRefreshExpiration()
                : access * 7L;
        return new JwtTokenProvider(jwtProperties.getSecret(), jwtProperties.getExpiration(), access, refresh);
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint() {
        return new JwtAuthenticationEntryPoint();
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            UserDetailsService userDetailsService) {
        return new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService);
    }

    @Bean
    @ConfigurationProperties(prefix = "jwt")
    @ConditionalOnMissingBean
    public JwtProperties jwtProperties() {
        return new JwtProperties();
    }
}

