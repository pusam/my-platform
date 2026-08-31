package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V30 카테고리 스냅샷 위생 (AUDIT R7, 2026-08-31) — 순수 함수 테스트.
 *
 * <p>계약: 실점수는 0~20, NULL=미수집(집계 제외). 호출자(RecommendationDto)의 표시 계약
 * sentinel(-1=NA)이 그대로 저장되면 IS NOT NULL 필터를 통과해 실점수 행세를 한다 —
 * prod 실측 수급 36행·기술 16행이 그 상태였다(V58 이 소급 정리).
 */
class SignalOutcomeSanitizeTest {

    @Test
    @DisplayName("음수(-1=NA sentinel)는 NULL 로 — 미수집을 점수로 위장하지 않는다(§4c)")
    void negativeBecomesNull() {
        assertThat(SignalOutcomeService.sanitizeCategorySnapshot(-1)).isNull();
        assertThat(SignalOutcomeService.sanitizeCategorySnapshot(-5)).isNull();
    }

    @Test
    @DisplayName("0 은 보존 — 진짜 약세(채점됨)와 미수집은 다르다")
    void zeroIsPreserved() {
        assertThat(SignalOutcomeService.sanitizeCategorySnapshot(0)).isZero();
    }

    @Test
    @DisplayName("정상 점수(0~20)와 null 은 그대로")
    void validScoresPassThrough() {
        assertThat(SignalOutcomeService.sanitizeCategorySnapshot(20)).isEqualTo(20);
        assertThat(SignalOutcomeService.sanitizeCategorySnapshot(7)).isEqualTo(7);
        assertThat(SignalOutcomeService.sanitizeCategorySnapshot(null)).isNull();
    }
}
