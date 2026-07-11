package com.myplatform.backend.service;

import com.myplatform.backend.entity.RecommendationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB 복원(loadFromDb) validCount = compute 경로(countValidCategories)와 동일하게 4 core 만 — 파리티 고정.
 *
 * <p>배경(2026-07-11 감사): 복원 경로가 valueStability(&gt;0)를 추가로 세어, 같은 스냅샷이
 * 장중(compute) "유효 4개" → 재시작 복원 후 "유효 5개"로 갈라지는 표시 불일치가 있었다.
 * validCount 의 분모는 4카테고리(80=4×20) — valueStability/growth 는 별도 트랙 점수라 미포함.
 */
class RecommendationRestoredValidCountTest {

    private RecommendationSnapshot snapshot(int e, int sd, int tc, int sc, Integer value, Integer growth) {
        RecommendationSnapshot s = new RecommendationSnapshot();
        s.setStockCode("005930");
        s.setStockName("삼성전자");
        s.setEarnings(e);
        s.setSupplyDemand(sd);
        s.setTechnical(tc);
        s.setSectorMomentum(sc);
        s.setValueStability(value);
        s.setGrowth(growth);
        return s;
    }

    @Test
    @DisplayName("4 core 전부 유효 + valueStability=15 → 4 (가치 점수는 validCount 미포함 — compute 경로 파리티)")
    void valueStabilityDoesNotInflateValidCount() {
        assertThat(RecommendationService.restoredValidCount(snapshot(19, 10, 13, 8, 15, 12)))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("valueStability NULL(NA, P3-3/V48) → 동일하게 4 core 만")
    void nullValueStabilitySafe() {
        assertThat(RecommendationService.restoredValidCount(snapshot(19, 10, 13, 8, null, null)))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("core 1개 결측(0) → 3 — validCount≥3 게이트 분모 시맨틱 유지")
    void zeroCategoryNotCounted() {
        assertThat(RecommendationService.restoredValidCount(snapshot(19, 0, 13, 8, 15, null)))
                .isEqualTo(3);
    }
}
