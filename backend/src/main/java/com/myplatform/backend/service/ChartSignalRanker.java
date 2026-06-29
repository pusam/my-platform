package com.myplatform.backend.service;

import com.myplatform.backend.service.ChartPatternClient.TimingSignal;

import java.util.Comparator;
import java.util.List;

/**
 * 차트 타이밍 보조 시그널 랭킹 — 순수함수(테스트 대상).
 *
 * 발굴 '추세눌림(보조)' 트랙용. 위험필터 제외/미산출(score=null) 종목은 빼고,
 * 타이밍 점수 desc(동점이면 ticker asc 로 결정성)로 상위 n 선별.
 * 산식 미검증 — 보조 시그널 전용.
 */
public final class ChartSignalRanker {

    private ChartSignalRanker() {}

    public static List<TimingSignal> topByScore(List<TimingSignal> signals, int n) {
        if (signals == null || n <= 0) return List.of();
        return signals.stream()
                .filter(s -> s != null && s.available() && !s.riskExcluded())
                .filter(s -> s.timingScore() != null && s.timingScore() > 0)
                .sorted(Comparator
                        .comparingInt(TimingSignal::timingScore).reversed()
                        .thenComparing(s -> s.ticker() == null ? "" : s.ticker()))
                .limit(n)
                .toList();
    }
}
