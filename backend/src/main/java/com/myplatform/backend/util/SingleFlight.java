package com.myplatform.backend.util;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 단일 비행(single flight) — 같은 계산을 동시에 요청한 호출자들이 <b>한 번의 실행</b>을 공유한다.
 *
 * <p>진행 중인 실행이 있으면 그 Future 를 돌려주고, 없으면 {@code work} 를 {@code runner} 로 시작해
 * 새 Future 를 돌려준다. 실행이 끝나면(정상·예외 모두) 다음 호출은 새로 시작한다.
 * 캐시 발행 같은 부수효과는 {@code work} 안에 두어 "누가 시작했든 한 번만" 일어나게 한다.
 *
 * <p>왜 AtomicBoolean 가드가 아닌가: "진행 중이면 return" 뿐인 가드는 결과가 필요한 호출자에게
 * 아무것도 주지 못해, 그 호출자는 가드를 우회해 직접 계산하게 된다 — 종합추천 09:00 크론이
 * 백그라운드 계산과 나란히 돌아 656종목을 KIS 에서 2회씩 조회한 원인(2026-09-03).
 * 결과를 Future 로 공유하면 우회할 이유가 없다. 순수 자료구조 — {@code SingleFlightTest}.
 */
public final class SingleFlight<T> {

    private final Object lock = new Object();
    private CompletableFuture<T> inFlight;

    /**
     * 진행 중인 실행에 합류하거나 새 실행을 시작한다.
     *
     * @param work   실제 계산(부수효과 포함). 시작자 쪽에서만 1회 실행된다.
     * @param runner {@code work} 를 어디서 돌릴지(새 스레드·풀 등). 합류자에게는 호출되지 않는다.
     */
    public CompletableFuture<T> startOrJoin(Supplier<T> work, Consumer<Runnable> runner) {
        synchronized (lock) {
            if (inFlight != null && !inFlight.isDone()) {
                return inFlight;
            }
            CompletableFuture<T> future = new CompletableFuture<>();
            inFlight = future;
            runner.accept(() -> {
                try {
                    future.complete(work.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future;
        }
    }

    /** 진행 중인 실행이 있는지 — 진단용. */
    public boolean isInFlight() {
        synchronized (lock) {
            return inFlight != null && !inFlight.isDone();
        }
    }
}
