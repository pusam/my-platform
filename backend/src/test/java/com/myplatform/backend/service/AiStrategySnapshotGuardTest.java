package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스냅샷 정리 안전장치 ({@link AiStrategySnapshotService#snapshotPipelineAlive}).
 *
 * <p>스냅샷 생성은 평일 장중만 돈다(cron {@code "0 0,30 8-19 * * MON-FRI"}) — 한 주의 마지막
 * 스냅샷은 금요일 19:30 이다. 반면 정리는 매일 06:50 에 돈다. 가드 기준이 "최근 24시간"이던
 * 시절엔 일요일·월요일 아침과 연휴 다음날에 <b>정상인데도</b> 걸려서 정리가 중단되고
 * log.error 만 주 2회 쌓였다. 기준은 시계 24시간이 아니라 직전 거래일이어야 한다.
 */
class AiStrategySnapshotGuardTest {

    /** 8/21(금) 장 마감 뒤 마지막 스냅샷. */
    private static final LocalDateTime FRIDAY_LAST = LocalDateTime.of(2026, 8, 21, 19, 30);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 8, 21);

    @Test
    @DisplayName("직전 거래일치가 있으면 살아있다 — 일/월 아침, 연휴 다음날 모두 해당")
    void snapshotFromPreviousTradingDayIsAlive() {
        // 일요일·월요일 06:50 정리도, 연휴로 며칠 건너뛴 뒤의 정리도 직전 거래일은 여전히 8/21(금).
        // 시계로는 35시간·59시간·그 이상 전이지만 거래일 기준으론 바로 직전이다.
        assertThat(AiStrategySnapshotService.snapshotPipelineAlive(FRIDAY_LAST, FRIDAY)).isTrue();
    }

    @Test
    @DisplayName("당일치가 이미 쌓였으면 당연히 살아있다")
    void snapshotNewerThanPreviousTradingDayIsAlive() {
        LocalDateTime todayMorning = LocalDateTime.of(2026, 8, 25, 8, 30);
        assertThat(AiStrategySnapshotService.snapshotPipelineAlive(todayMorning, LocalDate.of(2026, 8, 24))).isTrue();
    }

    @Test
    @DisplayName("직전 거래일치가 통째로 비었으면 죽은 것 — 정리 중단(보호 목적 유지)")
    void missingPreviousTradingDaySnapshotIsDead() {
        LocalDate monday = LocalDate.of(2026, 8, 24);
        assertThat(AiStrategySnapshotService.snapshotPipelineAlive(FRIDAY_LAST, monday))
                .as("월요일치가 하나도 없다 = 생성이 멈춘 것")
                .isFalse();
    }

    @Test
    @DisplayName("스냅샷이 아예 없으면 죽은 것")
    void noSnapshotAtAllIsDead() {
        assertThat(AiStrategySnapshotService.snapshotPipelineAlive(null, FRIDAY)).isFalse();
    }
}
