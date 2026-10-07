package com.myplatform.backend.service;

import java.math.BigDecimal;

/**
 * 이익의 질 — 비율 지표가 본업 이익을 말하는가(2026-09-30). 순수 static, {@code EarningsQualityTest} 로 고정.
 *
 * <p>PER·ROE 는 순이익으로, 영업이익률은 영업이익으로 만든다. 순이익이 영업이익으로 설명되지 않거나 영업이익이
 * 매출로 설명되지 않으면, 그 비율은 반복되는 이익력이 아니라 한 번의 이익(자산 매각·평가이익·채무면제·환입 등)을
 * 잰다. 마법의 공식과 PEG 는 바로 그 비율의 <b>극단</b>을 1등으로 올리므로 왜곡이 곧 만점이 된다 — 2026-09-29
 * 운영 AI 스윙 5종목이 전부 100점이었고, 베뉴지는 순이익 3,564억이 영업이익 146억의 24배라 PER 0.7·ROE 51.7%
 * 가 동시에 나왔다(한 번의 이익이 두 지표를 한꺼번에 민다).
 *
 * <p><b>기준 2배의 근거</b>: 법인세를 약 22% 로 보면 순이익 &gt; 영업이익×2 는 세전 영업외 이익이 영업이익의
 * 약 1.6배를 넘는다는 뜻이다(본업보다 본업 밖이 크다). 운영 실측(마법의 공식 상위 30, 2026-09-29)에서 정상 종목은
 * 순이익/영업이익 0.6~1.7배(이자·지분 이익이 있는 흔한 범위)였고, 걸린 종목은 2.4~24배라 그 사이가 비어 있다.
 * 성적에 맞춰 옮기지 말 것 — 옮기면 그 날짜가 AI 스윙·가치와 마법의 공식 결과의 표본 경계다.
 *
 * <p><b>판정 불가는 통과</b>(§4c): 영업이익·순이익이 없으면 {@link Verdict#UNKNOWN} 이고 제외하지 않는다 —
 * 결측은 제외 근거가 아니다(실적 YoY 가드와 같은 극성). 매출이 없거나 0 이하면 두 번째 규칙은 판정하지 않는다
 * (0 이 결측일 수 있다).
 *
 * <p><b>이 규칙이 못 잡는 것</b>: 지주사·그룹사의 연결 순이익에 비지배지분 몫이 섞여 PER 이 낮아지는 경우 —
 * 영업이익도 같이 연결되므로 비율이 정상으로 보인다(다우기술 PER 0.9·순이익/영업이익 0.8배).
 */
public final class EarningsQuality {

    private EarningsQuality() {}

    /** 순이익이 이 배수를 넘게 영업이익보다 크면 본업 밖의 이익이 지배한다. */
    static final BigDecimal NON_OPERATING_MULTIPLE = new BigDecimal("2");

    public enum Verdict {
        /** 본업 이익으로 설명된다. */
        OK,
        /** 영업이익·순이익 중 하나가 없어 판정할 수 없다 — 제외하지 않는다. */
        UNKNOWN,
        /** 순이익 &gt; 영업이익 × 2, 또는 영업손익 ≤ 0 인데 순이익 흑자 — PER·ROE 가 본업 밖의 이익을 잰다. */
        NON_OPERATING_DOMINANT,
        /** 영업이익 &gt; 매출 — 영업이익에 매출 밖의 이익이 섞였다(영업이익률 100% 초과). */
        OPERATING_EXCEEDS_REVENUE;

        /** 비율로 순위를 매기는 화면에서 뺄 왜곡인가. */
        public boolean distorted() {
            return this == NON_OPERATING_DOMINANT || this == OPERATING_EXCEEDS_REVENUE;
        }
    }

    /**
     * PER 이 실제로 쓴 순이익(억원) — 시가총액 ÷ PER(같은 행, 둘 다 양수일 때). 판정은 이 값으로 한다(2026-10-07).
     *
     * <p>PER 은 DART 지배주주 순이익(per_basis=CTRL)으로 만드는데 행의 net_income 은 KIS 연결 순이익이라, net_income 으로 판정하면
     * PER 을 끌어내린 이익을 보지 못한다 — 디에이피(10/7)는 영업손실 −578억·KIS 연결 순이익 −648억이라 '적자 = 판정 대상 아님'으로
     * 통과했지만 PER 0.4 는 지배주주 순이익 +1,022억으로 만든 값이었다(ROE 121.97%, 마법의 공식 1등감). 그날 PER&gt;0·ROE&gt;0
     * 1,635종목 중 54종목이 이렇게 빠져나갔다. 시가총액·PER 을 모르면 행의 순이익(종전).
     */
    public static BigDecimal perImpliedNetIncome(BigDecimal marketCap, BigDecimal per, BigDecimal netIncome) {
        if (marketCap != null && marketCap.signum() > 0 && per != null && per.signum() > 0) {
            return marketCap.divide(per, 2, java.math.RoundingMode.HALF_UP);
        }
        return netIncome;
    }

    /**
     * @param revenue         매출(TTM, 억원) — null 가능
     * @param operatingProfit 영업이익(TTM, 억원) — null 가능
     * @param netIncome       순이익(TTM, 억원) — null 가능
     */
    public static Verdict judge(BigDecimal revenue, BigDecimal operatingProfit, BigDecimal netIncome) {
        if (operatingProfit != null && revenue != null && revenue.signum() > 0
                && operatingProfit.compareTo(revenue) > 0) {
            return Verdict.OPERATING_EXCEEDS_REVENUE;
        }
        if (operatingProfit == null || netIncome == null) {
            return Verdict.UNKNOWN;
        }
        if (netIncome.signum() <= 0) {
            return Verdict.OK;   // 적자는 PER·ROE 가 이미 음수 — 이 판정의 대상이 아니다
        }
        if (operatingProfit.signum() <= 0) {
            return Verdict.NON_OPERATING_DOMINANT;
        }
        return netIncome.compareTo(operatingProfit.multiply(NON_OPERATING_MULTIPLE)) > 0
                ? Verdict.NON_OPERATING_DOMINANT
                : Verdict.OK;
    }
}
