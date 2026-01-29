package com.myplatform.backend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * 캐시 설정 - Caffeine 캐시 사용
 * - TTL(Time To Live) 지원
 * - 최대 크기 제한
 * - 자동 만료 정책
 */
@Configuration
@EnableCaching
@Slf4j
public class CacheConfig {

    /**
     * 캐시별 설정이 다른 경우를 위한 SimpleCacheManager
     */
    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();

        cacheManager.setCaches(Arrays.asList(
            // ========== 섹터 거래대금 캐시 (1분 TTL) ==========
            buildCache("sectorTrading", 60, 100),
            buildCache("sectorTradingToday", 60, 100),
            buildCache("sectorTradingMin5", 60, 100),
            buildCache("sectorTradingMin30", 60, 100),

            // ========== 주식 시세 캐시 (1분 TTL) ==========
            buildCache("stockPrice", 60, 500),
            buildCache("stockPriceBatch", 60, 50),

            // ========== 투자자 매매동향 캐시 (5분 TTL) ==========
            buildCache("investorTrend", 300, 200),
            buildCache("continuousBuy", 300, 200),
            buildCache("supplySurge", 300, 200),

            // ========== 연속 매수 종목 캐시 (1시간 TTL) ==========
            // 장 마감 후 하루에 한 번 변경되므로 긴 TTL 적용
            buildCache("consecutiveBuys", 3600, 50),

            // ========== 시세 캐시 (30초 TTL) ==========
            buildCache("goldPrice", 30, 10),
            buildCache("silverPrice", 30, 10),

            // ========== Reddit 캐시 (10분 TTL) ==========
            buildCache("redditUSStocks", 600, 100),
            buildCache("redditKRStocks", 600, 100),
            buildCache("redditPosts", 600, 500),

            // ========== 주식 순위 캐시 (5분 TTL) ==========
            buildCache("week52High", 300, 100),
            buildCache("week52Low", 300, 100),
            buildCache("marketCapHigh", 300, 100),
            buildCache("tradingValue", 300, 100),
            buildCache("priceRiseTop", 300, 100),
            buildCache("priceFallTop", 300, 100)
        ));

        log.info("Caffeine 캐시 매니저 초기화 완료 - 캐시 수: {}", cacheManager.getCacheNames().size());
        return cacheManager;
    }

    /**
     * Caffeine 캐시 빌더
     * @param name 캐시 이름
     * @param ttlSeconds TTL(초)
     * @param maxSize 최대 엔트리 수
     */
    private CaffeineCache buildCache(String name, int ttlSeconds, int maxSize) {
        return new CaffeineCache(name,
            Caffeine.newBuilder()
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .maximumSize(maxSize)
                .recordStats() // 통계 기록 (모니터링용)
                .build()
        );
    }

    /**
     * 기본 Caffeine 빌더 (필요 시 다른 서비스에서 사용)
     * - 1분 TTL, 최대 1000개
     */
    @Bean
    public Caffeine<Object, Object> caffeineConfig() {
        return Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(1000)
            .recordStats();
    }
}
