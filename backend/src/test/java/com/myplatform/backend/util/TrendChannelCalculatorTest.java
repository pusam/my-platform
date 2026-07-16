package com.myplatform.backend.util;

import com.myplatform.backend.util.TrendChannelCalculator.Bar;
import com.myplatform.backend.util.TrendChannelCalculator.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추세 채널(회귀 채널) 순수 계산 — 프론트 trendChannel.js 와 동일 산식.
 * 표시 전용(② 참고 계층) — 점수/시그널 산식 미편입 전제의 계산 정확성만 검증.
 */
class TrendChannelCalculatorTest {

    /** 등차 시계열 봉 생성 — close = base + step*i, high/low = close ± spread. 과거→최신 순. */
    private static List<Bar> bars(int n, double base, double step, double spread) {
        List<Bar> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double close = base + step * i;
            list.add(new Bar(close + spread, close - spread, close));
        }
        return list;
    }

    @Test
    @DisplayName("명확한 상승 추세 → UP + 기울기 %/봉 양수")
    void uptrend_up() {
        Channel ch = TrendChannelCalculator.compute(bars(30, 100, 1, 1.5));   // ≈ +0.9%/봉
        assertThat(ch).isNotNull();
        assertThat(ch.direction()).isEqualTo("UP");
        assertThat(ch.slopePctPerBar()).isGreaterThan(TrendChannelCalculator.SLOPE_FLAT_THRESHOLD);
    }

    @Test
    @DisplayName("명확한 하락 추세 → DOWN")
    void downtrend_down() {
        Channel ch = TrendChannelCalculator.compute(bars(30, 200, -1, 2));
        assertThat(ch.direction()).isEqualTo("DOWN");
    }

    @Test
    @DisplayName("횡보(기울기 ≈0) → FLAT + 대칭 spread 면 position ≈ 0.5")
    void flat_box() {
        Channel ch = TrendChannelCalculator.compute(bars(30, 100, 0, 2));
        assertThat(ch.direction()).isEqualTo("FLAT");
        assertThat(ch.position()).isBetween(0.4, 0.6);
    }

    @Test
    @DisplayName("position: 저가 꼬리가 길면(하단이 멀면) 종가는 상단 쪽 — 0~1 clamp")
    void position_asymmetric() {
        List<Bar> data = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            double close = 100 + i;
            data.add(new Bar(close + 0.5, close - 4, close));
        }
        Channel ch = TrendChannelCalculator.compute(data);
        assertThat(ch.position()).isGreaterThan(0.7).isLessThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("widthPct = (상단−하단)/중심선×100 — 대칭 spread 2, 중심선 100 → 폭 4%")
    void widthPct_symmetric() {
        // close=100(평탄), high=102/low=98 → 상단오프셋 2 + 하단오프셋 2 = span 4, 중심선(midEnd) 100 → 4/100×100 = 4%
        Channel ch = TrendChannelCalculator.compute(bars(30, 100, 0, 2));
        assertThat(ch).isNotNull();
        assertThat(ch.widthPct()).isCloseTo(4.0, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    @DisplayName("widthPct: 중심선이 높을수록(같은 절대폭) 폭 % 는 작아진다 — 정규화 확인")
    void widthPct_normalizedByCenter() {
        // 같은 절대 spread(2)라도 가격대(중심선)가 높으면 폭 % 는 작아야 한다.
        double low = TrendChannelCalculator.compute(bars(30, 100, 0, 2)).widthPct();   // 중심선 100
        double high = TrendChannelCalculator.compute(bars(30, 1000, 0, 2)).widthPct(); // 중심선 1000
        assertThat(high).isLessThan(low);
        assertThat(high).isCloseTo(0.4, org.assertj.core.api.Assertions.within(1e-9)); // 4/1000×100
    }

    @Test
    @DisplayName("데이터 부족(<MIN_BARS)/null → null (§4c 위장 금지)")
    void insufficient_null() {
        assertThat(TrendChannelCalculator.compute(bars(TrendChannelCalculator.MIN_BARS - 1, 100, 1, 1))).isNull();
        assertThat(TrendChannelCalculator.compute(List.of())).isNull();
        assertThat(TrendChannelCalculator.compute(null)).isNull();
    }

    @Test
    @DisplayName("비정상 가격(0/NaN) 포함 → null")
    void invalidPrice_null() {
        List<Bar> bad = bars(15, 100, 1, 1);
        bad.set(7, new Bar(1, 0, 0));
        assertThat(TrendChannelCalculator.compute(bad)).isNull();

        List<Bar> nan = bars(15, 100, 1, 1);
        nan.set(3, new Bar(Double.NaN, 99, 100));
        assertThat(TrendChannelCalculator.compute(nan)).isNull();
    }
}
