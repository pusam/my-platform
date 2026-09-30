package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 전략 워밍은 거래정지 게이트가 채워질 때까지 기다린다 — {@link AiStrategySnapshotService#awaitGate}(2026-09-30).
 *
 * <p>2026-09-29 22:54 재시작 뒤 워밍이 게이트보다 13초 먼저 마법의 공식을 돌려 정지 종목 2개가 스윙 1·3위로
 * 저장됐다({@code StockStatusHaltGateLoadedTest} 참조). 기다리되 <b>시한이 있다</b> — 마스터 동기화가 죽으면
 * 게이트는 영영 안 채워지므로, 시한을 넘기면 종전처럼 게이트 없이 수집하고 경고를 남긴다.
 */
class AiStrategyWarmUpGateTest {

    @Test
    @DisplayName("이미 채워져 있으면 기다리지 않는다")
    void readyImmediately() throws InterruptedException {
        AtomicInteger checks = new AtomicInteger();

        boolean loaded = AiStrategySnapshotService.awaitGate(() -> checks.incrementAndGet() > 0, 5, 1);

        assertThat(loaded).isTrue();
        assertThat(checks).hasValue(1);
    }

    @Test
    @DisplayName("몇 번 만에 채워지면 그때 진행한다")
    void becomesReadyWhileWaiting() throws InterruptedException {
        AtomicInteger checks = new AtomicInteger();

        boolean loaded = AiStrategySnapshotService.awaitGate(() -> checks.incrementAndGet() >= 3, 10, 1);

        assertThat(loaded).isTrue();
        assertThat(checks).hasValue(3);
    }

    @Test
    @DisplayName("시한 안에 안 채워지면 false — 무한정 기다리지 않는다")
    void givesUpAfterTheLimit() throws InterruptedException {
        AtomicInteger checks = new AtomicInteger();

        boolean loaded = AiStrategySnapshotService.awaitGate(() -> {
            checks.incrementAndGet();
            return false;
        }, 4, 1);

        assertThat(loaded).isFalse();
        assertThat(checks).as("시한 끝에 한 번 더 확인한다").hasValue(5);
    }
}
