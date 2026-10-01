package com.myplatform.backend.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 투자자 일별 수급이 "그날의 확정 기록"인가 — 순수 판정(2026-10-01 감사).
 *
 * <p>KIS 투자자 매매종목가집계는 <b>당일 값만</b> 주고, 장중에는 그 시각까지의 잠정 집계다. 그래서 그날 기록은 장 마감 뒤
 * 정규 수집({@link #CONFIRMED_FROM} 15:50)이 쓴 행만 확정으로 본다. 운영 실측: 9/30 11:12 배포 뒤 11:13 부팅 수집이
 * 잠정 156행을 9/30 일별 기록으로 저장했고, 15:50 이 159행으로 갈아끼울 때까지 그날 11:30·14:00 종합추천의 수급 축이
 * 잠정치로 계산됐다. 15:50 이 실패하면 16:00·18:00 보완 수집은 "행 있음"만 보고 건너뛰어 잠정치가 영구화됐다.
 *
 * <p>확정 = ① 외국인·기관 행이 둘 다 있고(한쪽 실패는 부분 수집) ② 그날 행이 전부 확정 시각 이후 저장됐다.
 * 연기금은 기관 응답에서 뽑아 0건일 수 있어 조건에 넣지 않는다(§4c — 0건은 0건).
 */
public final class InvestorDailyConfirmation {

    /** 정규 수집 시각 — 이 시각 전에 저장된 그날 행은 장중 잠정치다. */
    public static final LocalTime CONFIRMED_FROM = LocalTime.of(15, 50);

    private InvestorDailyConfirmation() {
    }

    /** 지금 오늘 값을 확정치로 받을 수 있는 시각인가. */
    public static boolean isConfirmedWindow(LocalDateTime now) {
        return now != null && !now.toLocalTime().isBefore(CONFIRMED_FROM);
    }

    /**
     * 그날 행이 확정 기록인가.
     *
     * @param summary {@code [investorType, MIN(createdAt)]} 행들 — 그날 투자자 유형별 최초 저장 시각
     */
    public static boolean isConfirmed(LocalDate tradeDate, List<Object[]> summary) {
        if (tradeDate == null || summary == null || summary.isEmpty()) return false;
        boolean foreign = false, institution = false;
        LocalDateTime earliest = null;
        for (Object[] row : summary) {
            if (row == null || row.length < 2 || row[0] == null) continue;
            String type = row[0].toString();
            if ("FOREIGN".equals(type)) foreign = true;
            if ("INSTITUTION".equals(type)) institution = true;
            if (row[1] instanceof LocalDateTime t && (earliest == null || t.isBefore(earliest))) earliest = t;
        }
        // 저장 시각을 모르면 확정이라 단정하지 않는다 — 다시 받는 쪽이 안전하다(같은 날 값을 지우고 다시 넣는다).
        return foreign && institution && earliest != null
                && !earliest.isBefore(tradeDate.atTime(CONFIRMED_FROM));
    }

    /** 행은 있는데 확정이 아닌가(잠정치·부분 수집) — 로그 사유용. */
    public static boolean hasRows(List<Object[]> summary) {
        return summary != null && !summary.isEmpty();
    }
}
