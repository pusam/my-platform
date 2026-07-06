package com.myplatform.backend.service;

import com.myplatform.backend.service.RecommendationService.StockScore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A안(P1-6) 수급 캡 — 순수함수 경계값 + composite 총점 효과 테스트.
 *
 * <p>prod 실측(n=88): 수급 점수 단조 역상관(0-4=67% → 15+=35%). 30일 재채점: STRONG_BUY 8행 중 7행
 * (전부 삼성전기 009150, sd=20, 비수급 base 40~45) 이 캡 10에서 BUY 로 강등 = "수급 의존 SB" 원형.
 * 캡은 <b>composite 총점 산식에서만</b> 적용(표시값·validCount·임계 불변, 가역 flag).
 */
class RecommendationSupplyCapTest {

    /** 각 테스트 후 정적 캡을 기본값(10)으로 복원 — 다른 테스트 클래스(SortTest 등) 오염 방지. */
    @AfterEach
    void resetCap() {
        RecommendationService.SUPPLY_DEMAND_CAP = 10;
    }

    private StockScore score(int e, int sd, int tc, int sc, int value) {
        StockScore s = new StockScore("T", "T");
        s.earnings = e;
        s.supplyDemand = sd;
        s.technical = tc;
        s.sectorMomentum = sc;
        s.valueStability = value;
        return s;
    }

    // ── cappedSupply 경계 ─────────────────────────────────────────────

    @Test
    @DisplayName("cappedSupply 활성(cap=10): sd>10 은 10 으로, ≤10 은 원값")
    void cappedSupply_active() {
        assertThat(RecommendationService.cappedSupply(20, 10)).isEqualTo(10);
        assertThat(RecommendationService.cappedSupply(11, 10)).isEqualTo(10);
        assertThat(RecommendationService.cappedSupply(10, 10)).isEqualTo(10); // 경계 = 유지
        assertThat(RecommendationService.cappedSupply(8, 10)).isEqualTo(8);
        assertThat(RecommendationService.cappedSupply(0, 10)).isZero();
    }

    @Test
    @DisplayName("cappedSupply 비활성(cap≥20 또는 -1): 무캡 원값 (가역)")
    void cappedSupply_disabled() {
        assertThat(RecommendationService.cappedSupply(18, 20)).isEqualTo(18); // 20 = 비활성
        assertThat(RecommendationService.cappedSupply(18, 25)).isEqualTo(18);
        assertThat(RecommendationService.cappedSupply(18, -1)).isEqualTo(18); // -1 = 비활성
    }

    @Test
    @DisplayName("cappedSupply 경계 cap=19 는 활성 (min 적용)")
    void cappedSupply_boundary19() {
        assertThat(RecommendationService.cappedSupply(20, 19)).isEqualTo(19);
    }

    // ── composite 총점 효과 (getNormalizedTotal) ──────────────────────

    @Test
    @DisplayName("수급 의존 SB(sd20, 비수급 base 40) → 캡10에서 BUY 강등 (75→62)")
    void supplyReliantSB_downgrades() {
        StockScore s = score(20, 20, 10, 10, -1); // raw=60, 비수급 base=40
        RecommendationService.SUPPLY_DEMAND_CAP = 20; // 비활성
        assertThat(s.getNormalizedTotal()).isEqualTo(75); // 60 → 75 STRONG_BUY
        RecommendationService.SUPPLY_DEMAND_CAP = 10; // 활성
        assertThat(s.getNormalizedTotal()).isEqualTo(62); // sd→10, raw=50 → 62 (BUY 55~74)
    }

    @Test
    @DisplayName("강한 베이스 SB(비수급 base 54) → 캡10에도 STRONG_BUY 유지 (92→80)")
    void strongBaseSB_stays() {
        StockScore s = score(20, 20, 18, 16, -1); // raw=74, 비수급 base=54
        RecommendationService.SUPPLY_DEMAND_CAP = 10; // sd→10, raw=64 → 80
        assertThat(s.getNormalizedTotal()).isEqualTo(80);
        assertThat(s.getNormalizedTotal()).isGreaterThanOrEqualTo(75); // 여전히 STRONG_BUY
    }

    @Test
    @DisplayName("저수급(sd≤10)은 캡 무영향 — 활성/비활성 동일 점수")
    void lowSupply_unaffected() {
        StockScore s = score(20, 8, 18, 16, -1); // sd=8 ≤ 10
        RecommendationService.SUPPLY_DEMAND_CAP = 20;
        int uncapped = s.getNormalizedTotal();
        RecommendationService.SUPPLY_DEMAND_CAP = 10;
        assertThat(s.getNormalizedTotal()).isEqualTo(uncapped);
    }

    @Test
    @DisplayName("비활성 flag(cap=-1)는 무캡과 동일 — 완전 가역")
    void disabled_equalsUncapped() {
        StockScore s = score(18, 20, 14, 8, -1);
        RecommendationService.SUPPLY_DEMAND_CAP = 20;
        int a = s.getNormalizedTotal();
        RecommendationService.SUPPLY_DEMAND_CAP = -1;
        assertThat(s.getNormalizedTotal()).isEqualTo(a);
    }

    // ── validCount 불변 (풀 게이트 안전성) ────────────────────────────

    @Test
    @DisplayName("validCount 는 캡 전 원값(>0) 기준 — 캡해도 불변(75% 커버리지 게이트 무영향)")
    void validCount_invariant() {
        // sd=20 강세든 캡 후 10이든 '유효(>0)' 판정은 동일 → validCount=4
        assertThat(RecommendationService.validCount(20, 20, 10, 10)).isEqualTo(4);
        assertThat(RecommendationService.validCount(20, 11, 10, 10)).isEqualTo(4);
    }
}
