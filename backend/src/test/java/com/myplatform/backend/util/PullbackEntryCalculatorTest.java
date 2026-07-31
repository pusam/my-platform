package com.myplatform.backend.util;

import com.myplatform.backend.util.PullbackEntryCalculator.Bar;
import com.myplatform.backend.util.PullbackEntryCalculator.Metrics;
import com.myplatform.backend.util.PullbackEntryCalculator.OverheadSupply;
import com.myplatform.backend.util.PullbackEntryCalculator.SupplyBin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 눌림목 진입 지표(측정 전용 뼈대) 순수 계산 검증.
 *
 * <p>임계 상수는 잠정값이라 "값이 얼마인가"가 아니라 <b>판정 구조</b>(터치/미터치, 첫 반등/재반등,
 * 장대음봉 경과, 머리 위 매물벽)가 의도대로 갈리는지만 검증한다.
 * 결측 규약(§4c) — 데이터 부족은 Metrics 통째로 null, 필드 null 은 "사건 없음"이라는 의미 있는 값.
 */
class PullbackEntryCalculatorTest {

    /** 평탄한 시계열 — close 고정, high/low = close ± spread. 과거→최신 순. */
    private static List<Bar> flat(int n, double close, double spread) {
        List<Bar> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Bar(close, close + spread, close - spread, close));
        }
        return list;
    }

    /** 등차 시계열 — close = base + step*i. */
    private static List<Bar> ramp(int n, double base, double step, double spread) {
        List<Bar> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double c = base + step * i;
            list.add(new Bar(c, c + spread, c - spread, c));
        }
        return list;
    }

    // ========== 결측 규약 ==========

    @Test
    @DisplayName("봉 수 부족(20 미만) → Metrics 통째로 null (§4c 결측)")
    void insufficientBars_null() {
        assertThat(PullbackEntryCalculator.compute(ramp(19, 100, 1, 1))).isNull();
        assertThat(PullbackEntryCalculator.compute(null)).isNull();
    }

    @Test
    @DisplayName("가격 0/음수 섞이면 null — 그럴듯한 값 만들지 않음")
    void invalidPrice_null() {
        List<Bar> bars = ramp(30, 100, 1, 1);
        bars.set(5, new Bar(0, 0, 0, 0));
        assertThat(PullbackEntryCalculator.compute(bars)).isNull();
    }

    // ========== %B ==========

    @Test
    @DisplayName("꾸준한 상승 → 마지막 종가는 밴드 상단 쪽(%B > 0.5)")
    void percentB_uptrend_high() {
        Double pb = PullbackEntryCalculator.percentB(ramp(40, 100, 1, 1));
        assertThat(pb).isNotNull();
        assertThat(pb).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("마지막 봉만 급락 → %B 가 0 아래(하단 이탈)로 떨어짐 — 클램프 안 함")
    void percentB_crash_belowZero() {
        List<Bar> bars = flat(30, 100, 0.5);
        bars.set(29, new Bar(100, 100, 80, 80));
        Double pb = PullbackEntryCalculator.percentB(bars);
        assertThat(pb).isNotNull();
        assertThat(pb).isLessThan(0.0);
    }

    @Test
    @DisplayName("종가 완전 고정(표준편차 0) → 밴드 성립 불가로 null")
    void percentB_zeroStdDev_null() {
        assertThat(PullbackEntryCalculator.percentB(flat(30, 100, 0.5))).isNull();
    }

    // ========== 볼린저 하단 터치 이력 ==========

    @Test
    @DisplayName("관측창 내 하단 미터치 → null (= '터치도 안 났다', 결측 아님)")
    void lowerTouch_none_null() {
        // 꾸준한 상승은 저가가 하단선까지 내려오지 않는다
        assertThat(PullbackEntryCalculator.lowerTouchBarsAgo(ramp(40, 100, 1, 0.5))).isNull();
    }

    @Test
    @DisplayName("마지막 봉이 하단을 찍으면 경과 0")
    void lowerTouch_lastBar_zero() {
        List<Bar> bars = ramp(40, 100, 1, 0.5);
        int last = bars.size() - 1;
        Bar b = bars.get(last);
        bars.set(last, new Bar(b.open(), b.high(), b.low() - 30, b.close()));   // 저가만 깊게
        assertThat(PullbackEntryCalculator.lowerTouchBarsAgo(bars)).isEqualTo(0);
    }

    @Test
    @DisplayName("3봉 전에 하단 터치 → 경과 3")
    void lowerTouch_threeBarsAgo() {
        List<Bar> bars = ramp(40, 100, 1, 0.5);
        int idx = bars.size() - 4;
        Bar b = bars.get(idx);
        bars.set(idx, new Bar(b.open(), b.high(), b.low() - 30, b.close()));
        assertThat(PullbackEntryCalculator.lowerTouchBarsAgo(bars)).isEqualTo(3);
    }

    // ========== 반등 회차 ==========

    @Test
    @DisplayName("바닥 찍고 처음 올라오는 중 → 첫 반등(1)")
    void bounce_firstRebound_one() {
        List<Bar> bars = new ArrayList<>();
        bars.addAll(ramp(20, 120, -1, 0.5));       // 120 → 101 하락
        bars.addAll(ramp(10, 101, 1.5, 0.5));      // 101 → 114.5 반등(+13%)
        assertThat(PullbackEntryCalculator.bounceOrdinal(bars)).isEqualTo(1);
    }

    @Test
    @DisplayName("반등 → 되밀림 → 재반등 중이면 2회차")
    void bounce_secondRebound_two() {
        List<Bar> bars = new ArrayList<>();
        bars.addAll(ramp(20, 120, -1, 0.5));       // 하락 → 바닥 101
        bars.addAll(ramp(8, 101, 1.5, 0.5));       // 1차 반등 → 111.5
        bars.addAll(ramp(6, 111.5, -1.5, 0.5));    // 되밀림 → 104
        bars.addAll(ramp(8, 104, 1.5, 0.5));       // 2차 반등
        assertThat(PullbackEntryCalculator.bounceOrdinal(bars)).isEqualTo(2);
    }

    @Test
    @DisplayName("계속 흘러내리는 중 → 반등 미성립(0)")
    void bounce_stillFalling_zero() {
        assertThat(PullbackEntryCalculator.bounceOrdinal(ramp(30, 130, -1, 0.5))).isEqualTo(0);
    }

    // ========== 장대음봉 ==========

    @Test
    @DisplayName("꽉 찬 장대음봉 판정 — 몸통 비중·하락률 둘 다 충족해야 true")
    void bigBear_bothConditions() {
        // 시가 100 → 종가 95 (-5%), 범위 100.5~94.8 → 몸통 비중 ≈ 88%
        assertThat(PullbackEntryCalculator.isBigBearCandle(new Bar(100, 100.5, 94.8, 95))).isTrue();
        // 하락률은 크지만 위아래 꼬리가 길어 몸통 비중 미달
        assertThat(PullbackEntryCalculator.isBigBearCandle(new Bar(100, 112, 88, 95))).isFalse();
        // 몸통은 꽉 찼지만 하락률 미달(-1%)
        assertThat(PullbackEntryCalculator.isBigBearCandle(new Bar(100, 100.1, 98.9, 99))).isFalse();
        // 양봉
        assertThat(PullbackEntryCalculator.isBigBearCandle(new Bar(95, 100.5, 94.8, 100))).isFalse();
    }

    @Test
    @DisplayName("장대음봉 없으면 null, 있으면 경과 봉 수 + 쿨다운 판정")
    void bigBear_barsAgo_and_cooldown() {
        List<Bar> bars = ramp(30, 100, 0.5, 0.3);
        assertThat(PullbackEntryCalculator.bigBearBarsAgo(bars)).isNull();
        assertThat(PullbackEntryCalculator.isWithinBigBearCooldown(null)).isFalse();

        int idx = bars.size() - 3;                              // 2봉 전에 장대음봉
        double open = bars.get(idx).open();
        double close = open * 0.94;
        bars.set(idx, new Bar(open, open * 1.005, close * 0.998, close));

        Integer ago = PullbackEntryCalculator.bigBearBarsAgo(bars);
        assertThat(ago).isEqualTo(2);
        assertThat(PullbackEntryCalculator.isWithinBigBearCooldown(ago)).isTrue();   // 3봉 미경과
        assertThat(PullbackEntryCalculator.isWithinBigBearCooldown(3)).isFalse();    // 3봉 경과 = 관망 해제
    }

    // ========== 머리 위 매물벽 ==========

    @Test
    @DisplayName("아래쪽 구간은 제외하고 머리 위 벽만 본다")
    void overheadSupply_ignoresBelow() {
        List<SupplyBin> bins = List.of(
                new SupplyBin(80, 90, 30),        // 아래 — 제외
                new SupplyBin(110, 120, 12));
        OverheadSupply os = PullbackEntryCalculator.overheadSupply(100, bins);
        assertThat(os).isNotNull();
        assertThat(os.wallPct()).isEqualTo(12);
        assertThat(os.distancePct()).isEqualTo(15.0);   // 중앙 115 → +15%
    }

    @Test
    @DisplayName("멀리 있는 더 두꺼운 벽보다 '바로 위를 막는' 가까운 벽을 고른다")
    void overheadSupply_nearestSignificantWall() {
        // 실제 삼성전자 케이스: 90일 고점 근처(+41%)에 최대 두께 bin 이 있어도
        // 정작 막는 건 바로 위 벽이다.
        List<SupplyBin> bins = List.of(
                new SupplyBin(100, 110, 2),        // 위쪽이지만 얇음(유의미 임계 미만) — 벽 아님
                new SupplyBin(110, 120, 9),        // 바로 위를 막는 벽
                new SupplyBin(140, 150, 35));      // 더 두껍지만 멀다
        OverheadSupply os = PullbackEntryCalculator.overheadSupply(100, bins);

        assertThat(os).isNotNull();
        assertThat(os.wallPct()).isEqualTo(9);
        assertThat(os.distancePct()).isEqualTo(15.0);   // 중앙 115 → +15%
    }

    @Test
    @DisplayName("현재가 바로 위 칸(+0.5%)은 같은 가격대로 보고 벽으로 세지 않는다")
    void overheadSupply_tooClose_skipped() {
        // 실제 화면 케이스: bin 폭이 좁아 '바로 위 칸'이 자동으로 잡히던 것
        List<SupplyBin> bins = List.of(
                new SupplyBin(100, 101, 5),        // 중앙 100.5 → +0.5% = 사실상 현재가
                new SupplyBin(107, 109, 8));       // 이게 진짜 머리 위 벽
        OverheadSupply os = PullbackEntryCalculator.overheadSupply(100, bins);

        assertThat(os).isNotNull();
        assertThat(os.wallPct()).isEqualTo(8);
        assertThat(os.distancePct()).isEqualTo(8.0);
    }

    @Test
    @DisplayName("위쪽에 유의미한 두께의 벽이 없으면 null — 얇은 구간을 '벽'이라 하지 않는다")
    void overheadSupply_allThin_null() {
        List<SupplyBin> bins = List.of(
                new SupplyBin(100, 110, 1.5), new SupplyBin(110, 120, 2.0));
        assertThat(PullbackEntryCalculator.overheadSupply(100, bins)).isNull();
    }

    @Test
    @DisplayName("현재가 위 구간이 없으면 null — 벽 없음을 0 으로 위장하지 않음")
    void overheadSupply_noneAbove_null() {
        List<SupplyBin> bins = List.of(new SupplyBin(80, 90, 30), new SupplyBin(90, 100, 40));
        assertThat(PullbackEntryCalculator.overheadSupply(150, bins)).isNull();
        assertThat(PullbackEntryCalculator.overheadSupply(150, List.of())).isNull();
        assertThat(PullbackEntryCalculator.overheadSupply(150, null)).isNull();
    }

    // ========== 묶음 ==========

    @Test
    @DisplayName("compute — 하단 미터치 + 첫 반등 자리를 한 번에 드러낸다")
    void compute_bundle() {
        List<Bar> bars = new ArrayList<>();
        bars.addAll(ramp(25, 120, -1, 0.5));
        bars.addAll(ramp(10, 95, 1.5, 0.5));
        Metrics m = PullbackEntryCalculator.compute(bars);
        assertThat(m).isNotNull();
        assertThat(m.bounceOrdinal()).isEqualTo(1);        // 첫 반등 — 경계 대상
        assertThat(m.bigBearBarsAgo()).isNull();           // 장대음봉은 없었음
    }
}
