package com.myplatform.backend.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * P0-2 ×10 가격 이상치 <b>근본원인 진단</b> 분류기 (순수 함수 — 가격 보정 없음).
 *
 * <p>KIS {@code FHKST01010100} + {@code FID_COND_MRKT_DIV_CODE=UN}(KRX+NXT 통합시세) 응답에서
 * 일부 종목의 현재가가 실거래가의 ×10 으로 표시되는 현상의 <b>두 가설</b>을, 응답 필드 간 관계로 구분한다:
 *
 * <ul>
 *   <li><b>{@code CURRENT_FIELD_OUTLIER} (현재가 필드 단독 오염)</b> — 고가/저가는 정상인데 현재가만
 *       당일 밴드를 크게 벗어남. 특정 필드만 잘못 읽혔거나(매핑 오류) 단독 스케일된 경우. → "필드 매핑 오류" 가설.</li>
 *   <li><b>{@code BATCH_SCALED} (응답 전체 동일배수)</b> — 현재가·고가·저가가 모두 같은 배수로 부풀어
 *       응답 내부적으로는 자기일관(현재가가 [저가,고가] 안)이고 등락률도 정상이지만, 직전 정상가(DB 앵커)
 *       대비 비율이 ~10 으로 튄다. 통합시세 필드 규약 차이로 <b>응답 자체가 ×10</b> 인 경우. → "응답 자체가 ×10" 가설.</li>
 *   <li><b>{@code AMBIGUOUS}</b> — 밴드는 정상인데 등락률만 비정상 등, 단일 가설로 단정 불가.</li>
 *   <li><b>{@code NORMAL}</b> — 이상 징후 없음.</li>
 * </ul>
 *
 * <p>응답만으로는 BATCH_SCALED 를 단정할 수 없다(통배수면 응답이 자기일관이라 정상과 구분 불가). 그래서
 * 직전 정상가(DB 앵커)를 함께 받아 "응답 자체가 ×10" 을 확정한다. 이 분류는 <b>탐지/로깅 전용</b>이며
 * 가격을 보정하지 않는다.
 */
public final class PriceOutlierDiagnostics {

    private PriceOutlierDiagnostics() {}

    /** 당일 밴드 허용폭 — 현재가가 [저가×0.9, 고가×1.1] 밖이면 단독 오염 의심. */
    private static final BigDecimal BAND_LOW = new BigDecimal("0.9");
    private static final BigDecimal BAND_HIGH = new BigDecimal("1.1");
    /** KRX 일일 변동제한(±30%) 여유분. 등락률이 이 값을 넘으면 비정상. */
    private static final BigDecimal CTRT_LIMIT = new BigDecimal("31");
    /** DB 앵커 배수 임계 — 6연속 상한가도 불가능한 수준이라 정상 등락 오탐 없음. */
    private static final BigDecimal ANCHOR_HIGH = new BigDecimal("5");
    private static final BigDecimal ANCHOR_LOW = new BigDecimal("0.2");

    public enum Kind { NORMAL, CURRENT_FIELD_OUTLIER, BATCH_SCALED, AMBIGUOUS }

    /**
     * 진단 결과.
     *
     * @param kind     분류
     * @param multiple 추정 배수(앵커/밴드 대비, 반올림). 없으면 null.
     * @param detail   사람이 읽는 근거 한 줄.
     */
    public record Result(Kind kind, BigDecimal multiple, String detail) {
        /** 로그/grep 용 태그. 예: {@code 진단=BATCH_SCALED(x10)} */
        public String tag() {
            return "진단=" + kind + (multiple != null ? "(x" + multiple.toPlainString() + ")" : "");
        }
    }

    /**
     * 가격 필드 관계로 ×10 이상치의 근본원인을 분류한다.
     *
     * @param cur          현재가(stck_prpr)
     * @param high         당일 고가(stck_hgpr) — null/0 이면 밴드 판정 skip
     * @param low          당일 저가(stck_lwpr) — null/0 이면 밴드 판정 skip
     * @param changeRate   전일대비율(prdy_ctrt) — null 이면 정상으로 간주
     * @param dbAnchorPrev 직전 저장된 정상가(DB) — null/0 이면 "응답 자체 ×10" 확정 불가
     */
    public static Result classify(BigDecimal cur, BigDecimal high, BigDecimal low,
                                  BigDecimal changeRate, BigDecimal dbAnchorPrev) {
        if (cur == null || cur.compareTo(BigDecimal.ZERO) <= 0) {
            return new Result(Kind.NORMAL, null, "현재가 없음/0 — 판정 skip");
        }

        boolean haveBand = high != null && low != null
                && high.compareTo(BigDecimal.ZERO) > 0 && low.compareTo(BigDecimal.ZERO) > 0
                && high.compareTo(low) >= 0;
        boolean inBand = true;
        if (haveBand) {
            BigDecimal lowBound = low.multiply(BAND_LOW);
            BigDecimal highBound = high.multiply(BAND_HIGH);
            inBand = cur.compareTo(lowBound) >= 0 && cur.compareTo(highBound) <= 0;
        }
        boolean ctrtNormal = changeRate == null || changeRate.abs().compareTo(CTRT_LIMIT) <= 0;

        // [가설 A] 현재가 필드 단독 오염 — 고/저가는 정상인데 현재가만 밴드 밖.
        //   밴드 밖이라는 것 자체가 "현재가 한 필드만 어긋났다"는 강한 신호 → 매핑 오류 쪽.
        if (haveBand && !inBand) {
            BigDecimal mult = cur.divide(high, 1, RoundingMode.HALF_UP);
            return new Result(Kind.CURRENT_FIELD_OUTLIER, roundMultiple(mult),
                    "현재가가 당일 밴드 밖 — 현재가 단독 오염(필드 매핑) 의심");
        }

        // [가설 B] 응답 전체 동일배수 — 밴드 안(자기일관)이라 응답만으론 못 잡고, DB 앵커 배수로 확정.
        if (dbAnchorPrev != null && dbAnchorPrev.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal ratio = cur.divide(dbAnchorPrev, 2, RoundingMode.HALF_UP);
            boolean scaled = ratio.compareTo(ANCHOR_HIGH) >= 0 || ratio.compareTo(ANCHOR_LOW) <= 0;
            if (scaled) {
                if (ctrtNormal) {
                    return new Result(Kind.BATCH_SCALED, roundMultiple(ratio),
                            "응답 전체 동일배수(현재가/고/저가 함께 스케일, 등락률 정상) — 응답 자체 ×N 의심");
                }
                return new Result(Kind.AMBIGUOUS, roundMultiple(ratio),
                        "앵커 배수 튐 + 등락률 비정상 — 통배수/매핑 혼재 의심");
            }
        }

        // 밴드는 정상인데 등락률만 제한 초과 → 단일 가설 단정 불가.
        if (!ctrtNormal) {
            return new Result(Kind.AMBIGUOUS, null, "밴드 정상인데 등락률 제한 초과 — 등락률 필드 의심");
        }

        return new Result(Kind.NORMAL, null, "이상 징후 없음");
    }

    /**
     * 배수를 가까운 정수로 반올림(예: 9.8/10.2 → 10). 0.5 미만은 소수 1자리 유지.
     */
    private static BigDecimal roundMultiple(BigDecimal raw) {
        if (raw == null) return null;
        if (raw.compareTo(BigDecimal.ONE) >= 0) {
            return raw.setScale(0, RoundingMode.HALF_UP);
        }
        return raw.setScale(1, RoundingMode.HALF_UP);
    }
}
