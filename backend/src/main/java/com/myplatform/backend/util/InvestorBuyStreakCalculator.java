package com.myplatform.backend.util;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 투자자(외인/기관) 연속 순매수일 계산 — <b>순수 함수, 표시·참고 전용(산식 미편입, unverified)</b>.
 *
 * <p>InvestorDailyTrade 는 투자자별 <b>순매수 상위 종목(top-N)</b>만 적재한다. 따라서 어떤 종목이
 * 특정 거래일의 "순매수 목록(BUY)"에 들었으면 그날 그 투자자의 강한 순매수 대상이었다는 뜻.
 * "연속 순매수일" = <b>거래일 캘린더(최신순) 맨 앞부터 그 종목이 BUY 목록에 연속으로 든 일수</b>.
 * 주말/휴장은 캘린더에 없으니 갭으로 오인하지 않는다(캘린더 = 실제 데이터 있는 거래일).
 *
 * <p><b>§4c</b>: 데이터가 {@value #MIN_DATA_DAYS}거래일 미만이면 판정 불가로 <b>null</b>(0으로 위장 금지).
 * 최신 거래일에 매수 목록에 없으면 streak=0(연속 아님).
 */
public final class InvestorBuyStreakCalculator {

    private InvestorBuyStreakCalculator() {}

    /** 판정에 필요한 최소 거래일 수 — 이 미만이면 null(데이터 부족). */
    public static final int MIN_DATA_DAYS = 5;

    /**
     * 연속 순매수일. 순수 함수.
     *
     * @param tradeDates 데이터가 존재하는 거래일 목록(정렬 무관 — 내부에서 최신순 처리). 이 투자자의 캘린더.
     * @param buyDates   해당 종목이 이 투자자의 BUY(순매수 상위) 목록에 든 거래일 집합.
     * @return 최신 거래일부터 연속으로 매수한 일수(0 이상). 거래일이 {@value #MIN_DATA_DAYS} 미만이면 null.
     */
    public static Integer consecutiveNetBuyDays(List<LocalDate> tradeDates, Set<LocalDate> buyDates) {
        if (tradeDates == null || tradeDates.size() < MIN_DATA_DAYS) return null;
        if (buyDates == null || buyDates.isEmpty()) return 0;

        // 최신순 정렬(방어적 — 중복 제거 후 desc)
        List<LocalDate> desc = new ArrayList<>(new java.util.LinkedHashSet<>(tradeDates));
        desc.sort(Comparator.reverseOrder());
        if (desc.size() < MIN_DATA_DAYS) return null;

        int streak = 0;
        for (LocalDate d : desc) {
            if (buyDates.contains(d)) streak++;
            else break;
        }
        return streak;
    }
}
