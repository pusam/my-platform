package com.myplatform.backend.util;

import java.util.List;

/**
 * 눌림목 진입 지표 — 순수 함수(테스트 대상). <b>측정 전용 뼈대</b>.
 *
 * <p>"좋은 종목 고르기(가점)"가 아니라 <b>"자리 아닌 걸 거르기(veto)"</b> 쪽 지표 묶음이다.
 * 재량 트레이더가 실제로 쓰는 거르기 규칙 4종을 수치화한다:
 * <ol>
 *   <li>볼린저 하단을 <b>터치도 안 한</b> 상태의 진입 — {@link Metrics#percentB()} /
 *       {@link Metrics#lowerTouchBarsAgo()}</li>
 *   <li>바닥 이후 <b>첫 반등</b> 진입(첫 반등은 저점을 다시 깨는 경우가 많다) —
 *       {@link Metrics#bounceOrdinal()}</li>
 *   <li>꽉 찬 <b>장대음봉</b> 직후 진입(음봉 매물이 소화될 시간이 필요) —
 *       {@link Metrics#bigBearBarsAgo()}</li>
 *   <li>머리 위 <b>매물벽</b>(오버헤드 서플라이) — {@link #overheadSupply}</li>
 * </ol>
 *
 * <p>⚠ <b>산식/봇/추천 미편입</b>(차트기법 스코어러 분리 불변식 + P2-12 교훈).
 * 1단계는 {@code signal_outcome} 스냅샷 적재 → 조건부 적중률로 검증하고,
 * 검증 전에는 매수후보/봇 게이트로 승격하지 않는다.
 *
 * <p>⚠ 아래 <b>임계 상수는 잠정값</b>이다. 재량 매매 글 표본을 더 모아 확정하기 전까지
 * 값 자체에 의미를 두지 말 것(뼈대 — 시그니처와 판정 구조만 확정된 상태).
 *
 * <p>결측 규약(§4c): 데이터 부족/퇴화 입력이면 {@link Metrics} 통째로 null.
 * Metrics 가 non-null 인데 개별 필드가 null 이면 그건 "관측창 안에 해당 사건이 없었다"는
 * <b>의미 있는 값</b>이다(예: lowerTouchBarsAgo=null → 하단 미터치).
 */
public final class PullbackEntryCalculator {

    /** 볼린저 기간 — {@code TechnicalIndicatorService.BB_PERIOD} 와 동기. */
    public static final int BB_PERIOD = 20;
    /** 볼린저 표준편차 배수 — {@code TechnicalIndicatorService.BB_STD_MULTIPLIER} 와 동기(모집단 표준편차). */
    public static final double BB_STD_MULTIPLIER = 2.0;

    /** 하단 터치 관측창(봉). */
    public static final int LOWER_TOUCH_LOOKBACK = 20;
    /** 장대음봉 관측창(봉). */
    public static final int BIG_BEAR_LOOKBACK = 10;
    /** 반등 회차 관측창(봉) — 이 구간 최저 저가를 바닥으로 본다. */
    public static final int BOUNCE_LOOKBACK = 60;

    /** [잠정] 장대음봉 판정 — 몸통이 전체 범위에서 차지하는 최소 비율("꽉 찬"). */
    public static final double BIG_BEAR_BODY_FILL = 0.70;
    /** [잠정] 장대음봉 판정 — 최소 하락률(시가 대비). */
    public static final double BIG_BEAR_DROP_PCT = 4.0;
    /** [잠정] 장대음봉 이후 관망 봉 수 — {@link #isWithinBigBearCooldown} 판정 기준. */
    public static final int BIG_BEAR_COOLDOWN_BARS = 3;

    /** [잠정] 매물벽 최소 두께(전체 거래 대비 %) — 이보다 얇으면 '벽'으로 보지 않는다. */
    public static final double WALL_MIN_PCT = 5.0;
    /**
     * [잠정] 매물벽 최소 거리(%) — 이보다 가까운 구간은 현재가가 속한 가격대로 보고 제외한다.
     * Volume Profile bin 폭이 좁아 '바로 위 칸'이 자동으로 잡히는데, 그건 머리 위 저항이 아니다.
     */
    public static final double WALL_MIN_DISTANCE_PCT = 1.0;

    /** [잠정] 반등 회차 — 직전 저점 대비 이만큼 오르면 반등 1회 성립. */
    public static final double BOUNCE_RISE_PCT = 5.0;
    /** [잠정] 반등 회차 — 반등 고점 대비 이만큼 밀리면 반등 종료(다음 저점 탐색). */
    public static final double BOUNCE_FADE_PCT = 3.0;

    private PullbackEntryCalculator() {}

    /** 일봉 1개 — 과거→최신 순으로 넘긴다. */
    public record Bar(double open, double high, double low, double close) {}

    /**
     * 눌림목 진입 지표 묶음. 전 필드 <b>측정 전용</b>.
     *
     * @param percentB          마지막 종가의 볼린저 밴드 내 위치. 0=하단, 1=상단.
     *                          밴드 밖이면 0 미만/1 초과가 그대로 나온다(클램프 안 함 — 이탈 정도가 정보).
     * @param lowerTouchBarsAgo 최근 볼린저 하단 터치(저가 ≤ 하단) 이후 경과 봉 수(0=마지막 봉).
     *                          <b>null = 관측창({@link #LOWER_TOUCH_LOOKBACK}봉) 내 미터치</b>.
     * @param bounceOrdinal     관측창 최저점 이후 <b>현재 진행 중인 반등이 몇 번째인가</b>(1=첫 반등).
     *                          0 = 반등 미성립(아직 저점 갱신/하락 중).
     * @param bigBearBarsAgo    최근 장대음봉 이후 경과 봉 수(0=마지막 봉).
     *                          <b>null = 관측창({@link #BIG_BEAR_LOOKBACK}봉) 내 장대음봉 없음</b>.
     */
    public record Metrics(double percentB, Integer lowerTouchBarsAgo,
                          int bounceOrdinal, Integer bigBearBarsAgo) {}

    /**
     * 머리 위 매물벽 — 현재가 <b>위쪽</b> 구간에서 거래량이 가장 두꺼운 가격대.
     *
     * @param distancePct 현재가 → 그 가격대 중앙까지 거리(%). 가까울수록 상단이 막혀 있다.
     * @param wallPct     그 가격대의 전체 대비 거래량 비중(%) — 벽의 두께.
     */
    public record OverheadSupply(double distancePct, double wallPct) {}

    /** Volume Profile 한 구간 — {@code VolumeProfileDto.Bin} 을 순수 함수용으로 옮긴 것. */
    public record SupplyBin(double priceLow, double priceHigh, double volumePct) {}

    /**
     * @param barsOldestFirst 과거→최신 순 일봉
     * @return 지표 묶음, 데이터 부족({@link #BB_PERIOD} 미만)/비정상(가격 ≤0·NaN)이면 null
     */
    public static Metrics compute(List<Bar> barsOldestFirst) {
        if (barsOldestFirst == null || barsOldestFirst.size() < BB_PERIOD) return null;
        for (Bar b : barsOldestFirst) {
            if (b == null || !isValid(b.open()) || !isValid(b.high())
                    || !isValid(b.low()) || !isValid(b.close())) return null;
        }

        Double pb = percentB(barsOldestFirst);
        if (pb == null) return null;

        return new Metrics(pb,
                lowerTouchBarsAgo(barsOldestFirst),
                bounceOrdinal(barsOldestFirst),
                bigBearBarsAgo(barsOldestFirst));
    }

    /**
     * 마지막 봉의 %B = (종가 − 하단) / (상단 − 하단). 밴드폭 0/데이터 부족이면 null.
     * 클램프하지 않는다 — 하단 이탈(음수)·상단 이탈(1 초과) 자체가 관측 대상.
     */
    public static Double percentB(List<Bar> barsOldestFirst) {
        if (barsOldestFirst == null || barsOldestFirst.size() < BB_PERIOD) return null;
        int last = barsOldestFirst.size() - 1;
        double[] band = bandAt(barsOldestFirst, last);
        if (band == null) return null;
        double span = band[0] - band[1];
        if (span <= 0) return null;
        return (barsOldestFirst.get(last).close() - band[1]) / span;
    }

    /**
     * 최근 볼린저 하단 터치(저가 ≤ 하단선) 이후 경과 봉 수. 0 = 마지막 봉에서 터치.
     *
     * @return 미터치면 null — "터치도 안 났다"는 관측 결과이지 결측이 아니다.
     */
    public static Integer lowerTouchBarsAgo(List<Bar> barsOldestFirst) {
        if (barsOldestFirst == null || barsOldestFirst.size() < BB_PERIOD) return null;
        int last = barsOldestFirst.size() - 1;
        int oldest = Math.max(BB_PERIOD - 1, last - LOWER_TOUCH_LOOKBACK + 1);
        for (int i = last; i >= oldest; i--) {
            double[] band = bandAt(barsOldestFirst, i);
            if (band == null) continue;
            if (barsOldestFirst.get(i).low() <= band[1]) return last - i;
        }
        return null;
    }

    /**
     * 관측창 최저 저가(바닥) 이후 <b>현재 진행 중인 반등의 회차</b>.
     *
     * <p>지그재그 카운트: 바닥부터 훑으며 직전 저점 대비 {@link #BOUNCE_RISE_PCT} 이상 오르면
     * 반등 1회 성립, 그 반등 고점 대비 {@link #BOUNCE_FADE_PCT} 이상 밀리면 반등 종료하고
     * 다음 저점을 다시 찾는다.
     *
     * @return 1 = 첫 반등 진행 중(재량 트레이더가 가장 경계하는 자리), 0 = 반등 미성립
     */
    public static int bounceOrdinal(List<Bar> barsOldestFirst) {
        if (barsOldestFirst == null || barsOldestFirst.isEmpty()) return 0;
        int last = barsOldestFirst.size() - 1;
        int from = Math.max(0, last - BOUNCE_LOOKBACK + 1);

        int bottomIdx = from;
        for (int i = from; i <= last; i++) {
            if (barsOldestFirst.get(i).low() < barsOldestFirst.get(bottomIdx).low()) bottomIdx = i;
        }

        int count = 0;
        boolean rising = false;               // 반등 진행 중인가
        double swingLow = barsOldestFirst.get(bottomIdx).low();
        double swingHigh = 0;

        for (int i = bottomIdx; i <= last; i++) {
            double close = barsOldestFirst.get(i).close();
            if (!rising) {
                swingLow = Math.min(swingLow, barsOldestFirst.get(i).low());
                if (close >= swingLow * (1 + BOUNCE_RISE_PCT / 100.0)) {
                    count++;
                    rising = true;
                    swingHigh = close;
                }
            } else {
                swingHigh = Math.max(swingHigh, close);
                if (close <= swingHigh * (1 - BOUNCE_FADE_PCT / 100.0)) {
                    rising = false;
                    swingLow = barsOldestFirst.get(i).low();
                }
            }
        }
        return rising ? count : 0;
    }

    /**
     * 최근 "꽉 찬 장대음봉" 이후 경과 봉 수. 0 = 마지막 봉이 장대음봉.
     *
     * <p>판정: 종가 &lt; 시가 AND 몸통/전체범위 ≥ {@link #BIG_BEAR_BODY_FILL}
     * AND 시가 대비 하락률 ≥ {@link #BIG_BEAR_DROP_PCT}%.
     *
     * @return 관측창 내 없으면 null
     */
    public static Integer bigBearBarsAgo(List<Bar> barsOldestFirst) {
        if (barsOldestFirst == null || barsOldestFirst.isEmpty()) return null;
        int last = barsOldestFirst.size() - 1;
        int oldest = Math.max(0, last - BIG_BEAR_LOOKBACK + 1);
        for (int i = last; i >= oldest; i--) {
            if (isBigBearCandle(barsOldestFirst.get(i))) return last - i;
        }
        return null;
    }

    /** 꽉 찬 장대음봉 여부 — 단봉 판정(테스트 대상). */
    public static boolean isBigBearCandle(Bar bar) {
        if (bar == null || !isValid(bar.open()) || !isValid(bar.high())
                || !isValid(bar.low()) || !isValid(bar.close())) return false;
        if (bar.close() >= bar.open()) return false;
        double range = bar.high() - bar.low();
        if (range <= 0) return false;
        double body = bar.open() - bar.close();
        if (body / range < BIG_BEAR_BODY_FILL) return false;
        return body / bar.open() * 100.0 >= BIG_BEAR_DROP_PCT;
    }

    /**
     * 장대음봉 관망 구간인가 — "장대음봉 나오고 +{@value #BIG_BEAR_COOLDOWN_BARS}거래일 뒤에 봐라".
     *
     * @param bigBearBarsAgo {@link #bigBearBarsAgo} 결과(null=장대음봉 없음)
     * @return 경과 봉 수가 {@link #BIG_BEAR_COOLDOWN_BARS} 미만이면 true
     */
    public static boolean isWithinBigBearCooldown(Integer bigBearBarsAgo) {
        return bigBearBarsAgo != null && bigBearBarsAgo < BIG_BEAR_COOLDOWN_BARS;
    }

    /**
     * 현재가 <b>바로 위</b>를 막는 매물벽까지의 거리와 두께.
     *
     * <p>"위쪽에서 가장 두꺼운 구간"이 아니라 <b>유의미한 두께({@link #WALL_MIN_PCT}% 이상)를 가진
     * 것 중 가장 가까운 구간</b>을 고른다 — 90일 고점 근처의 최대 거래 구간(+40~80%)은 지금
     * 상승을 막는 벽이 아니기 때문이다(실측에서 드러난 오선택).
     *
     * @param currentPrice 현재가
     * @param bins         Volume Profile 구간(정렬 무관)
     * @return 위쪽에 유의미한 벽이 없거나 입력이 비정상이면 null
     *         (§4c — 벽 없음/얇은 구간을 '벽'으로 위장하지 않는다)
     */
    public static OverheadSupply overheadSupply(double currentPrice, List<SupplyBin> bins) {
        if (!isValid(currentPrice) || bins == null || bins.isEmpty()) return null;

        SupplyBin wall = null;
        double wallMid = 0;
        for (SupplyBin b : bins) {
            if (b == null || !isValid(b.priceLow()) || !isValid(b.priceHigh())) continue;
            if (b.volumePct() < WALL_MIN_PCT) continue;        // 얇은 구간은 벽이 아니다
            double mid = (b.priceLow() + b.priceHigh()) / 2.0;
            // 현재가와 사실상 같은 가격대(바로 위 칸)는 머리 위 저항이 아니다
            if (mid <= currentPrice * (1 + WALL_MIN_DISTANCE_PCT / 100.0)) continue;
            if (wall == null || mid < wallMid) {               // 가장 가까운 벽
                wall = b;
                wallMid = mid;
            }
        }
        if (wall == null) return null;

        double mid = (wall.priceLow() + wall.priceHigh()) / 2.0;
        return new OverheadSupply((mid - currentPrice) / currentPrice * 100.0, wall.volumePct());
    }

    /**
     * index 위치의 볼린저 밴드.
     *
     * @return {상단, 하단}, 구간 부족/표준편차 0 이면 null
     */
    private static double[] bandAt(List<Bar> barsOldestFirst, int index) {
        if (index < BB_PERIOD - 1) return null;
        double sum = 0;
        for (int i = index - BB_PERIOD + 1; i <= index; i++) sum += barsOldestFirst.get(i).close();
        double mean = sum / BB_PERIOD;

        double sq = 0;
        for (int i = index - BB_PERIOD + 1; i <= index; i++) {
            double d = barsOldestFirst.get(i).close() - mean;
            sq += d * d;
        }
        double sd = Math.sqrt(sq / BB_PERIOD);   // 모집단 표준편차 — TechnicalIndicatorService 와 동기
        if (sd <= 0) return null;

        return new double[]{mean + sd * BB_STD_MULTIPLIER, mean - sd * BB_STD_MULTIPLIER};
    }

    private static boolean isValid(double v) {
        return Double.isFinite(v) && v > 0;
    }
}
