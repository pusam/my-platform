package com.myplatform.backend.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(
            "investorTrend",      // 투자자 매매동향 (5분 캐시)
            "continuousBuy",      // 연속 매수 종목 (5분 캐시)
            "supplySurge",        // 수급 급등 종목 (5분 캐시)
            "goldPrice",          // 금 시세 (30초 캐시)
            "silverPrice",        // 은 시세 (30초 캐시)
            "redditUSStocks",     // Reddit 미국 주식 (10분 캐시)
            "redditKRStocks",     // Reddit 한국 주식 (10분 캐시)
            "redditPosts"         // Reddit 게시글 (10분 캐시)
        );
    }
}

