package com.myplatform.backend.controlroom;

import com.myplatform.backend.service.SignalSampleBoundary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 신뢰 게이트(⑦) 표본 경계 설명문 — {@link ControlRoomSnapshotService#sampleBoundaryCaveat}(2026-10-01).
 *
 * <p>시작일은 {@link SignalSampleBoundary} 단일 출처다(화면과 같은 값). 배포일이 아니라 수정이 재무 수집·추천 캐시·
 * 스냅샷까지 반영된 뒤 첫 온전한 거래일이라 <b>기본값이 없다</b>(미정) — 예전엔 코드에 10/2 를 박아 두었는데, 그날 뒤에도
 * 추천 입력이 또 바뀌면(10/1 스크리너 미래 행·AI 순위) 경계가 저절로 틀린 값이 된다.
 */
class ControlRoomTrustGateBoundaryTest {

    private static final ControlRoomSnapshotDto.LegacyReference LEGACY = new ControlRoomSnapshotDto.LegacyReference(
            "2026-06-25", "2026-10-05", 120, 30, 25,
            new BigDecimal("-4.33"), new BigDecimal("0.17"), "EVALUABLE");

    @Test
    @DisplayName("미정 — 시작일을 정하는 기준과 '현재 판정 없음(검증 중)'을 적고, 참고치는 '경계 미정 구간'으로 부른다")
    void unsetBoundary() {
        String text = ControlRoomSnapshotService.sampleBoundaryCaveat(SignalSampleBoundary.Boundary.UNSET, LEGACY);

        assertThat(text)
                .contains("현재 산식 표본 시작일 미정")
                .contains("첫 온전한 거래일")
                .contains("RECOMMENDATION_SAMPLE_SINCE")
                .contains("검증 중")
                .contains("경계 미정 구간(2026-06-25~2026-10-05 전)")
                .doesNotContain("이전 산식(");
    }

    @Test
    @DisplayName("잠정 — 날짜와 '첫 추천 반영 확인 전'을 함께 적는다")
    void provisionalBoundary() {
        var b = new SignalSampleBoundary.Boundary(LocalDate.of(2026, 10, 5), SignalSampleBoundary.Status.PROVISIONAL);

        String text = ControlRoomSnapshotService.sampleBoundaryCaveat(b, LEGACY);

        assertThat(text).contains("2026-10-05 이후 기록분(현재 산식)만(잠정 — 첫 추천 반영 확인 전)")
                .contains("이전 산식(2026-06-25~2026-10-05 전) 120건/고유 30일·대조군 25건(9/1 이후 대조군만)")
                .contains("비용차감 -4.33%")
                .contains("대조군比 +0.17%")
                .contains("현재 판정에 섞지 않는다");
    }

    @Test
    @DisplayName("확정 — 통과 조건은 그대로(30건·10일은 통과 아님)·슬리피지 미포함을 늘 적는다")
    void confirmedBoundary() {
        var b = new SignalSampleBoundary.Boundary(LocalDate.of(2026, 10, 5), SignalSampleBoundary.Status.CONFIRMED);

        String text = ControlRoomSnapshotService.sampleBoundaryCaveat(b, null);

        assertThat(text).contains("2026-10-05 이후 기록분(현재 산식)만(확정)")
                .contains("경계 이전 평가 행 없음")
                .contains("30건·고유 10일을 채워도 통과가 아니다")
                .contains("슬리피지는 빠져 있다");
    }
}
