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
            // ========== 섹터 거래대금 캐시 (5분 TTL) ==========
            // 실시간성보다 성능 우선 (API 호출 최소화)
            // 섹터 카디널리티 200+ → 충분한 크기로 조정
            buildCache("sectorTrading", 300, 300),
            buildCache("sectorTradingToday", 300, 300),
            buildCache("sectorTradingMin5", 300, 300),
            buildCache("sectorTradingMin30", 300, 300),

            // ========== 주식 시세 캐시 (3분 TTL) ==========
            buildCache("stockPrice", 180, 1000),
            buildCache("stockPriceBatch", 180, 100),

            // ========== 종목 검색 결과 캐시 (1분 TTL) ==========
            // 자동완성용 — 키워드별 동일 응답. Naver 호출/N+1 방지.
            buildCache("stockSearch", 60, 200),

            // ========== AI 스크리너 분석 캐시 (1시간 TTL) ==========
            // QuantScreener (마법공식/PEG/턴어라운드) 분석 응답.
            // 종목 리스트는 일 단위로만 변경 → 1시간 캐시로 Gemini API 호출 절감.
            buildCache("aiScreenerAnalysis", 3600, 50),

            // ========== 실적 공시 요약 캐시 (6시간 TTL) ==========
            // 분기 실적은 발표 후 며칠간 동일. 사용자 새로고침마다 DART + Gemini 호출 방지.
            buildCache("earningsSummary", 21600, 200),

            // ========== 차트 패턴 검출 캐시 (30분 TTL) ==========
            // 일봉 90일 기준 검출 — 장중에도 1-2번만 갱신되면 충분. KIS 호출 절감.
            buildCache("chartPatterns", 1800, 300),

            // ========== 투자자 매매동향 캐시 (5분 TTL) ==========
            // 투자자 × 시장 × 종목 조합 → 카디널리티 폭발 대비
            buildCache("investorTrend", 300, 500),
            buildCache("continuousBuy", 300, 500),
            buildCache("supplySurge", 300, 500),

            // ========== 연속 매수 종목 캐시 (1시간 TTL) ==========
            // 장 마감 후 하루에 한 번 변경되므로 긴 TTL 적용
            buildCache("consecutiveBuys", 3600, 50),

            // ========== 시세 캐시 (30초 TTL) ==========
            buildCache("goldPrice", 30, 10),
            buildCache("silverPrice", 30, 10),

            // ========== 주식 순위 캐시 (5분 TTL) ==========
            buildCache("week52High", 300, 100),
            buildCache("week52Low", 300, 100),
            buildCache("marketCapHigh", 300, 100),
            buildCache("tradingValue", 300, 100),
            buildCache("priceRiseTop", 300, 100),
            buildCache("priceFallTop", 300, 100),

            // ========== 종목 상세 캐시 (점진적 로딩용) ==========
            // 재무 데이터: 거의 안 변함 → 10분 TTL
            buildCache("stockDetailFinancial", 600, 200),
            // 리스크 분석 (뉴스/공시): 3분 TTL
            buildCache("stockDetailRisk", 180, 200),
            // 차트 데이터: 2분 TTL (장중 갱신 필요)
            buildCache("stockDetailChart", 120, 200),
            // AI 분석 (Gemini): 15분 TTL (비용 절감)
            buildCache("stockDetailAi", 900, 200),
            // 피어 비교: 10분 TTL
            buildCache("stockDetailPeer", 600, 100)
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
