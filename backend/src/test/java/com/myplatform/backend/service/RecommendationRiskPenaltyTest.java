package com.myplatform.backend.service;

import com.myplatform.backend.service.RecommendationService.StockScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * composite 리스크 공시 페널티 실효화 (2026-07-28).
 *
 * <p>버그 재현: 기존 applyRiskPenalty 는 valueStability(-5)만 깎았는데 valueStability 는
 * composite raw 합산(당시 80점 분모 — 2026-11-10 부터 실제 만점 58)에 없어 <b>랭킹·총점에 실효 0</b>이었다(⚠리스크공시 태그만 붙음).
 * 수정 후: {@code StockScore.riskPenalty}(5)가 수급 캡과 동일하게 composite raw 3지점
 * (필터/toDto/getNormalizedTotal)에서 차감된다. 카테고리 표시값은 불변.
 */
class RecommendationRiskPenaltyTest {

    private StockScore score(int e, int sd, int tc, int sc) {
        StockScore s = new StockScore("TEST", "테스트");
        s.earnings = e;
        s.supplyDemand = sd;
        s.technical = tc;
        s.sectorMomentum = sc;
        return s;
    }

    @Test
    @DisplayName("riskPenalty=5 → getNormalizedTotal 이 raw −5 만큼 낮아진다")
    void penaltyLowersNormalizedTotal() {
        StockScore clean = score(16, 10, 16, 8);
        StockScore risky = score(16, 10, 16, 8);
        risky.riskPenalty = 5;

        int cleanTotal = clean.getNormalizedTotal();
        int riskyTotal = risky.getNormalizedTotal();

        assertThat(riskyTotal).isLessThan(cleanTotal);
        // raw 50 → 45: normalize(50,4)=86, normalize(45,4)=77 (분모 = 실제 만점 58, 2026-11-10)
        assertThat(cleanTotal - riskyTotal).isGreaterThanOrEqualTo(5); // ≈ 5/58×100
    }

    @Test
    @DisplayName("riskPenalty 는 validCount 에 영향 없음 (카테고리 값 불변)")
    void penaltyDoesNotAffectValidCount() {
        StockScore risky = score(16, 10, 16, 8);
        risky.riskPenalty = 5;
        assertThat(RecommendationService.validCount(
                risky.earnings, risky.supplyDemand, risky.technical, risky.sectorMomentum)).isEqualTo(4);
    }

    @Test
    @DisplayName("raw 가 페널티보다 작아도 음수로 내려가지 않는다 (clamp 0)")
    void penaltyClampsAtZero() {
        StockScore tiny = score(2, 0, 1, 0);
        tiny.riskPenalty = 5;
        assertThat(tiny.getNormalizedTotal()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("riskPenalty 기본값 0 — 페널티 없는 종목은 기존 산식 그대로")
    void defaultNoPenalty() {
        StockScore s = score(16, 10, 16, 8);
        // raw = 16+10+16+8 = 50 (수급 캡 10: sd=10 은 캡 미발동) → 50×100/58(실제 만점, 2026-11-10) = 86
        assertThat(s.getNormalizedTotal()).isEqualTo(86);
    }
}
