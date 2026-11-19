package com.myplatform.backend.leaders;

import com.myplatform.backend.entity.InvestorStockDaily;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.service.LiquidityFloor;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 주도주 목록 규칙(2026-11-19, 순수 함수) — 시가총액 1조 원 이상 종목을 20거래일 평균 거래대금 순으로 세운다.
 *
 * <p><b>표시 전용</b>이다. 추천 점수·봇·시그널 기록 어디에도 들어가지 않고, '사라'는 신호가 아니다(성과 검증 전).
 *
 * <p><b>정의</b>
 * <ul>
 *   <li>대상: 시가총액 {@link #MIN_MARKET_CAP_EOK}억 원 이상 · 상장법인 목록(종목 마스터)에 시장이 있는 종목 · 거래정지 아님.
 *       우선주·ETF 는 마스터에 시장이 없어 빠진다(같은 회사가 두 번 나오지 않게).</li>
 *   <li>평균 거래대금: 매수 후보의 거래대금 하한과 <b>같은 정의</b>({@link LiquidityFloor#avgTradedValueEok}) — 최근 20봉의
 *       (종가 × 거래량) 평균, 억 원. KRX 정규장 일봉이라 대체거래소(NXT) 거래는 들어 있지 않다.</li>
 *   <li>등락: 기준일 종가 ÷ 달력이 정한 20·60거래일 전 날짜의 종가 − 1. 그 날짜의 봉이 없으면 모른다(null) —
 *       가까운 다른 봉으로 대신하지 않는다(§4c).</li>
 * </ul>
 */
public final class LeaderRules {

    /** 대상 하한 — 시가총액 1조 원(억 원 단위). */
    public static final BigDecimal MIN_MARKET_CAP_EOK = new BigDecimal("10000");
    /** 등락·순매수를 재는 거래일 수. */
    public static final int SHORT_DAYS = 20;
    public static final int LONG_DAYS = 60;

    /** 재지 못한 종목이 대상의 이 비율을 넘으면 그 회차 목록을 내지 않는다. */
    static final double MAX_UNMEASURED_SHARE = 0.10;
    /** 대상이 이보다 적으면 입력(시가총액·종목 마스터)이 비정상이다 — 평소 280여 종목. */
    static final int MIN_UNIVERSE = 50;
    /** 연속으로 이만큼 일봉을 못 받으면 그 회차를 멈춘다(KIS 장애). */
    static final int ABORT_AFTER_CONSECUTIVE_FAILURES = 30;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private LeaderRules() {}

    /** 일봉 한 개 — 종가·거래량은 KIS 수정주가 기준. */
    public record Bar(LocalDate date, BigDecimal close, BigDecimal volume) {}

    /** 한 종목을 잰 값. 등락은 모르면 null. */
    public record Metrics(BigDecimal close, BigDecimal avgTradedValueEok, BigDecimal return20, BigDecimal return60) {}

    public record Candidate(String stockCode, String stockName, String market, BigDecimal marketCapEok, Metrics metrics) {}

    /** 대상인가 — 시가총액을 모르거나, 마스터의 시장이 KOSPI·KOSDAQ 가 아니거나(우선주·ETF 는 시장이 없다), 거래정지면 아니다. */
    static boolean inUniverse(BigDecimal marketCapEok, String masterMarket, boolean active) {
        return active
                && marketCapEok != null && marketCapEok.compareTo(MIN_MARKET_CAP_EOK) >= 0
                && ("KOSPI".equalsIgnoreCase(masterMarket) || "KOSDAQ".equalsIgnoreCase(masterMarket));
    }

    /**
     * 한 종목을 잰다. 기준일 봉이 없거나 평균 거래대금을 모르면 null(목록에 넣지 않는다).
     *
     * @param bars  KIS 일봉(순서 무관). 기준일 뒤의 봉은 쓰지 않는다 — 값이 아직 굳지 않은 날이다
     * @param day20 달력이 정한 20거래일 전 날짜
     * @param day60 달력이 정한 60거래일 전 날짜
     */
    static Metrics measure(List<Bar> bars, LocalDate basis, LocalDate day20, LocalDate day60) {
        if (bars == null || basis == null) return null;
        List<StockPriceHistory> rows = new ArrayList<>(bars.size());
        BigDecimal close = null, close20 = null, close60 = null;
        for (Bar b : bars) {
            if (b == null || b.date() == null || b.date().isAfter(basis)) continue;
            if (b.close() == null || b.close().signum() <= 0) continue;
            StockPriceHistory row = new StockPriceHistory();   // 저장하지 않는다 — 평균 정의를 한 곳(LiquidityFloor)에서 쓰려는 그릇
            row.setTradeDate(b.date());
            row.setClosePrice(b.close());
            row.setVolume(b.volume());
            rows.add(row);
            if (b.date().equals(basis)) close = b.close();
            if (b.date().equals(day20)) close20 = b.close();
            if (b.date().equals(day60)) close60 = b.close();
        }
        if (close == null) return null;
        BigDecimal avg = LiquidityFloor.avgTradedValueEok(rows, basis);
        if (avg == null) return null;
        return new Metrics(close, LiquidityFloor.displayEok(avg), changePct(close, close20), changePct(close, close60));
    }

    /** (now ÷ then − 1) × 100, 소수 둘째 자리. 둘 중 하나를 모르거나 견줄 값이 0 이하면 null. */
    static BigDecimal changePct(BigDecimal now, BigDecimal then) {
        if (now == null || then == null || then.signum() <= 0) return null;
        return now.divide(then, MathContext.DECIMAL64).subtract(BigDecimal.ONE).multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static final Comparator<Candidate> ORDER = Comparator
            .comparing((Candidate c) -> c.metrics().avgTradedValueEok()).reversed()
            .thenComparing(Candidate::marketCapEok, Comparator.reverseOrder())
            .thenComparing(Candidate::stockCode);

    /** 20거래일 평균 거래대금 큰 순 → 시가총액 큰 순 → 종목코드 순. 입력은 바꾸지 않는다. */
    static List<Candidate> rank(List<Candidate> candidates) {
        List<Candidate> out = new ArrayList<>(candidates);
        out.sort(ORDER);
        return out;
    }

    /**
     * 이 회차 결과를 목록으로 내도 되는가 — 대상이 너무 적거나, 재지 못한 종목(일봉을 못 받았거나 기준일 봉이 없는 종목)이
     * 대상의 10% 를 넘으면 아니다. 일부로 만든 순위는 순위가 아니다(직전 목록을 그대로 둔다).
     */
    static boolean publishable(int universe, int measured) {
        if (universe < MIN_UNIVERSE || measured <= 0) return false;
        return (universe - measured) <= universe * MAX_UNMEASURED_SHARE;
    }

    /** 외국인 + 기관 순매수 합(억 원)과 그 합에 든 날 수. */
    public record NetBuy(BigDecimal sumEok, int days) {}

    /**
     * 외국인 + 기관 순매수 합 — 두 값이 다 있는 날만 더한다(한쪽만 있으면 그날 합은 모른다). 기관 계에 연기금이 들어 있으므로
     * 연기금을 따로 더하지 않는다(§4c 수급). 더한 날이 없으면 null.
     */
    static NetBuy netBuy(java.util.Collection<InvestorStockDaily> rows) {
        if (rows == null) return null;
        BigDecimal sum = BigDecimal.ZERO;
        int days = 0;
        for (InvestorStockDaily r : rows) {
            if (r == null || r.getForeignNet() == null || r.getInstitutionNet() == null) continue;
            sum = sum.add(r.getForeignNet()).add(r.getInstitutionNet());
            days++;
        }
        return days == 0 ? null : new NetBuy(sum.setScale(2, RoundingMode.HALF_UP), days);
    }
}
