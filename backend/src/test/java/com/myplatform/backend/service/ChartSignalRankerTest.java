package com.myplatform.backend.service;

import com.myplatform.backend.service.ChartPatternClient.TimingSignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 차트 타이밍 보조 시그널 랭킹(ChartSignalRanker.topByScore) — 순수함수.
 * 위험필터 제외/미산출/0점 제거 + 점수 desc(동점 ticker asc) 상위 n.
 */
class ChartSignalRankerTest {

    private static TimingSignal sig(String ticker, boolean available, Integer score, boolean riskExcluded) {
        return new TimingSignal(ticker, available, score, riskExcluded, List.of());
    }

    @Test
    @DisplayName("점수 desc 정렬 + 상위 n")
    void sortsByScoreDesc() {
        var out = ChartSignalRanker.topByScore(List.of(
                sig("A", true, 5, false),
                sig("B", true, 9, false),
                sig("C", true, 7, false)), 2);
        assertThat(out).extracting(TimingSignal::ticker).containsExactly("B", "C");
    }

    @Test
    @DisplayName("동점이면 ticker asc (결정성)")
    void tieBreakByTicker() {
        var out = ChartSignalRanker.topByScore(List.of(
                sig("009", true, 8, false),
                sig("001", true, 8, false)), 5);
        assertThat(out).extracting(TimingSignal::ticker).containsExactly("001", "009");
    }

    @Test
    @DisplayName("위험필터 제외/미산출(null)/0점/미가용은 제거")
    void filtersOut() {
        var out = ChartSignalRanker.topByScore(List.of(
                sig("RISK", true, 9, true),     // 위험필터 제외
                sig("NULL", true, null, false), // 미산출
                sig("ZERO", true, 0, false),    // 0점
                sig("NA", false, 7, false),     // 미가용
                sig("OK", true, 3, false)), 10);
        assertThat(out).extracting(TimingSignal::ticker).containsExactly("OK");
    }

    @Test
    @DisplayName("빈 입력/n<=0 → 빈 리스트")
    void edgeCases() {
        assertThat(ChartSignalRanker.topByScore(List.of(), 5)).isEmpty();
        assertThat(ChartSignalRanker.topByScore(List.of(sig("A", true, 5, false)), 0)).isEmpty();
        assertThat(ChartSignalRanker.topByScore(null, 5)).isEmpty();
    }
}
