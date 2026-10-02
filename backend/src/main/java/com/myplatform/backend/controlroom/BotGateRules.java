package com.myplatform.backend.controlroom;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * "봇을 믿고 맡겨도 되나"를 <b>표본·비용·불확실성·위험</b>으로 판정하는 순수 함수 (2026-10-02).
 *
 * <p><b>왜 따로 있나.</b> 봇은 추천 점수를 쓰지 않는다 — 스윙은 외국인·기관 연속 순매수, 스캘핑은 체결강도로
 * 들어간다(§7). 그래서 "믿고 사도 되나"({@link TrustGateRules}, 추천 신호의 성적)는 봇에 대해 아무것도 말해 주지 않고,
 * 봇을 믿을지는 봇이 실제로 낸 매매 기록으로 따로 판정해야 한다. 그 전엔 승률·손익 숫자를 사람이 눈으로 보고 정했다.
 *
 * <p><b>상태와 표본 기준은 추천 게이트와 같다</b>({@link TrustGateRules.State}, 30건·고유 10일). 표본이 찼다고 통과가
 * 아니고, 통과해도 "확대 검토"이지 실전 승인이 아니다.
 *
 * <p><b>거래일 단위로 접는다.</b> 같은 날 거래는 같은 시장 충격을 공유하므로 독립 표본이 아니다 — 거래별 순수익률을
 * 그날 평균으로 접고, 날짜별 평균의 평균과 95% 불확실성 폭을 본다(유효 표본 = 거래가 있었던 날 수).
 *
 * <p><b>대조군이 없다.</b> 추천 게이트는 같은 날 무작위 종목과 짝지어 시장 등락을 지우지만 봇에는 그런 짝이 없다.
 * 매수만 하는 봇은 오르는 장에서 대체로 번다 — 이 판정의 (+)에는 시장 덕이 섞여 있을 수 있다(설명문에 적는다).
 */
public final class BotGateRules {

    private BotGateRules() {}

    /**
     * 최악 1건 허용 한도(%) — 손절이 있는데도 이보다 크게 잃었다면 위험 관리가 새고 있다는 뜻이다
     * (갭 하락·정지·체결 실패). 추천 게이트의 평균 최대낙폭 한도({@link TrustGateRules#MAX_ACCEPTABLE_MAE_PCT})와 같은 값.
     */
    public static final BigDecimal WORST_TRADE_LIMIT_PCT = new BigDecimal("-10");

    /** 실현손익 누적곡선의 최대 낙폭 한도(자본 대비 %). 자본을 모르면(실전 계좌) 판정하지 않는다 — 모름은 통과가 아니라 미판정. */
    public static final BigDecimal MAX_DRAWDOWN_LIMIT_PCT = new BigDecimal("-10");

    /** 전략 표시 순서 — 매수 사유로 정한다({@code BotTradeOutcomes.strategyOf}). */
    public static final List<String> STRATEGY_ORDER = List.of("SCALPING", "SWING", "CLOSING", "UNKNOWN");

    /**
     * 매도 1건의 결과.
     *
     * @param day          매도일(거래일 단위로 접는 키)
     * @param strategy     진입 전략(SCALPING/SWING/CLOSING/UNKNOWN)
     * @param netReturnPct 수수료·세금을 뺀 순수익률(%)
     */
    public record TradeOutcome(LocalDate day, String strategy, BigDecimal netReturnPct) {}

    /** 전략별 요약 — 한 전략이 다른 전략의 손실을 가리는지 보려고 둔다. */
    public record StrategyLine(String strategy, int trades, int distinctDays,
                               BigDecimal dailyMeanPct, BigDecimal winRatePct) {}

    /**
     * 판정 결과.
     *
     * @param dailyMeanPct             거래일별 평균 순수익률의 평균(%). 거래가 없으면 null
     * @param marginOfError            그 평균의 95% 불확실성 폭(±%). 거래일 2일 미만이면 null(0 으로 두지 않는다)
     * @param profitExceedsUncertainty 평균이 (+)이고 불확실성 폭보다 큰가(= 0 과 구분되는가)
     * @param maxDrawdownPct           실현손익 누적의 최대 낙폭(자본 대비 %, 음수). 자본을 모르면 null
     * @param excludedTrades           수익률을 계산할 수 없어 뺀 매도 수(§4c — 조용히 빼지 않는다)
     */
    public record Verdict(
            TrustGateRules.State state,
            int trades,
            int distinctDays,
            BigDecimal dailyMeanPct,
            BigDecimal marginOfError,
            boolean profitExceedsUncertainty,
            BigDecimal winRatePct,
            BigDecimal avgWinPct,
            BigDecimal avgLossPct,
            BigDecimal worstPct,
            BigDecimal maxDrawdownPct,
            List<StrategyLine> strategies,
            int excludedTrades,
            List<String> blockers,
            String headline,
            String detail
    ) {}

    /**
     * 판정한다.
     *
     * @param trades         매도 결과(날짜·수익률이 없는 항목은 무시)
     * @param maxDrawdownPct 실현손익 누적 최대 낙폭(자본 대비 %, 음수). 모르면 null
     * @param excludedTrades 호출부가 수익률을 못 구해 뺀 매도 수
     */
    public static Verdict judge(List<TradeOutcome> trades, BigDecimal maxDrawdownPct, int excludedTrades) {
        List<TradeOutcome> usable = trades == null ? List.<TradeOutcome>of()
                : trades.stream()
                        .filter(t -> t != null && t.day() != null && t.netReturnPct() != null)
                        .toList();
        int n = usable.size();
        List<BigDecimal> dailyMeans = dailyMeans(usable);
        int days = dailyMeans.size();

        BigDecimal mean = TrustGateRules.meanOf(dailyMeans);
        BigDecimal moe = TrustGateRules.marginOfError(dailyMeans);
        boolean exceeds = mean != null && moe != null && mean.signum() > 0 && mean.compareTo(moe) > 0;

        List<BigDecimal> returns = usable.stream().map(TradeOutcome::netReturnPct).toList();
        BigDecimal winRate = winRate(returns);
        BigDecimal avgWin = TrustGateRules.meanOf(returns.stream().filter(r -> r.signum() > 0).toList());
        BigDecimal avgLoss = TrustGateRules.meanOf(returns.stream().filter(r -> r.signum() < 0).toList());
        BigDecimal worst = returns.stream().min(BigDecimal::compareTo).orElse(null);

        List<String> blockers = new ArrayList<>();
        if (n < TrustGateRules.MIN_ROWS) blockers.add("거래 " + n + "/" + TrustGateRules.MIN_ROWS + "건");
        if (days < TrustGateRules.MIN_DISTINCT_DAYS) {
            blockers.add("거래일 " + days + "/" + TrustGateRules.MIN_DISTINCT_DAYS + "일");
        }
        boolean sampleShort = !blockers.isEmpty();

        // 표본이 차야 비로소 '읽을 수 있다'. 표본 충족 자체는 통과 사유가 아니다(추천 게이트와 같은 규칙).
        if (!sampleShort) {
            if (mean == null || mean.signum() <= 0) {
                blockers.add("하루 평균 순수익 " + (mean == null ? "계산 불가" : signed(mean) + "%"));
            } else if (!exceeds) {
                blockers.add("하루 평균 " + signed(mean) + "% 가 불확실성 ±"
                        + (moe == null ? "?" : scale2(moe).toPlainString()) + "% 를 못 넘음");
            }
            if (worst != null && worst.compareTo(WORST_TRADE_LIMIT_PCT) < 0) {
                blockers.add("최악 거래 " + scale2(worst).toPlainString() + "% (한도 " + WORST_TRADE_LIMIT_PCT + "%)");
            }
            if (maxDrawdownPct != null && maxDrawdownPct.compareTo(MAX_DRAWDOWN_LIMIT_PCT) < 0) {
                blockers.add("최대 낙폭 " + scale2(maxDrawdownPct).toPlainString()
                        + "% (자본 대비, 한도 " + MAX_DRAWDOWN_LIMIT_PCT + "%)");
            }
        }

        TrustGateRules.State state = sampleShort ? TrustGateRules.State.COLLECTING
                : blockers.isEmpty() ? TrustGateRules.State.CONSIDER_EXPANDING : TrustGateRules.State.EVALUABLE;

        return new Verdict(state, n, days,
                mean == null ? null : scale2(mean),
                moe == null ? null : scale2(moe),
                exceeds,
                winRate,
                avgWin == null ? null : scale2(avgWin),
                avgLoss == null ? null : scale2(avgLoss),
                worst == null ? null : scale2(worst),
                maxDrawdownPct == null ? null : scale2(maxDrawdownPct),
                strategyLines(usable),
                Math.max(0, excludedTrades),
                List.copyOf(blockers),
                headline(state, blockers),
                detail(state, days, mean, moe, maxDrawdownPct, excludedTrades));
    }

    /** 거래일별 평균 순수익률 — 날짜 순. 같은 날 거래는 같은 시장 충격을 공유하므로 하루를 한 표본으로 접는다. */
    static List<BigDecimal> dailyMeans(List<TradeOutcome> usable) {
        Map<LocalDate, List<BigDecimal>> byDay = new TreeMap<>();
        for (TradeOutcome t : usable) {
            byDay.computeIfAbsent(t.day(), d -> new ArrayList<>()).add(t.netReturnPct());
        }
        List<BigDecimal> means = new ArrayList<>(byDay.size());
        for (List<BigDecimal> v : byDay.values()) means.add(TrustGateRules.meanOf(v));
        return means;
    }

    private static List<StrategyLine> strategyLines(List<TradeOutcome> usable) {
        Map<String, List<TradeOutcome>> byStrategy = new LinkedHashMap<>();
        for (String s : STRATEGY_ORDER) byStrategy.put(s, new ArrayList<>());
        for (TradeOutcome t : usable) {
            String key = STRATEGY_ORDER.contains(t.strategy()) ? t.strategy() : "UNKNOWN";
            byStrategy.get(key).add(t);
        }
        List<StrategyLine> lines = new ArrayList<>();
        for (Map.Entry<String, List<TradeOutcome>> e : byStrategy.entrySet()) {
            List<TradeOutcome> group = e.getValue();
            if (group.isEmpty()) continue;
            List<BigDecimal> means = dailyMeans(group);
            BigDecimal mean = TrustGateRules.meanOf(means);
            lines.add(new StrategyLine(e.getKey(), group.size(), means.size(),
                    mean == null ? null : scale2(mean),
                    winRate(group.stream().map(TradeOutcome::netReturnPct).toList())));
        }
        return List.copyOf(lines);
    }

    /** 승률(%) — 순수익 > 0 인 매도의 비율. 거래가 없으면 null. */
    private static BigDecimal winRate(List<BigDecimal> returns) {
        if (returns.isEmpty()) return null;
        long wins = returns.stream().filter(r -> r.signum() > 0).count();
        return BigDecimal.valueOf(wins * 100.0 / returns.size()).setScale(1, RoundingMode.HALF_UP);
    }

    private static String headline(TrustGateRules.State state, List<String> blockers) {
        return switch (state) {
            case COLLECTING -> "표본 수집 중 — " + String.join(", ", blockers);
            case EVALUABLE -> "평가 가능 — 아직 근거 없음: " + String.join(", ", blockers);
            case CONSIDER_EXPANDING -> "확대 검토 가능 — 승인 아님";
        };
    }

    private static String detail(TrustGateRules.State state, int days, BigDecimal mean, BigDecimal moe,
                                 BigDecimal maxDrawdownPct, int excludedTrades) {
        StringBuilder sb = new StringBuilder();
        sb.append("유효 표본은 거래 수가 아니라 거래가 있었던 날 수다(같은 날 거래는 같은 시장 충격을 공유). 현재 ")
          .append(days).append("일. ");
        sb.append("수익률은 수수료·세금을 뺀 실현손익 기준이다(모의는 현재가 전량 체결 가정이라 슬리피지가 없다 — 낙관 쪽). ");
        if (mean != null) {
            sb.append("하루 평균 ").append(signed(mean)).append('%');
            if (moe != null) sb.append(" ± ").append(scale2(moe).toPlainString()).append("%(95%)");
            sb.append(". ");
        }
        if (maxDrawdownPct == null) {
            sb.append("자본을 몰라 최대 낙폭은 판정하지 않았다. ");
        }
        if (excludedTrades > 0) {
            sb.append("⚠ 수익률을 계산할 수 없어 뺀 매도 ").append(excludedTrades).append("건. ");
        }
        sb.append("⚠ 시장 대비 비교가 없다 — 매수만 하는 봇은 오르는 장에서 대체로 번다. 부분 매도는 매도 건마다 따로 센다. ");
        if (state == TrustGateRules.State.CONSIDER_EXPANDING) {
            sb.append("과거 성적은 미래 수익을 보장하지 않는다. 확대 검토이지 실전 승인이 아니다.");
        } else {
            sb.append("통과했더라도 '확대 검토'이지 실전 승인이 아니다.");
        }
        return sb.toString();
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String signed(BigDecimal v) {
        BigDecimal s = scale2(v);
        return (s.signum() > 0 ? "+" : "") + s.toPlainString();
    }
}
