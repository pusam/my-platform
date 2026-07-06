package com.myplatform.backend.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * ATR×2.5 동적 청산 레벨 — <b>순수 함수</b> (ATR 세트, VIRTUAL 전용 경로에서만 사용).
 *
 * <p>손절폭 = k×ATR ÷ 진입가(%), 익절폭 = 손절폭 × (5/3) — exit 백테스트(atr_x2.5,
 * "ATR 익절 = k×ATR×1.667, 현행 -3/+5 손익비 유지")와 동일 정의. 레벨은 <b>진입 시점 1회 계산 후
 * 스냅샷 고정</b>(사후 ATR 변동 반영 금지)이 계약 — 고정은 호출부(포지션 필드/영속)가 담당.
 *
 * <p>§4c: 입력 결측·무의미 → null — 호출부는 현행 고정 -3/+5 로 폴백해야 한다.
 */
public final class AtrExitRule {

    /** ATR 배수 k — exit 백테스트 최우수 변형(atr_x2.5). */
    public static final BigDecimal K = new BigDecimal("2.5");
    /** 익절/손절 비 — 현행 -3/+5 의 손익비(5/3) 유지(백테스트와 동일). */
    static final BigDecimal REWARD_RISK = new BigDecimal("5")
            .divide(new BigDecimal("3"), 8, RoundingMode.HALF_UP);

    /** 청산 레벨 — stopPct 는 음수 %(현행 SWING_STOP_LOSS 비교 규약과 동일 부호), targetPct 는 양수 %. */
    public record Levels(BigDecimal stopPct, BigDecimal targetPct) {}

    private AtrExitRule() {}

    /**
     * 진입가·ATR → 청산 레벨. 결측/무의미(≤0)면 null(호출부 현행 폴백).
     * 예: 진입 10,000원·ATR 300원 → stop −7.50% / target +12.50%.
     */
    public static Levels judge(BigDecimal entryPrice, BigDecimal atr) {
        if (entryPrice == null || entryPrice.signum() <= 0) return null;
        if (atr == null || atr.signum() <= 0) return null;
        BigDecimal stopWidthPct = K.multiply(atr)
                .divide(entryPrice, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        if (stopWidthPct.signum() <= 0) return null;
        BigDecimal targetPct = stopWidthPct.multiply(REWARD_RISK).setScale(4, RoundingMode.HALF_UP);
        return new Levels(stopWidthPct.setScale(4, RoundingMode.HALF_UP).negate(), targetPct);
    }
}
