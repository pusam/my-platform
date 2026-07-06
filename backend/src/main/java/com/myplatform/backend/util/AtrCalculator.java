package com.myplatform.backend.util;

import com.myplatform.backend.entity.StockPriceHistory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * ATR(Average True Range, Wilder) — <b>순수 계산 유틸</b>. 봇 ATR 세트(사이징/청산)와 UI 표시가 공용.
 *
 * <p>소스는 {@link StockPriceHistory} 일봉(J=KRX 단독 — PriceSanityGuard 앵커와 동일 소스).
 * TR = max(H−L, |H−C₍₋₁₎|, |L−C₍₋₁₎|), 초기 ATR = 첫 14 TR 단순평균, 이후 Wilder 평활
 * ((prev×13 + TR)÷14) — 백테스트(app/backtest, exit_backtest)와 동일 정의.
 *
 * <p><b>§4c</b>: 유효 연속 일봉이 15개(TR 14개) 미만이거나 최신 구간에 OHLC 결측이 끼면 <b>null</b>(미산출)
 * — 결측을 그럴듯한 변동성으로 위장하지 않는다. 호출부는 null 이면 현행 로직으로 폴백해야 한다.
 */
public final class AtrCalculator {

    /** ATR 기간 — 백테스트(ATR14)와 동일. */
    public static final int PERIOD = 14;

    private AtrCalculator() {}

    /**
     * ATR(14) — 입력은 리포지토리 표준 정렬(<b>최신순</b>, {@code findByStockCodeOrderByTradeDateDesc}).
     *
     * <p>최신 행에서 과거로 내려가며 <b>연속 유효(H·L·C 모두 양수)</b> 구간만 사용 — 결측 행을 만나면 중단
     * (결측 너머의 과거 데이터로 이어 붙이면 갭이 TR 을 오염). 유효 구간이 {@value #PERIOD}+1 미만이면 null.
     *
     * @return ATR 절대값(원, scale 4) 또는 null(미산출)
     */
    public static BigDecimal atr14(List<StockPriceHistory> rowsLatestFirst) {
        if (rowsLatestFirst == null || rowsLatestFirst.size() < PERIOD + 1) return null;

        // 최신→과거로 훑으며 연속 유효 구간 수집 후 시간순(과거→최신)으로 뒤집는다.
        List<StockPriceHistory> valid = new ArrayList<>();
        for (StockPriceHistory h : rowsLatestFirst) {
            if (h == null || !isValidOhlc(h)) break;
            valid.add(h);
        }
        if (valid.size() < PERIOD + 1) return null;
        List<StockPriceHistory> chrono = new ArrayList<>(valid);
        java.util.Collections.reverse(chrono);

        // TR 시계열 (chrono[0] 은 prevClose 전용)
        List<BigDecimal> trs = new ArrayList<>();
        for (int i = 1; i < chrono.size(); i++) {
            BigDecimal high = chrono.get(i).getHighPrice();
            BigDecimal low = chrono.get(i).getLowPrice();
            BigDecimal prevClose = chrono.get(i - 1).getClosePrice();
            BigDecimal tr = high.subtract(low)
                    .max(high.subtract(prevClose).abs())
                    .max(low.subtract(prevClose).abs());
            trs.add(tr);
        }
        if (trs.size() < PERIOD) return null;

        // Wilder: 초기 = 첫 14 TR 단순평균, 이후 (prev×13 + TR)÷14
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < PERIOD; i++) sum = sum.add(trs.get(i));
        BigDecimal atr = sum.divide(BigDecimal.valueOf(PERIOD), 8, RoundingMode.HALF_UP);
        BigDecimal periodMinus1 = BigDecimal.valueOf(PERIOD - 1);
        BigDecimal period = BigDecimal.valueOf(PERIOD);
        for (int i = PERIOD; i < trs.size(); i++) {
            atr = atr.multiply(periodMinus1).add(trs.get(i)).divide(period, 8, RoundingMode.HALF_UP);
        }
        if (atr.signum() <= 0) return null;   // 전 구간 H=L=C 등 무의미 변동성 — 사이징 분모 0 방지
        return atr.setScale(4, RoundingMode.HALF_UP);
    }

    private static boolean isValidOhlc(StockPriceHistory h) {
        return h.getHighPrice() != null && h.getHighPrice().signum() > 0
                && h.getLowPrice() != null && h.getLowPrice().signum() > 0
                && h.getClosePrice() != null && h.getClosePrice().signum() > 0
                && h.getHighPrice().compareTo(h.getLowPrice()) >= 0;
    }
}
