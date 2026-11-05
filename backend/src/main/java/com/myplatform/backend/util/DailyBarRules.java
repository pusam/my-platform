package com.myplatform.backend.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** KIS 일봉 행을 읽는 규칙. 순수. */
public final class DailyBarRules {

    private DailyBarRules() {
    }

    /**
     * 오늘 날짜인데 거래가 없는 봉인가 — 장이 열리기 전 KIS 가 맨 앞에 주는 자리 봉이다(2026-11-06 실측: 현대차 08:17 맨 앞 행이 20261106,
     * 시·고·저·종 전부 전일 종가 293,500, 거래량 0). 아직 없는 봉이라 이평·볼린저·'오늘 시세'에 넣지 않는다.
     *
     * <p>거래정지 종목의 오늘 행도 같은 모양이고 역시 없는 봉이다. 지난 날짜의 거래량 0 봉은 정지 기록이라 남긴다. 거래량을 모르면(null) 봉으로 둔다.
     *
     * @param yyyymmdd KIS 영업일자
     */
    public static boolean isUntradedToday(String yyyymmdd, Long volume, LocalDate today) {
        return volume != null && volume == 0L && today != null
                && today.format(DateTimeFormatter.BASIC_ISO_DATE).equals(yyyymmdd);
    }
}
