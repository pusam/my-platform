package com.myplatform.backend.controlroom;

import com.myplatform.backend.entity.CrewSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * stale RUNNING 세션 밀어내기 판정 — {@link CrewOrchestrationService} 의 순수 헬퍼 2개.
 *
 * <p><b>왜 필요한가</b>: 파이프라인 스레드가 Error 로 죽는 등 프로세스는 살아 있는데 세션만
 * RUNNING 으로 남으면, 기동 시 고아 정리(재시작 필요) 전까지 동시 1건 가드가 영구 잠긴다.
 * 실제로 2026-08-25 "채팅창 활성화가 안 된다" 문의의 후보 원인이 이 시나리오였다.
 *
 * <p>가장 위험한 오동작은 <b>정상 진행 중인 세션을 밀어내는 것</b>(이중 실행 = 이중 과금)이라,
 * 임계가 5턴 최악 소요보다 항상 크게 보정되는지가 핵심 검증이다.
 */
class CrewStaleGuardTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 25, 12, 0);

    private static CrewSession runningSince(LocalDateTime startedAt) {
        return CrewSession.builder()
                .status(CrewSession.Status.RUNNING)
                .startedAt(startedAt)
                .build();
    }

    @Test
    @DisplayName("임계는 항상 5턴 최악 소요(5×턴 타임아웃 +5분)보다 작지 않다 — 정상 세션 오살 방지")
    void staleFloorAlwaysCoversWorstCaseRun() {
        // 기본값: 300초 × 5턴 = 25분 + 5분 여유 = 30분 → 설정 30분과 같음
        assertThat(CrewOrchestrationService.effectiveStaleMinutes(300, 30)).isEqualTo(30);

        // 운영자가 턴 타임아웃을 600초로 올렸는데 stale 설정을 안 고친 경우 —
        // 최악 소요(55분)가 설정(30분)을 이겨야 한다. 아니면 정상 세션이 밀려난다.
        assertThat(CrewOrchestrationService.effectiveStaleMinutes(600, 30)).isEqualTo(55);

        // 반대로 설정을 넉넉히 준 경우는 설정이 이긴다.
        assertThat(CrewOrchestrationService.effectiveStaleMinutes(300, 90)).isEqualTo(90);
    }

    @Test
    @DisplayName("임계를 넘긴 세션만 stale — 경계 직전은 살아 있다고 본다")
    void staleOnlyBeyondThreshold() {
        assertThat(CrewOrchestrationService.isStale(
                runningSince(NOW.minusMinutes(31)), NOW, 30)).isTrue();
        assertThat(CrewOrchestrationService.isStale(
                runningSince(NOW.minusMinutes(29)), NOW, 30)).isFalse();
        // 정확히 임계 = 아직 stale 아님 (isBefore, 경계 미포함)
        assertThat(CrewOrchestrationService.isStale(
                runningSince(NOW.minusMinutes(30)), NOW, 30)).isFalse();
    }

    @Test
    @DisplayName("startedAt 이 없는 비정상 행은 즉시 stale — 판정 불가로 영구 잠금되는 것보다 낫다")
    void missingStartedAtIsStale() {
        CrewSession broken = CrewSession.builder()
                .status(CrewSession.Status.RUNNING)
                .build();

        assertThat(CrewOrchestrationService.isStale(broken, NOW, 30)).isTrue();
    }
}
