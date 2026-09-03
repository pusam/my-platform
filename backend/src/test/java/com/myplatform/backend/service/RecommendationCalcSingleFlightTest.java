package com.myplatform.backend.service;

import com.myplatform.backend.util.SingleFlight;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 종합추천 계산 단일 비행 — {@code RecommendationService.startOrJoinCalculation()} 이 쓰는 {@link SingleFlight}.
 *
 * <p>고치려는 결함(2026-09-03 prod 실측): 09:00:00 에 {@code detectAndAlertNewStrongBuys} 크론이
 * {@code calculate()} 를 직접 호출하고, 같은 초에 다른 잡의 {@code getTop5()} 가 stale 캐시를 보고
 * 백그라운드 계산({@code rec-calc})을 띄웠다. 백그라운드 가드(AtomicBoolean)는 백그라운드끼리만 막아
 * 둘 다 완주 → 모든 {@code [종합추천]} 로그가 두 스레드로 같은 ms 에 찍히고, 가격히스토리 수집기가
 * 두 번 예약돼 652종목이 KIS 에서 2회씩(1,310건, 09:00~09:08) 조회됐다.
 *
 * <p>서비스 자체는 단위 테스트에서 못 만든다(ObjectProvider 2개 — 생성자 주입 불가, {@code calculate()} 는
 * private + 실 의존 20여 개). 그래서 두 경로가 공유하는 자료구조를 고정한다 — 여기가 깨지면 그 결함이 돌아온다.
 */
class RecommendationCalcSingleFlightTest {

    private static final List<String> RESULT = List.of("005930", "000660");

    /** 크론 스레드와 백그라운드 스레드가 같은 순간 들어오는 모양 그대로. */
    @Test
    @DisplayName("크론 + 백그라운드가 같은 순간 요청 → 계산 1회, 둘 다 같은 결과")
    void cronAndBackgroundShareOneCalculation() throws Exception {
        SingleFlight<List<String>> flight = new SingleFlight<>();
        AtomicInteger calculations = new AtomicInteger();
        AtomicInteger runnerCalls = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);

        java.util.function.Supplier<List<String>> work = () -> {
            calculations.incrementAndGet();
            sleep(300);   // 실제 calculate() 동안 다른 호출자가 겹치도록
            return RESULT;
        };
        java.util.function.Consumer<Runnable> runner = task -> {
            runnerCalls.incrementAndGet();
            new Thread(task, "rec-calc").start();
        };

        CompletableFuture<List<String>>[] futures = new CompletableFuture[2];
        Thread cron = new Thread(() -> { await(go); futures[0] = flight.startOrJoin(work, runner); }, "batch-sched");
        Thread background = new Thread(() -> { await(go); futures[1] = flight.startOrJoin(work, runner); }, "getTop5");
        cron.start();
        background.start();
        go.countDown();
        cron.join(2000);
        background.join(2000);

        assertThat(futures[0].get(5, TimeUnit.SECONDS)).isEqualTo(RESULT);
        assertThat(futures[1].get(5, TimeUnit.SECONDS)).isEqualTo(RESULT);
        assertThat(futures[0]).as("합류자는 시작자의 Future 를 그대로 받는다").isSameAs(futures[1]);
        assertThat(calculations.get()).as("동시 요청이 각자 calculate() 를 돌리면 안 된다").isEqualTo(1);
        assertThat(runnerCalls.get()).as("합류자에게는 runner(새 스레드) 가 호출되지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("이전 계산이 끝난 뒤의 요청은 새로 계산한다 — 영구 캐시가 아니다")
    void finishedFlightDoesNotServeStaleResult() throws Exception {
        SingleFlight<Integer> flight = new SingleFlight<>();
        AtomicInteger calculations = new AtomicInteger();

        Integer first = flight.startOrJoin(calculations::incrementAndGet, Runnable::run).get(1, TimeUnit.SECONDS);
        Integer second = flight.startOrJoin(calculations::incrementAndGet, Runnable::run).get(1, TimeUnit.SECONDS);

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        assertThat(flight.isInFlight()).isFalse();
    }

    @Test
    @DisplayName("계산이 예외로 끝나면 합류자도 예외를 받고(실패≠빈 결과), 다음 요청은 새로 시작한다")
    void failurePropagatesAndClearsFlight() throws Exception {
        SingleFlight<Integer> flight = new SingleFlight<>();
        AtomicInteger calculations = new AtomicInteger();

        CompletableFuture<Integer> failed = flight.startOrJoin(() -> {
            calculations.incrementAndGet();
            throw new IllegalStateException("KIS 장애");
        }, Runnable::run);

        assertThatThrownBy(() -> failed.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(flight.isInFlight()).as("실패한 비행이 다음 요청을 막으면 안 된다").isFalse();

        Integer next = flight.startOrJoin(calculations::incrementAndGet, Runnable::run).get(1, TimeUnit.SECONDS);
        assertThat(next).isEqualTo(2);
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
