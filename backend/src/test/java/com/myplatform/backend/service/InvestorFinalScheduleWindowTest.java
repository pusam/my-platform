package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KIS 종목별 투자자 매매(FHPTJ04160001)는 00:00~15:40 엔 거부한다 — 2026-11-09 07:50 첫 아침 회차가 30건 전부
 * 'TIME LIMIT 00:00 ~ 15:40' 으로 거부됐고 연속 실패로 멈췄다(16:15·18:10 회차는 받는다).
 * 이 API 를 부르는 회차는 15:40 이후 자정 전에만 둔다.
 */
class InvestorFinalScheduleWindowTest {

    /** KIS 가 이 API 를 받기 시작하는 시각 — 응답 문구 그대로. */
    private static final LocalTime KIS_OPENS = LocalTime.of(15, 40);

    @Test
    @DisplayName("재현: 수급 확정치 크론은 전부 15:40 이후다 — 07:50 아침 회차는 KIS 가 거부해 한 번도 받지 못했다")
    void everyCronIsInsideKisWindow() {
        List<String> outside = new ArrayList<>();
        int crons = 0;
        for (Method m : InvestorFinalDataService.class.getDeclaredMethods()) {
            Scheduled s = m.getAnnotation(Scheduled.class);
            if (s == null || s.cron().isEmpty()) continue;
            crons++;
            CronExpression cron = CronExpression.parse(s.cron());
            LocalDateTime t = LocalDateTime.of(2026, 11, 8, 0, 0);   // 일요일 자정부터 평일 다섯 번 넘게
            for (int i = 0; i < 10; i++) {
                t = cron.next(t);
                if (t.toLocalTime().isBefore(KIS_OPENS)) outside.add(m.getName() + " " + t.toLocalTime());
            }
        }
        assertThat(crons).isGreaterThan(0);
        assertThat(outside).isEmpty();
    }
}
