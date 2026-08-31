package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 일봉 갱신 대상 공정 선정 (AUDIT R6, 2026-08-31) — 순수 함수 테스트.
 *
 * <p>prod 실측: 하루 대상 879~1,123종목 vs 상한 400. 결정적 절단(항상 같은 앞 400)이면
 * 나머지 ~700종목이 <b>영구 미확정 봉</b>으로 굶주린다 — 셔플로 며칠에 걸쳐 돌아가게 한다.
 */
class DailyBarRefreshSelectionTest {

    private static final List<String> CODES = IntStream.range(0, 1100)
            .mapToObj(i -> String.format("%06d", i)).toList();

    @Test
    @DisplayName("상한 이하면 전수 그대로 — 셔플 불필요")
    void underCapReturnsAll() {
        List<String> few = List.of("005930", "000660");
        assertThat(DailyBarRefreshService.selectFairly(few, 400, new Random(1))).isEqualTo(few);
    }

    @Test
    @DisplayName("상한 초과면 정확히 상한 개수만, 중복 없이")
    void overCapReturnsExactlyCap() {
        List<String> picked = DailyBarRefreshService.selectFairly(CODES, 400, new Random(1));
        assertThat(picked).hasSize(400);
        assertThat(Set.copyOf(picked)).hasSize(400);
        assertThat(CODES).containsAll(picked);
    }

    @Test
    @DisplayName("R6 회귀 — 실행마다 다른 부분집합(결정적 꼬리 절단이면 영구 굶주림)")
    void differentRunsPickDifferentSubsets() {
        Set<String> a = Set.copyOf(DailyBarRefreshService.selectFairly(CODES, 400, new Random(1)));
        Set<String> b = Set.copyOf(DailyBarRefreshService.selectFairly(CODES, 400, new Random(2)));
        // 두 무작위 400개가 완전히 같을 수는 없다 — 결정적 절단이었다면 항상 동일했을 것
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("여러 번 실행하면 결국 전 종목이 뽑힌다 — 굶주림 없음")
    void everyCodeEventuallyPicked() {
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        Random r = new Random(42);
        for (int day = 0; day < 30; day++) {
            seen.addAll(DailyBarRefreshService.selectFairly(CODES, 400, r));
        }
        // 30회면 미채택 확률 (1-400/1100)^30 ≈ 1.6e-6 — 전 종목 커버를 기대할 수 있다
        assertThat(seen).hasSize(CODES.size());
    }
}
