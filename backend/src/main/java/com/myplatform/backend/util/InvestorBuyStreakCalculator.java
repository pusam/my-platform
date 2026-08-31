package com.myplatform.backend.util;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 투자자(외인/기관) 연속 순매수일 계산 — <b>순수 함수, 표시·참고 전용(산식 미편입, unverified)</b>.
 *
 * <p>InvestorDailyTrade 는 투자자별 <b>순매수 상위 종목(top-N)</b>만 적재한다. 따라서 어떤 종목이
 * 특정 거래일의 "순매수 목록(BUY)"에 들었으면 그날 그 투자자의 강한 순매수 대상이었다는 뜻.
 * "연속 순매수일" = <b>최신 데이터일부터 실제 거래일을 하루씩 거슬러 그 종목이 BUY 목록에
 * 연속으로 든 일수</b>.
 *
 * <p><b>결측일은 연속을 끊는다(AUDIT R3, 2026-08-31)</b>: 이전 구현은 "데이터가 있는 날짜"만
 * 이어 세서 <b>수집이 실패한 거래일을 주말처럼 건너뛰었다</b> — 결측 3일을 사이에 두고도
 * "5일 연속" 배지가 나왔다(부풀림, §4c 위반). 점수용 {@code resolveConsecutiveBuyDates}
 * (2026-07-28 수정)와 같은 의미론으로 통일한다: 휴장일은 건너뛰고, <b>거래일인데 데이터가
 * 없으면 그날 매수 여부를 알 수 없으므로 끊는다.</b> 임시공휴일 등 달력 미수록 휴일은 결측으로
 * 보여 끊길 수 있으나 과소 판정 방향이라 안전한 열화다.
 *
 * <p>앵커는 이 투자자의 <b>최신 데이터일</b>이다 — 수집이 통째로 며칠 죽으면 배지가 옛 날짜
 * 기준이 된다. 그 노후 판정은 호출측(§4c 노후 가드)의 몫이고 이 함수는 부풀림만 막는다.
 *
 * <p><b>§4c</b>: 데이터가 {@value #MIN_DATA_DAYS}거래일 미만이면 판정 불가로 <b>null</b>(0으로 위장 금지).
 * 최신 데이터일에 매수 목록에 없으면 streak=0(연속 아님).
 */
public final class InvestorBuyStreakCalculator {

    private InvestorBuyStreakCalculator() {}

    /** 판정에 필요한 최소 거래일 수 — 이 미만이면 null(데이터 부족). */
    public static final int MIN_DATA_DAYS = 5;

    /**
     * 연속 순매수일. 순수 함수.
     *
     * @param tradeDates     데이터가 존재하는 거래일 목록(정렬 무관). 이 투자자의 수집 캘린더.
     * @param buyDates       해당 종목이 이 투자자의 BUY(순매수 상위) 목록에 든 거래일 집합.
     * @param isMarketClosed 휴장일 판정(주말+휴일) — 휴장일은 결측이 아니라 정상 공백.
     * @return 최신 데이터일부터 연속 매수 일수(0 이상). 거래일 {@value #MIN_DATA_DAYS} 미만이면 null.
     */
    public static Integer consecutiveNetBuyDays(List<LocalDate> tradeDates, Set<LocalDate> buyDates,
                                                Predicate<LocalDate> isMarketClosed) {
        if (tradeDates == null) return null;
        Set<LocalDate> data = new HashSet<>(tradeDates);
        if (data.size() < MIN_DATA_DAYS) return null;
        if (buyDates == null || buyDates.isEmpty()) return 0;

        LocalDate anchor = data.stream().max(Comparator.naturalOrder()).orElseThrow();
        LocalDate floor = data.stream().min(Comparator.naturalOrder()).orElseThrow();

        int streak = 0;
        LocalDate d = anchor;
        while (!d.isBefore(floor)) {
            if (isMarketClosed.test(d)) {          // 휴장일 — 정상 공백, 건너뛴다
                d = d.minusDays(1);
                continue;
            }
            if (!data.contains(d)) break;           // 거래일인데 수집 결측 — 알 수 없음 → 끊는다(§4c)
            if (!buyDates.contains(d)) break;       // 매수 목록에 없음 — 연속 종료
            streak++;
            d = d.minusDays(1);
        }
        return streak;
    }
}
