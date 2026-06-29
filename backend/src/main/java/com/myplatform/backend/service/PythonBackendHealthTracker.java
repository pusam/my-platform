package com.myplatform.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * python-backend 호출 헬스 추적 — best-effort 클라이언트({@link MarketRegimeClient}/{@link ChartPatternClient})가
 * 조용히 죽는 걸 가시화한다. 소스별(regime/chart-timing/chart-sector) 성공·실패·연속실패를 집계해
 * {@code /api/diagnostics/python-health} 로 노출하고, 연속실패가 임계({@value #CONSECUTIVE_FAILURE_ALERT_THRESHOLD})를
 * 넘으면 텔레그램 리스크 채널로 1회 알림(복구 시 리셋).
 *
 * <p>왜 필요한가: 차트 타이밍 베타 섹션이 빈 게 "신호 없음"인지 "분석서버 다운"인지 구분 못 하면
 * P2-12 백테스트 신뢰성이 훼손된다. null 하나로 뭉개지 말 것.
 */
@Service
@Slf4j
public class PythonBackendHealthTracker {

    static final int CONSECUTIVE_FAILURE_ALERT_THRESHOLD = 3;

    public static final String SOURCE_REGIME = "regime";
    public static final String SOURCE_TIMING = "chart-timing";
    public static final String SOURCE_SECTOR = "chart-sector";

    private final ObjectProvider<TelegramNotificationService> telegramProvider;
    private final Map<String, Stat> stats = new ConcurrentHashMap<>();

    public PythonBackendHealthTracker(ObjectProvider<TelegramNotificationService> telegramProvider) {
        this.telegramProvider = telegramProvider;
    }

    private static final class Stat {
        long total;
        long failures;
        long consecutive;
        Instant lastSuccessAt;
        Instant lastFailureAt;
        String lastError;
        boolean alerted;
    }

    public void recordSuccess(String source) {
        Stat s = stats.computeIfAbsent(source, k -> new Stat());
        synchronized (s) {
            s.total++;
            s.consecutive = 0;
            s.lastSuccessAt = Instant.now();
            s.alerted = false;   // 복구 → 다음 연속실패 시 다시 알림 가능
        }
    }

    public void recordFailure(String source, String error) {
        Stat s = stats.computeIfAbsent(source, k -> new Stat());
        boolean fireAlert = false;
        synchronized (s) {
            s.total++;
            s.failures++;
            s.consecutive++;
            s.lastFailureAt = Instant.now();
            s.lastError = error;
            if (s.consecutive >= CONSECUTIVE_FAILURE_ALERT_THRESHOLD && !s.alerted) {
                s.alerted = true;       // 임계 도달 1회만 — 복구 전까지 재알림 안 함(스팸 방지)
                fireAlert = true;
            }
        }
        if (fireAlert) {
            TelegramNotificationService tg = telegramProvider.getIfAvailable();
            if (tg != null) {
                tg.sendRisk(String.format(
                        "⚠️ [python-backend] %s 연속 %d회 실패 — 분석서버(pykrx) 점검 필요. 마지막 오류: %s",
                        source, CONSECUTIVE_FAILURE_ALERT_THRESHOLD, error));
            }
            log.warn("[PythonHealth] {} 연속 {}회 실패 — 리스크 알림 발송. 마지막 오류: {}",
                    source, CONSECUTIVE_FAILURE_ALERT_THRESHOLD, error);
        }
    }

    /** 운영 진단용 스냅샷 — 비밀 없음(permitAll 노출 가능). available=직전 호출 성공 여부. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        stats.forEach((src, s) -> {
            synchronized (s) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("total", s.total);
                m.put("failures", s.failures);
                m.put("consecutiveFailures", s.consecutive);
                m.put("available", s.consecutive == 0);   // 연속실패 0 = 직전 성공 = 가용
                m.put("lastSuccessAt", s.lastSuccessAt != null ? s.lastSuccessAt.toString() : null);
                m.put("lastFailureAt", s.lastFailureAt != null ? s.lastFailureAt.toString() : null);
                m.put("lastError", s.lastError);
                out.put(src, m);
            }
        });
        return out;
    }

    /** 테스트용 — 소스의 연속실패 카운트. */
    long consecutiveFailures(String source) {
        Stat s = stats.get(source);
        if (s == null) return 0;
        synchronized (s) { return s.consecutive; }
    }
}
