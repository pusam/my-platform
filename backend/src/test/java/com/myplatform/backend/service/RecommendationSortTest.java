package com.myplatform.backend.service;

import com.myplatform.backend.service.RecommendationService.StockScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발굴 TOP10 정렬 tie-break — 등락률 약화(추격 인상 완화).
 *
 * <p>점수·delta 동률일 때 기존엔 changeRate desc(많이 오른 종목 우선)라 "이미 많이 올랐다"가
 * 상위 노출 요인이었음. phase 38: changeRate asc(덜 오른 종목 우선)로 약화.
 */
class RecommendationSortTest {

    private StockScore score(String code, int e, int sd, int tc, int sc, double changeRate) {
        StockScore s = new StockScore(code, code);
        s.earnings = e;
        s.supplyDemand = sd;
        s.technical = tc;
        s.sectorMomentum = sc;
        s.changeRate = BigDecimal.valueOf(changeRate);
        return s;
    }

    @Test
    @DisplayName("tie-break 3순위: 점수·delta 동률이면 덜 오른 종목 우선 (changeRate asc)")
    void tieBreakPrefersLessRisen() {
        StockScore risen = score("RISEN", 20, 20, 20, 20, 20.0); // 100, +20%
        StockScore calm = score("CALM", 20, 20, 20, 20, 5.0);    // 100, +5%
        List<StockScore> list = new ArrayList<>(List.of(risen, calm));
        list.sort(RecommendationService.recommendationComparator(Map.of()));
        assertThat(list.get(0).stockCode).isEqualTo("CALM"); // 덜 오른 종목이 위
    }

    @Test
    @DisplayName("1순위: normalized total desc — 많이 올랐어도 점수 낮으면 아래")
    void primaryByScore() {
        StockScore high = score("HIGH", 20, 20, 20, 20, 1.0);  // 100
        StockScore low = score("LOW", 10, 10, 10, 10, 30.0);   // 50, +30%여도 아래
        List<StockScore> list = new ArrayList<>(List.of(low, high));
        list.sort(RecommendationService.recommendationComparator(Map.of()));
        assertThat(list.get(0).stockCode).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("2순위: 점수 동률이면 delta(오늘-어제) desc — 막 가속한 종목 우선")
    void secondaryByDelta() {
        StockScore accel = score("ACCEL", 20, 20, 20, 20, 1.0); // 오늘 100, 어제 40 → delta 60
        StockScore flat = score("FLAT", 20, 20, 20, 20, 1.0);   // 오늘 100, 어제 90 → delta 10
        Map<String, Integer> prev = Map.of("ACCEL", 40, "FLAT", 90);
        List<StockScore> list = new ArrayList<>(List.of(flat, accel));
        list.sort(RecommendationService.recommendationComparator(prev));
        assertThat(list.get(0).stockCode).isEqualTo("ACCEL");
    }

    @Test
    @DisplayName("신규 진입(prev 부재)은 delta 0 — 최대 delta 로 tie 를 이기던 신규 편향 제거 (2026-07-28)")
    void newEntrantDeltaIsZero() {
        StockScore accel = score("ACCEL", 20, 20, 20, 20, 1.0);   // 어제 40 → delta 60
        StockScore newbie = score("NEWBIE", 20, 20, 20, 20, 1.0); // 어제 풀 밖 → delta 0 (기존 버그: 100)
        Map<String, Integer> prev = Map.of("ACCEL", 40, "FLAT", 90);
        List<StockScore> list = new ArrayList<>(List.of(newbie, accel));
        list.sort(RecommendationService.recommendationComparator(prev));
        assertThat(list.get(0).stockCode).isEqualTo("ACCEL"); // 풀 안 가속이 신규보다 우선
    }
}
