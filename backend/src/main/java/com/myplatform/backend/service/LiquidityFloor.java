package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockPriceHistory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 거래대금 하한 — 매수 후보(종합추천)와 무작위 대조군이 같이 쓰는 단일 판정(2026-11-11, 순수 함수).
 *
 * <p><b>왜</b>: 그날 '오늘' 탭 후보 10건 중 5건이 20일 평균 거래대금 5억 원 미만(0.23~3.22억)이었다(한일화학 0.23억 — 하루 100만 원인
 * 날도 있다). 그런 종목은 화면 가격으로 사고팔 수 없고, D+3 성적(비용 0.18% 가정)도 실제로 낼 수 없는 수익을 잰다.
 *
 * <p><b>정의</b>: 저장된 마감 확정 봉 가운데 최근 {@value #WINDOW_BARS}개의 (종가 × 거래량) 평균, 억 원. 거래량이 NULL 인 봉은
 * 미수집이라 평균에서 빼고, 거래량 0 인 봉은 0 으로 센다(거래가 없던 날). 쓸 수 있는 봉이 {@value #MIN_USABLE_BARS}개
 * 미만이거나 가장 새 봉이 기술 채점의 노후 기준보다 오래되면 <b>판정 불가</b>(null) — 결측은 차단 근거가 아니다(§4c).
 */
public final class LiquidityFloor {

    public static final int WINDOW_BARS = 20;
    public static final int MIN_USABLE_BARS = 10;
    private static final BigDecimal EOK = BigDecimal.valueOf(100_000_000L);

    public enum Verdict { PASS, BELOW, UNKNOWN }

    private LiquidityFloor() {}

    /**
     * 20거래일 평균 거래대금(억 원). 모르면 null.
     *
     * @param minAcceptable 가장 새 봉이 이 날짜보다 오래되면 판정 불가 — 보통 직전 거래일. null 이면 노후 판정을 하지 않는다
     *                      (달력 조회 실패 — 기술 채점 {@code isPriceHistoryFresh} 와 같은 fail-open).
     */
    public static BigDecimal avgTradedValueEok(List<StockPriceHistory> rows, LocalDate minAcceptable) {
        if (rows == null || rows.isEmpty()) return null;
        List<StockPriceHistory> sorted = new ArrayList<>(rows.size());
        for (StockPriceHistory b : rows) if (b != null && b.getTradeDate() != null) sorted.add(b);
        if (sorted.isEmpty()) return null;
        sorted.sort(Comparator.comparing(StockPriceHistory::getTradeDate).reversed());
        if (minAcceptable != null && sorted.get(0).getTradeDate().isBefore(minAcceptable)) return null;

        BigDecimal sum = BigDecimal.ZERO;
        int usable = 0;
        for (StockPriceHistory b : sorted.subList(0, Math.min(WINDOW_BARS, sorted.size()))) {
            if (b.getVolume() == null || b.getClosePrice() == null) continue;   // 미수집 — 0 이 아니다
            sum = sum.add(b.getClosePrice().multiply(b.getVolume()));
            usable++;
        }
        if (usable < MIN_USABLE_BARS) return null;
        return sum.divide(BigDecimal.valueOf(usable), MathContext.DECIMAL64).divide(EOK, MathContext.DECIMAL64);
    }

    /** 하한 판정 — 하한이 0 이하면 끔(전부 통과), 평균을 모르면 UNKNOWN, 하한과 같으면 통과. */
    public static Verdict judge(BigDecimal avgEok, double floorEok) {
        if (!(floorEok > 0)) return Verdict.PASS;
        if (avgEok == null) return Verdict.UNKNOWN;
        return avgEok.compareTo(BigDecimal.valueOf(floorEok)) >= 0 ? Verdict.PASS : Verdict.BELOW;
    }

    /** 화면용 억 원 반올림(소수 둘째 자리). null 이면 null. */
    public static BigDecimal displayEok(BigDecimal avgEok) {
        return avgEok == null ? null : avgEok.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    static boolean isOff(double floorEok) { return !(floorEok > 0); }

}
