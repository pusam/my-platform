package com.myplatform.backend.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 리스크 균등 포지션 사이징 — <b>순수 함수</b> (ATR 세트, VIRTUAL 전용 경로에서만 호출).
 *
 * <p>아이디어: "한 포지션이 손절에 걸렸을 때 잃는 금액"을 종목·변동성 무관하게 riskBudget 으로 균등화.
 * {@code 수량 = riskBudget ÷ (현재가 × 손절폭%)}. ATR 손절(넓음)일수록 수량이 줄어 — exit 백테스트의
 * "개별 손실 확대(-5% 초과 35%)" 트레이드오프를 금액 단위에서 상쇄하는 세트의 반쪽.
 *
 * <p><b>안전 계약</b>:
 * <ul>
 *   <li>결과는 <b>항상 현행 수량 이하</b> — 상한 = min(계좌자산×maxPositionPct, 현행상한금액).
 *       리스크 균등이 현행보다 공격적으로 커지는 일은 없다(수량 축소 전용).</li>
 *   <li>손절폭/riskBudget <b>결측·무의미 → 현행 고정 금액 폴백</b>(§4c: 결측을 근거로 주문 확대 금지 —
 *       폴백도 현행상한 기반이라 현행 이하).</li>
 * </ul>
 */
public final class PositionSizer {

    private PositionSizer() {}

    /**
     * 주문 수량 판정.
     *
     * @param totalAsset       계좌 총자산(현금+평가) — 비율 상한 계산용
     * @param currentPrice     진입가
     * @param stopPct          손절폭(<b>양수 %</b>, 예: ATR×2.5/진입가면 7.5) — null/≤0 = 결측(폴백)
     * @param riskBudgetKrw    포지션당 감수 손실액(원) — null/≤0 = 결측(폴백)
     * @param maxPositionPct   종목당 최대 투자비율(예: 0.50) — null 이면 현행상한만 적용
     * @param currentCapAmount 현행 산식의 투자금 상한(= min(가용현금, totalAsset×현행비율)) — 폴백·상한 기준
     * @return 주문 수량(주). 판정 불가(가격/상한 무의미)면 0.
     */
    public static int judge(BigDecimal totalAsset, BigDecimal currentPrice, BigDecimal stopPct,
                            BigDecimal riskBudgetKrw, BigDecimal maxPositionPct,
                            BigDecimal currentCapAmount) {
        if (currentPrice == null || currentPrice.signum() <= 0) return 0;
        if (currentCapAmount == null || currentCapAmount.signum() <= 0) return 0;

        int fallbackQty = currentCapAmount.divide(currentPrice, 0, RoundingMode.DOWN).intValue();

        // 결측 → 현행 폴백 (§4c: 결측 근거 주문 확대 금지 — 폴백 = 현행 산식 그대로)
        if (stopPct == null || stopPct.signum() <= 0) return fallbackQty;
        if (riskBudgetKrw == null || riskBudgetKrw.signum() <= 0) return fallbackQty;

        // 리스크 균등 수량: riskBudget ÷ (가격 × 손절폭)
        BigDecimal lossPerShare = currentPrice.multiply(stopPct)
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        if (lossPerShare.signum() <= 0) return fallbackQty;
        int riskQty = riskBudgetKrw.divide(lossPerShare, 0, RoundingMode.DOWN).intValue();

        // 상한: 현행상한 + (있으면) 자산×비율 — 둘 중 작은 금액
        BigDecimal capAmount = currentCapAmount;
        if (totalAsset != null && totalAsset.signum() > 0
                && maxPositionPct != null && maxPositionPct.signum() > 0) {
            capAmount = capAmount.min(totalAsset.multiply(maxPositionPct));
        }
        int capQty = capAmount.divide(currentPrice, 0, RoundingMode.DOWN).intValue();

        return Math.max(0, Math.min(riskQty, capQty));
    }
}
