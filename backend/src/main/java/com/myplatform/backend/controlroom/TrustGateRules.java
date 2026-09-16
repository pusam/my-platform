package com.myplatform.backend.controlroom;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * "추천을 믿고 사도 되나"를 <b>표본·비용·불확실성</b>으로 판정하는 순수 함수 (2026-09-16).
 *
 * <p><b>왜 이 규칙이 필요한가.</b> 그동안 이 질문의 답은 사람이 화면의 적중률 숫자를 눈으로 보고
 * 내렸다. 적중률만으로는 손익을 알 수 없다 — 맞을 때 얼마 벌고 틀릴 때 얼마 잃는지가 빠져 있고,
 * 거래비용도 빠져 있다. 게다가 "표본 30건"은 같은 날 10건씩 3일이면 채워지는데, 그 30건은
 * 독립적인 30번의 기회가 아니다(같은 날 행은 같은 시장 충격을 공유한다).
 *
 * <p><b>상태는 셋이고, 표본이 찼다고 통과가 아니다.</b>
 * <ul>
 *   <li>{@code COLLECTING} — 표본 부족. 아직 읽을 숫자가 없다.</li>
 *   <li>{@code EVALUABLE} — 표본은 찼다. <b>이제 숫자를 읽을 수 있다</b>는 뜻이지 유리하다는 뜻이 아니다.</li>
 *   <li>{@code CONSIDER_EXPANDING} — 비용을 빼고도 (+), 대조군 대비 우위가 <b>불확실성 폭보다 크고</b>,
 *       평균 최대낙폭이 한도 안. 이때도 "모의운용 확대 검토"이지 실매수 승인이 아니다.</li>
 * </ul>
 *
 * <p><b>짝지은(paired) 일 단위 비교를 쓴다.</b> 행 단위로 평균을 내면 하루에 10건 몰린 날이 그날의
 * 시장 등락을 10배로 반영한다. 그래서 ① 같은 날 시그널·대조군을 각각 <b>그날의 평균</b>으로 접고
 * ② 날짜별 차이의 평균과 표준편차로 불확실성을 잡는다. 같은 날끼리 빼므로 시장 전체 등락이
 * 상쇄되고, 유효 표본 수가 <b>행 수가 아니라 거래일 수</b>가 된다(P3-11 distinctDays 원리와 동일).
 *
 * <p>⚠ <b>이 판정은 소프트웨어 검증이지 수익 보장이 아니다.</b> 과거 성적은 미래 수익을 보장하지
 * 않는다. 규칙을 성적에 맞춰 계속 바꾸면 검증이 아니라 과적합이다 — 임계를 바꿀 땐 바꾼 날짜를
 * 표본 경계로 기록할 것.
 */
public final class TrustGateRules {

    private TrustGateRules() {}

    /**
     * 왕복 거래비용(%) — 매수 수수료 0.015% + 매도 수수료 0.015% + 증권거래세 0.15% = 0.18%.
     *
     * <p>{@code TradeService.SELL_TAX_RATE}(0.15%)·{@code VirtualTradeService.COMMISSION_RATE}(0.015%)와
     * 같은 값이다. ⚠ <b>슬리피지는 포함하지 않았다</b> — 시그널 기록가와 실제 체결가의 차이는 아직
     * 측정하지 않았으므로, 여기 수치는 <b>낙관 쪽으로 치우친 하한 비용</b>이다.
     */
    public static final BigDecimal ROUND_TRIP_COST_PCT = new BigDecimal("0.18");

    /** 최소 평가 행 수 — {@code SignalOutcomeService.MIN_CONTROL_SAMPLE} 과 같은 값. */
    public static final int MIN_ROWS = 30;
    /** 최소 고유 거래일 수 — 행 수만으론 3일치로도 30을 넘긴다. */
    public static final int MIN_DISTINCT_DAYS = 10;
    /** 95% 양측 정규근사. */
    private static final double Z_95 = 1.96;
    /**
     * 평균 최대낙폭 허용 한도(%) — 이보다 깊으면 평균 수익이 (+)여도 확대 검토 대상이 아니다.
     * 보유 중 -12% 를 견뎌야 평균 +1% 를 얻는 전략은 사람이 못 지킨다.
     */
    public static final BigDecimal MAX_ACCEPTABLE_MAE_PCT = new BigDecimal("-10");

    public enum State {
        /** 표본 수집 중. */
        COLLECTING,
        /** 평가 가능 — 숫자를 읽을 수 있다(유리하다는 뜻 아님). */
        EVALUABLE,
        /** 모의운용 확대 검토 — 비용 차감 (+) & 우위 &gt; 불확실성 & 낙폭 한도 내. */
        CONSIDER_EXPANDING
    }

    /**
     * 하루치 짝 — 그날의 시그널 평균수익(%)과 대조군 평균수익(%).
     * 한쪽이라도 없는 날은 호출부가 넣지 않는다(짝 없는 날은 비교 불가).
     */
    public record DayPair(LocalDate date, BigDecimal signalMeanPct, BigDecimal controlMeanPct) {}

    /**
     * 손익의 모양 — 평균만으론 큰 손실 한 번에 무너지는 분포를 못 본다.
     *
     * @param avgWin    이익 난 건의 평균 수익률(%). 없으면 null
     * @param avgLoss   손실 난 건의 평균 수익률(%, 음수). 없으면 null
     * @param worst     최악 1건(%). 없으면 null
     * @param avgMaePct 보유 중 평균 최대낙폭(%, 음수). 없으면 null
     */
    public record Shape(BigDecimal avgWin, BigDecimal avgLoss, BigDecimal worst, BigDecimal avgMaePct) {}

    /**
     * 판정 결과.
     *
     * @param costAdjustedReturn     비용 차감 평균 수익(%, 일 단위 평균). 계산 불가면 null
     * @param edgeVsControl          대조군 대비 우위(%p, 짝지은 일별 차이의 평균). 계산 불가면 null
     * @param edgeMarginOfError      그 우위의 95% 불확실성 폭(±%p). 계산 불가면 null
     * @param edgeExceedsUncertainty 우위가 불확실성 폭을 넘었는가(= 0 과 구분되는가)
     * @param blockers               통과를 막고 있는 사유들. 비어 있으면 막는 것 없음
     */
    public record Verdict(
            State state,
            int rows,
            int distinctDays,
            int controlRows,
            BigDecimal costAdjustedReturn,
            BigDecimal edgeVsControl,
            BigDecimal edgeMarginOfError,
            boolean edgeExceedsUncertainty,
            Shape shape,
            int excludedDays,
            List<String> blockers,
            String headline,
            String detail
    ) {}

    /**
     * 판정한다.
     *
     * @param pairs        양쪽 모두 평가 완료된 날의 짝(호출부가 공통 날짜로만 만든다)
     * @param rows         시그널 평가 완료 행 수
     * @param controlRows  대조군 평가 완료 행 수
     * @param shape        손익 분포(없으면 모든 필드 null 인 Shape)
     * @param excludedDays 시그널은 있었으나 대조군 짝이 없어 빠진 날 수(§4c 조용한 제외 금지)
     */
    public static Verdict judge(List<DayPair> pairs, int rows, int controlRows,
                                Shape shape, int excludedDays) {
        List<DayPair> usable = pairs == null ? List.<DayPair>of()
                : pairs.stream()
                       .filter(p -> p != null && p.signalMeanPct() != null && p.controlMeanPct() != null)
                       .toList();
        int days = usable.size();

        BigDecimal signalMean = meanOf(usable.stream().map(DayPair::signalMeanPct).toList());
        BigDecimal costAdjusted = signalMean == null ? null
                : signalMean.subtract(ROUND_TRIP_COST_PCT).setScale(2, RoundingMode.HALF_UP);

        List<BigDecimal> diffs = usable.stream()
                .map(p -> p.signalMeanPct().subtract(p.controlMeanPct()))
                .toList();
        BigDecimal edge = meanOf(diffs);
        BigDecimal moe = marginOfError(diffs);
        boolean exceeds = edge != null && moe != null
                && edge.signum() > 0 && edge.abs().compareTo(moe) > 0;

        List<String> blockers = new ArrayList<>();
        if (rows < MIN_ROWS) blockers.add("시그널 표본 " + rows + "/" + MIN_ROWS + "건");
        if (controlRows < MIN_ROWS) blockers.add("대조군 표본 " + controlRows + "/" + MIN_ROWS + "건");
        if (days < MIN_DISTINCT_DAYS) blockers.add("고유 거래일 " + days + "/" + MIN_DISTINCT_DAYS + "일");
        boolean sampleShort = !blockers.isEmpty();

        // 표본이 차야 비로소 '읽을 수 있다'. 표본 충족 자체는 통과 사유가 아니다.
        if (!sampleShort) {
            if (costAdjusted == null || costAdjusted.signum() <= 0) {
                blockers.add("비용 차감 수익 " + (costAdjusted == null ? "계산 불가" : costAdjusted + "%"));
            }
            if (!exceeds) {
                blockers.add(edge == null ? "대조군 대비 우위 계산 불가"
                        : "우위 " + edge.setScale(2, RoundingMode.HALF_UP) + "%p 가 불확실성 ±"
                          + (moe == null ? "?" : moe.setScale(2, RoundingMode.HALF_UP).toPlainString())
                          + "%p 를 못 넘음");
            }
            BigDecimal mae = shape == null ? null : shape.avgMaePct();
            if (mae != null && mae.compareTo(MAX_ACCEPTABLE_MAE_PCT) < 0) {
                blockers.add("평균 최대낙폭 " + mae + "% (한도 " + MAX_ACCEPTABLE_MAE_PCT + "%)");
            }
        }

        State state = sampleShort ? State.COLLECTING
                : blockers.isEmpty() ? State.CONSIDER_EXPANDING : State.EVALUABLE;

        return new Verdict(state, rows, days, controlRows,
                costAdjusted,
                edge == null ? null : edge.setScale(2, RoundingMode.HALF_UP),
                moe == null ? null : moe.setScale(2, RoundingMode.HALF_UP),
                exceeds,
                shape == null ? new Shape(null, null, null, null) : shape,
                excludedDays,
                List.copyOf(blockers),
                headline(state, blockers),
                detail(state, days, excludedDays, costAdjusted, edge, moe));
    }

    private static String headline(State state, List<String> blockers) {
        return switch (state) {
            case COLLECTING -> "표본 수집 중 — " + String.join(", ", blockers);
            case EVALUABLE -> "평가 가능 — 아직 확대 근거 없음: " + String.join(", ", blockers);
            case CONSIDER_EXPANDING -> "모의운용 확대 검토 가능 — 실매수 승인 아님";
        };
    }

    private static String detail(State state, int days, int excludedDays,
                                 BigDecimal costAdjusted, BigDecimal edge, BigDecimal moe) {
        StringBuilder sb = new StringBuilder();
        sb.append("유효 표본은 행 수가 아니라 고유 거래일 수다(같은 날 행은 같은 시장 충격을 공유). 현재 ")
          .append(days).append("일. ");
        sb.append("수익은 왕복 비용 ").append(ROUND_TRIP_COST_PCT.toPlainString())
          .append("%(수수료 0.03 + 거래세 0.15) 차감 기준이며 슬리피지는 미포함이라 낙관 쪽 하한이다. ");
        if (costAdjusted != null) {
            sb.append("비용 차감 평균 ").append(costAdjusted.toPlainString()).append("%. ");
        }
        if (edge != null && moe != null) {
            sb.append("대조군 대비 ").append(edge.setScale(2, RoundingMode.HALF_UP).toPlainString())
              .append("%p ± ").append(moe.setScale(2, RoundingMode.HALF_UP).toPlainString())
              .append("%p(95%, 같은 날끼리 짝지어 시장 등락 상쇄). ");
        }
        if (excludedDays > 0) {
            sb.append("⚠ 대조군 짝이 없어 빠진 날 ").append(excludedDays)
              .append("일 — 기록 실패가 변동성 큰 날에 몰리면 비교창이 평온한 날로 치우친다. ");
        }
        if (state == State.CONSIDER_EXPANDING) {
            sb.append("과거 성적은 미래 수익을 보장하지 않는다. 확대는 모의운용 범위 안에서만 검토한다.");
        } else {
            sb.append("통과했더라도 '모의운용 확대 검토'이지 실매수 승인이 아니다.");
        }
        return sb.toString();
    }

    /** 평균. 빈 목록이면 null(§4c — 0 으로 위장 금지). 집계부가 일별 평균을 접을 때도 쓴다. */
    public static BigDecimal meanOf(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) sum = sum.add(v);
        return sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    /**
     * 짝지은 일별 차이의 95% 오차 폭 = {@code 1.96 × 표본표준편차 ÷ √n}.
     *
     * <p>2일 미만이면 표준편차가 정의되지 않아 null 이다 — <b>불확실성을 모를 땐 0 으로 두지 않는다</b>
     * (0 이면 어떤 미세한 우위도 "확실"해진다). n 이 작을 때 이 폭은 정규근사라 낙관 쪽으로 좁다.
     */
    static BigDecimal marginOfError(List<BigDecimal> diffs) {
        if (diffs == null || diffs.size() < 2) return null;
        BigDecimal mean = meanOf(diffs);
        BigDecimal ss = BigDecimal.ZERO;
        for (BigDecimal d : diffs) {
            BigDecimal dev = d.subtract(mean);
            ss = ss.add(dev.multiply(dev));
        }
        double variance = ss.doubleValue() / (diffs.size() - 1);
        double se = Math.sqrt(variance / diffs.size());
        return BigDecimal.valueOf(Z_95 * se).setScale(6, RoundingMode.HALF_UP);
    }
}
