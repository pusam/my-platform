package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * python-backend 헬스 추적 — 연속실패 임계 알림(스팸 방지) + 복구 리셋 + 가용 스냅샷.
 */
class PythonBackendHealthTrackerTest {

    @SuppressWarnings("unchecked")
    private PythonBackendHealthTracker withTelegram(TelegramNotificationService tg) {
        ObjectProvider<TelegramNotificationService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(tg);
        return new PythonBackendHealthTracker(p);
    }

    @Test
    @DisplayName("연속 3회 실패 → 텔레그램 리스크 1회, 이후 추가 실패엔 재알림 안 함")
    void alertsOnceAtThreshold() {
        TelegramNotificationService tg = mock(TelegramNotificationService.class);
        PythonBackendHealthTracker t = withTelegram(tg);

        t.recordFailure("regime", "boom");
        t.recordFailure("regime", "boom");
        verify(tg, never()).sendRisk(anyString());   // 2회까진 알림 없음

        t.recordFailure("regime", "boom");            // 3회 → 알림
        t.recordFailure("regime", "boom");            // 4회 → 재알림 없음(스팸 방지)
        verify(tg, times(1)).sendRisk(anyString());
        assertThat(t.consecutiveFailures("regime")).isEqualTo(4);
    }

    @Test
    @DisplayName("복구(success) 후 다시 연속 3회 → 재알림")
    void reAlertsAfterRecovery() {
        TelegramNotificationService tg = mock(TelegramNotificationService.class);
        PythonBackendHealthTracker t = withTelegram(tg);

        t.recordFailure("chart-timing", "x");
        t.recordFailure("chart-timing", "x");
        t.recordFailure("chart-timing", "x");        // 알림 1
        t.recordSuccess("chart-timing");              // 복구 → consecutive 0, alerted 리셋
        assertThat(t.consecutiveFailures("chart-timing")).isZero();

        t.recordFailure("chart-timing", "x");
        t.recordFailure("chart-timing", "x");
        t.recordFailure("chart-timing", "x");        // 알림 2
        verify(tg, times(2)).sendRisk(anyString());
    }

    @Test
    @DisplayName("텔레그램 빈 빈(미가용)이어도 예외 없이 진행")
    void telegramAbsentNoCrash() {
        PythonBackendHealthTracker t = withTelegram(null);   // getIfAvailable()=null
        t.recordFailure("regime", "x");
        t.recordFailure("regime", "x");
        t.recordFailure("regime", "x");               // 알림 시도하나 telegram null → no-op
        assertThat(t.consecutiveFailures("regime")).isEqualTo(3);
    }

    @Test
    @DisplayName("snapshot — available = 연속실패 0 여부")
    void snapshotAvailability() {
        PythonBackendHealthTracker t = withTelegram(mock(TelegramNotificationService.class));
        t.recordSuccess("regime");
        t.recordFailure("chart-sector", "down");

        Map<String, Object> snap = t.snapshot();
        assertThat(((Map<?, ?>) snap.get("regime")).get("available")).isEqualTo(true);
        assertThat(((Map<?, ?>) snap.get("chart-sector")).get("available")).isEqualTo(false);
        assertThat(((Map<?, ?>) snap.get("chart-sector")).get("consecutiveFailures")).isEqualTo(1L);
    }
}
