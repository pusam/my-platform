package com.myplatform.backend.youtubeopinion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 한 시간 슬라이딩 창 처리량 제한 — 관리자 자막 등록·분석 요청이 폭주해 Gemini 전역 제한기를 독점하지 않게.
 * 단일 인스턴스 전제(CLAUDE.md §5)라 메모리로 충분하다.
 */
final class HourlyThroughputLimit {

    private final int maxPerHour;
    private final Clock clock;
    private final Deque<Instant> times = new ArrayDeque<>();

    HourlyThroughputLimit(int maxPerHour, Clock clock) {
        this.maxPerHour = maxPerHour;
        this.clock = clock;
    }

    /** 자리가 있으면 기록하고 true, 없으면 false(기록하지 않음). */
    synchronized boolean tryAcquire() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(Duration.ofHours(1));
        while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) times.pollFirst();
        if (times.size() >= maxPerHour) return false;
        times.addLast(now);
        return true;
    }

    int maxPerHour() { return maxPerHour; }
}
