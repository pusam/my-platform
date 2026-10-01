package com.myplatform.backend.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수급 급증 카드의 '낡음' 판정(2026-10-01 '오늘' 탭 점검).
 *
 * <p>장중 스냅샷은 매 :02·:12…에 수집되는데({@code InvestorSurgeService.collectIntradaySnapshot} 크론 {@code 0 2/10})
 * 시각은 10분 단위로 내려 저장된다(12:32 수집분 = 12:30). 그래서 '10분 이상 = 낡음'이면 매 주기 :x0~:x2 사이에는
 * <b>가장 최신 데이터</b>도 낡음이 되어 화면의 카드 12장이 통째로 흐려졌다(운영 실측 12:31 — 12장 전부, 12:35 — 0장).
 * 낡음은 '한 주기를 건너뛰었다'일 때만이어야 한다.
 */
class InvestorSurgeDtoOutdatedTest {

    private static LocalTime t(int h, int m, int s) {
        return LocalTime.of(h, m, s);
    }

    @Test
    @DisplayName("다음 수집 직전(라벨 12:20, 지금 12:31) — 주기 안의 최신 데이터는 낡음이 아니다")
    void latestDataBeforeNextCollectionIsNotOutdated() {
        assertThat(InvestorSurgeDto.outdatedAt(t(12, 20, 0), t(12, 31, 0))).isFalse();
        // 다음 수집(12:32)이 몇 초 걸리고 화면이 30초마다 다시 불러도 아직 최신이다
        assertThat(InvestorSurgeDto.outdatedAt(t(12, 20, 0), t(12, 33, 30))).isFalse();
    }

    @Test
    @DisplayName("수집 직후(라벨 12:20, 지금 12:22)는 당연히 낡음이 아니다")
    void rightAfterCollectionIsFresh() {
        assertThat(InvestorSurgeDto.outdatedAt(t(12, 20, 0), t(12, 22, 0))).isFalse();
    }

    @Test
    @DisplayName("한 주기를 건너뛰면 낡음 — 12:30 수집분이 12:35 까지 안 들어왔으면 12:20 라벨은 낡았다")
    void missedCycleIsOutdated() {
        assertThat(InvestorSurgeDto.outdatedAt(t(12, 20, 0), t(12, 34, 59))).isFalse();
        assertThat(InvestorSurgeDto.outdatedAt(t(12, 20, 0), t(12, 35, 0))).isTrue();
        assertThat(InvestorSurgeDto.outdatedAt(t(11, 50, 0), t(12, 31, 0))).isTrue();
    }

    @Test
    @DisplayName("시각을 모르면 낡음으로 본다(신선하다고 단정하지 않는다), 자정을 넘겨 음수면 0분")
    void unknownTimeIsOutdatedAndMidnightWrapIsZero() {
        assertThat(InvestorSurgeDto.outdatedAt(null, t(12, 0, 0))).isTrue();
        assertThat(InvestorSurgeDto.outdatedAt(t(23, 58, 0), t(0, 1, 0))).isFalse();
    }
}
