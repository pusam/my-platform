package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관제실이 '입력 노후'와 '관망'을 가르는 기준(2026-11-04) — 11/3·11/4 는 637종목을 정상 채점하고 컷 통과 0건이었는데, 저장된
 * 스냅샷이 없어 날짜가 11/2 로 밀린 것을 '입력 노후 — 가드가 채점 거부'로 표시했다.
 */
class RecommendationScoredNormallyTest {

    @Test
    @DisplayName("후보가 있고 기술 미채점이 절반 미만이면 정상 채점")
    void scoredWithFewStale() {
        assertThat(RecommendationService.scoredNormally(637, 3)).isTrue();
    }

    @Test
    @DisplayName("후보 대부분이 봉 노후면 정상 채점이 아니다")
    void mostlyStaleIsNotNormal() {
        assertThat(RecommendationService.scoredNormally(637, 400)).isFalse();
    }

    @Test
    @DisplayName("후보가 없으면 정상 채점이 아니다")
    void noCandidatesIsNotNormal() {
        assertThat(RecommendationService.scoredNormally(0, 0)).isFalse();
    }
}
