package com.myplatform.backend.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;

import java.util.concurrent.Executor;

/**
 * 한정자 없는 {@code @Async} 의 기본 executor 를 명시적으로 고정한다.
 *
 * <p><b>왜 필요한가.</b> Spring 의 기본 해석은 {@code getBean(TaskExecutor.class)} 다.
 * {@code ThreadPoolTaskScheduler} 는 TaskExecutor 도 구현하고, SchedulingConfig 의
 * {@code taskScheduler} 는 {@code @Primary} 라 이 타입 조회를 그대로 이겨버린다.
 * 그래서 AsyncConfig 가 만들어 둔 {@code Async-} 풀은 기동 로그만 찍고 한 번도 쓰이지 않았고,
 * 한정자 없는 {@code @Async} 12곳이 <b>매매·포지션 감시와 같은 16스레드</b>를 나눠 썼다.
 * SchedulingConfig 가 "장 마감 구간에 트레이딩 슬롯이 밀리지 않게" 풀을 나눈 취지가
 * 비동기 경로에서만 조용히 무너져 있던 셈이다(2026-08-24 실측: {@code scheduled-task-1}).
 *
 * <p><b>왜 AsyncConfig 안에 두지 않는가.</b> {@code AsyncConfigurer} 를 구현한 뒤 자기 클래스의
 * {@code @Bean} 메서드를 직접 호출하는 형태는 이 리포에서 Spring Boot 4.0 부팅 실패를 낸 적이
 * 있다(SchedulingConfig 클래스 주석). 같은 자기참조를 피하려고 설정을 분리하고 주입으로 받는다.
 *
 * <p>{@code @Async("notificationExecutor")} 처럼 한정자가 붙은 곳은 영향 없다.
 *
 * <p>회귀: {@code AsyncExecutorResolutionTest}.
 */
@Configuration
public class AsyncExecutorSelection implements AsyncConfigurer {

    private final Executor asyncExecutor;

    AsyncExecutorSelection(@Qualifier("taskExecutor") Executor taskExecutor) {
        this.asyncExecutor = taskExecutor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return asyncExecutor;
    }
}
