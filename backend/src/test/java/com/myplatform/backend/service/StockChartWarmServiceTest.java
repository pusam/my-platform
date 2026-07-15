package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장 마감 후 차트 프리워밍 — 대상 선정(관심종목∪봇보유·dedup·상한) + 장외 판정 순수 함수.
 */
class StockChartWarmServiceTest {

    @Test
    @DisplayName("selectWarmTargets — 관심종목 우선 + 봇보유 합집합, dedup, 6자리만, 상한")
    void selectWarmTargets_unionDedupCap() {
        List<String> watch = List.of("005930", "000660", "005930");        // 중복 삼전
        List<String> positions = List.of("000660", "035420", "bad", "12");  // 000660 중복, 비정상 코드
        List<String> out = StockChartWarmService.selectWarmTargets(watch, positions, 10);

        // 관심종목 먼저(삼전·하이닉스) → 봇 추가(네이버), 중복·비정상 제거
        assertThat(out).containsExactly("005930", "000660", "035420");
    }

    @Test
    @DisplayName("selectWarmTargets — 상한 적용(관심종목 우선 채움), cap<=0 은 기본 20")
    void selectWarmTargets_cap() {
        List<String> watch = List.of("000001", "000002", "000003");
        List<String> positions = List.of("000004");
        assertThat(StockChartWarmService.selectWarmTargets(watch, positions, 2))
                .containsExactly("000001", "000002");   // 상한 2 → 관심종목 앞 2개
        assertThat(StockChartWarmService.selectWarmTargets(watch, positions, 0)).hasSize(4);   // cap<=0 → 기본
    }

    @Test
    @DisplayName("selectWarmTargets — null/빈 입력 방어")
    void selectWarmTargets_guards() {
        assertThat(StockChartWarmService.selectWarmTargets(null, null, 5)).isEmpty();
        assertThat(StockChartWarmService.selectWarmTargets(List.of(), List.of(), 5)).isEmpty();
    }

    @Test
    @DisplayName("isOutsideMarket — 08:00~20:00 안=false, 밖=true(장외 워밍 캐시 신뢰 구간)")
    void isOutsideMarket_boundaries() {
        assertThat(StockDetailCacheService.isOutsideMarket(LocalTime.of(7, 59))).isTrue();
        assertThat(StockDetailCacheService.isOutsideMarket(LocalTime.of(8, 0))).isFalse();
        assertThat(StockDetailCacheService.isOutsideMarket(LocalTime.of(15, 0))).isFalse();
        assertThat(StockDetailCacheService.isOutsideMarket(LocalTime.of(20, 0))).isFalse();
        assertThat(StockDetailCacheService.isOutsideMarket(LocalTime.of(20, 1))).isTrue();
        assertThat(StockDetailCacheService.isOutsideMarket(LocalTime.of(23, 30))).isTrue();
    }
}
