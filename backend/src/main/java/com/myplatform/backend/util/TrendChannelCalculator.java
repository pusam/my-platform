package com.myplatform.backend.util;

import java.util.List;

/**
 * 추세 채널(회귀 채널) 계산 — 순수 함수(테스트 대상). 프론트 {@code trendChannel.js} 와 동일 산식.
 *
 * <p>산출: 종가 선형회귀 중심선 + 고가/저가 최대 이탈만큼 평행 이동한 상단/하단선.
 * 방향 = 기울기(평균 종가 대비 %/봉)를 ±{@link #SLOPE_FLAT_THRESHOLD} 임계로 UP/DOWN/FLAT.
 * position = 마지막 종가의 채널 내 위치 0(하단)~1(상단).
 *
 * <p>⚠ <b>표시 전용 보조 지표</b> — 점수/시그널/봇 산식에 편입하지 않는다(차트기법 스코어러 분리 불변식).
 * 데이터 부족({@link #MIN_BARS} 미만)/퇴화 입력이면 null — 그럴듯한 값으로 위장하지 않는다(§4c).
 */
public final class TrendChannelCalculator {

    /** 채널 성립 최소 봉 수. */
    public static final int MIN_BARS = 10;
    /** 방향 판정 임계(%/봉) — 이내면 FLAT(박스권). */
    public static final double SLOPE_FLAT_THRESHOLD = 0.15;

    private TrendChannelCalculator() {}

    /** 일봉 1개 — 과거→최신 순으로 넘긴다. */
    public record Bar(double high, double low, double close) {}

    /**
     * 채널 결과 — direction=UP/DOWN/FLAT, slopePctPerBar=%/봉, position=0(하단)~1(상단),
     * widthPct=(상단−하단)/중심선×100 (채널 폭 %). 폭은 이상치 1봉이 좌우하므로(고저가 최대이탈
     * 평행 채널) 사후 재집계 필터용으로 함께 노출한다 — position 만으론 급락일 압축을 걸러낼 수 없다.
     */
    public record Channel(String direction, double slopePctPerBar, double position, double widthPct) {}

    /**
     * @param barsOldestFirst 과거→최신 순 일봉
     * @return 채널, 데이터 부족/비정상(가격 ≤0·NaN)이면 null
     */
    public static Channel compute(List<Bar> barsOldestFirst) {
        if (barsOldestFirst == null || barsOldestFirst.size() < MIN_BARS) return null;
        int n = barsOldestFirst.size();

        double meanX = (n - 1) / 2.0;
        double sumY = 0;
        for (Bar b : barsOldestFirst) {
            if (b == null || !isValid(b.close()) || !isValid(b.high()) || !isValid(b.low())) return null;
            sumY += b.close();
        }
        double meanY = sumY / n;

        double sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            sxx += (i - meanX) * (i - meanX);
            sxy += (i - meanX) * (barsOldestFirst.get(i).close() - meanY);
        }
        if (sxx == 0 || meanY <= 0) return null;
        double slope = sxy / sxx;
        double intercept = meanY - slope * meanX;

        // 평행 이동 오프셋 — 모든 고가/저가를 감싸는 최대 이탈
        double offsetUp = 0, offsetDown = 0;
        for (int i = 0; i < n; i++) {
            double reg = intercept + slope * i;
            offsetUp = Math.max(offsetUp, barsOldestFirst.get(i).high() - reg);
            offsetDown = Math.max(offsetDown, reg - barsOldestFirst.get(i).low());
        }

        double midEnd = intercept + slope * (n - 1);
        double span = offsetUp + offsetDown;
        if (span <= 0 || midEnd <= 0) return null;

        double slopePctPerBar = slope / meanY * 100.0;
        String direction = slopePctPerBar >= SLOPE_FLAT_THRESHOLD ? "UP"
                : slopePctPerBar <= -SLOPE_FLAT_THRESHOLD ? "DOWN" : "FLAT";

        double lowerEnd = midEnd - offsetDown;
        double lastClose = barsOldestFirst.get(n - 1).close();
        double position = Math.min(1.0, Math.max(0.0, (lastClose - lowerEnd) / span));
        // 폭 % = (상단−하단)/중심선 × 100 = span/midEnd × 100 (span=offsetUp+offsetDown=채널 높이, midEnd=중심선).
        double widthPct = span / midEnd * 100.0;

        return new Channel(direction, slopePctPerBar, position, widthPct);
    }

    private static boolean isValid(double v) {
        return Double.isFinite(v) && v > 0;
    }
}
