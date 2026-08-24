package com.myplatform.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한정자 없는 {@code @Async} 가 어느 풀에서 도는지.
 *
 * <p>SchedulingConfig 의 {@code taskScheduler} 는 {@code @Primary} 이고 ThreadPoolTaskScheduler 는
 * TaskExecutor 도 구현한다. 그래서 Spring 의 기본 executor 해석({@code getBean(TaskExecutor.class)})
 * 이 트레이딩 풀을 집어가고, AsyncConfig 가 만들어 둔 {@code Async-} 풀은 쓰이지 않는다.
 * 결과적으로 한정자 없는 {@code @Async} 작업이 매매·포지션 감시와 같은 16스레드를 나눠 쓴다 —
 * SchedulingConfig 가 "트레이딩 슬롯은 항상 확보한다"며 풀을 분리한 취지가 무너진다.
 */
class AsyncExecutorResolutionTest {

    @Test
    @DisplayName("한정자 없는 @Async 는 Async- 풀에서 돌아야 한다 (트레이딩 스케줄러 아님)")
    void unqualifiedAsyncMustNotBorrowTradingScheduler() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(
                SchedulingConfig.class, AsyncConfig.class, AsyncExecutorSelection.class, AsyncProbe.class)) {

            String thread = ctx.getBean(AsyncProbe.class).whichThread().get(10, TimeUnit.SECONDS);

            assertThat(thread)
                    .as("실제 실행 스레드 — scheduled-task- 로 시작하면 트레이딩 풀을 빌려 쓴 것")
                    .startsWith("Async-");
        }
    }

    @Component
    static class AsyncProbe {
        @Async
        public CompletableFuture<String> whichThread() {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }
    }
}
