package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 현재 산식 표본 시작일 설정 — {@link SignalSampleBoundary#resolve}(2026-10-01).
 * 기본값이 없다: 시작일은 수정 반영 뒤 첫 온전한 거래일이라 배포 전에 박아 둘 수 없다.
 */
class SignalSampleBoundaryTest {

    @Test
    @DisplayName("비어 있으면 미정 — 코드에 박힌 기본 날짜가 없다")
    void blankIsUnset() {
        assertThat(SignalSampleBoundary.resolve(null, false)).isEqualTo(SignalSampleBoundary.Boundary.UNSET);
        assertThat(SignalSampleBoundary.resolve("  ", true)).isEqualTo(SignalSampleBoundary.Boundary.UNSET);
        assertThat(SignalSampleBoundary.Boundary.UNSET.isSet()).isFalse();
    }

    @Test
    @DisplayName("날짜가 아니면 미정 — 잘못된 값이 엉뚱한 경계가 되지 않는다")
    void invalidIsUnset() {
        assertThat(SignalSampleBoundary.resolve("어제", true).isSet()).isFalse();
        assertThat(SignalSampleBoundary.resolve("2026/10/05", false).isSet()).isFalse();
    }

    @Test
    @DisplayName("날짜만 넣으면 잠정, confirmed=true 면 확정")
    void provisionalAndConfirmed() {
        var p = SignalSampleBoundary.resolve(" 2026-10-05 ", false);
        assertThat(p.since()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(p.status()).isEqualTo(SignalSampleBoundary.Status.PROVISIONAL);
        assertThat(p.isSet()).isTrue();

        var c = SignalSampleBoundary.resolve("2026-10-05", true);
        assertThat(c.status()).isEqualTo(SignalSampleBoundary.Status.CONFIRMED);
    }
}
