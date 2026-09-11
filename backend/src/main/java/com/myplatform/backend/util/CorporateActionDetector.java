package com.myplatform.backend.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 액면변경(병합/분할) 의심 판정 — <b>저장 이력과 현재가가 비교 불가능해진 종목</b>을 찾는다. 순수 함수.
 *
 * <p><b>왜 필요한가(2026-09-11 실측)</b>: 조일알미늄(018470) 현재가 4,865 가 저장가 973 의 <b>정확히 5.00배</b>로
 * 튀어 가격 이상치 그물에 걸렸는데, 로그는 {@code "응답 일괄 배수 오염 강력 의심"} 이라고 말했다. 실제로는
 * 오염이 아니라 <b>거래정지 중 액면병합(5:1)</b> 이었다 — 저장된 봉은 8/21~8/28 내내 973원·거래량 0 으로
 * 굳어 있었다. 오진이 위험한 이유는 사람을 KIS 응답 버그 추적으로 보내기 때문이다(그쪽엔 아무것도 없다).
 *
 * <p><b>둘을 가르는 유일한 증거는 정지 이력이다</b>: 배수 오염도 정수배를 만든다(×10 이 그 원형). 다른 점은
 * ① 액면변경은 <b>거래정지(거래량 0 봉 연속)</b> 뒤에 온다 ② 오염은 응답이 틀린 것이고 액면변경은 <b>응답이 맞고
 * 저장 이력이 낡은</b> 것이다. 그래서 정지 증거가 없으면 여기서는 판정하지 않는다({@link Kind#INSUFFICIENT}) —
 * "정수배니까 액면변경"이라고 단정하면 진짜 오염을 액면변경으로 덮어 §4c 를 반대 방향으로 어긴다.
 *
 * <p><b>⚠ 어느 쪽이 낡았는지를 데이터로 확인할 것(2026-09-11 오탐에서 배움)</b>: 처음엔 "봉이 낡고 현재가가
 * 신선하다"고 가정했다가 285800 을 방향이 뒤집힌 채 오탐했다. 실제로는 <b>KIS 일봉이 수정주가라 액면변경 뒤
 * 소급 보정</b>되고(002880·285800 의 봉은 이미 보정됨), 정지 종목은 아무도 시세를 안 불러 <b>{@code stock_price}
 * 캐시가 멈춘다</b>. 그래서 진짜 감지 대상은 <b>봉이 아직 보정 전인데 현재가는 신선한</b> 종목이다 —
 * 018470 이 그 모양이다(8/20 까지 정상 거래 → 8/21~8/28 정지 → 봉이 973 에서 끊김, 현재가 4,865).
 * {@link #MAX_PRICE_AGE_DAYS} 가 낡은 현재가를 걸러낸다.
 *
 * <p><b>행동은 하지 않는다</b>: 액면변경은 "제외 대상"이 아니다. 종목은 멀쩡하고 <b>이력만</b> 못 쓴다 —
 * {@code isActive} 게이트와 무관하고, 가격을 보정하지도 않는다(§3 미보정 불변식). 용도는 가시성뿐이다.
 */
public final class CorporateActionDetector {

    private CorporateActionDetector() {}

    /** 액면변경 의심 최소 배수 — 2:1 부터. 그 아래는 정상 등락과 구분되지 않는다. */
    static final BigDecimal MIN_MERGE_RATIO = new BigDecimal("2");
    static final BigDecimal MAX_SPLIT_RATIO = new BigDecimal("0.5");

    /**
     * 정수배 허용 오차 ±5%. 거래 재개 직후엔 KRX 기준가라 배수가 거의 정확하고, 재개 뒤 시간이 지날수록
     * 실제 등락만큼 벌어진다 — 오차를 더 키우면 "3.7배" 같은 그냥 급등까지 액면변경으로 읽는다.
     */
    static final BigDecimal RATIO_TOLERANCE = new BigDecimal("0.05");

    /** 이력이 굳었다고 보는 최소 거래량 0 봉 수 — 정지 감지({@code StockStatusService})와 같은 기준. */
    static final long MIN_ZERO_VOLUME_BARS = 3;

    /**
     * 현재가가 이보다 오래됐으면 판정하지 않는다 — <b>2026-09-11 오탐의 원인</b>.
     *
     * <p>처음엔 "봉이 낡고 현재가가 신선하다"고 가정했는데 <b>정확히 반대인 경우가 있다</b>: KIS 일봉은
     * <b>수정주가</b>라 액면변경 뒤 소급 보정되는 반면, 정지 종목은 아무도 시세를 조회하지 않아
     * {@code stock_price} 캐시가 그 자리에 멈춘다. 285800 은 봉 3,665(보정됨)·캐시 733(9/4 에 멈춤)이라
     * 방향이 뒤집힌 채 "액면분할 1:5"로 오탐했다. 낡은 값을 "현재가"로 쓰면 어느 쪽으로든 틀린다.
     */
    static final long MAX_PRICE_AGE_DAYS = 2;

    public enum Kind {
        /** 액면병합 의심 — 현재가 ≈ 저장가 × N. */
        MERGE_SUSPECTED,
        /** 액면분할 의심 — 현재가 ≈ 저장가 ÷ N. */
        SPLIT_SUSPECTED,
        /** 배수가 정수에 안 맞거나 폭이 작다 — 액면변경으로 보기 어렵다. */
        NOT_CLEAN,
        /** 가격 결측이거나 정지 증거 부족 — 판정하지 않는다(§4c). */
        INSUFFICIENT
    }

    /**
     * @param kind   분류
     * @param ratio  현재가/저장가 (소수 2자리). 판정 불가면 null.
     * @param factor 추정 액면 배수(병합 N:1 이면 N, 분할이면 N). 의심이 아니면 null.
     * @param detail 사람이 읽는 근거 한 줄.
     */
    public record Verdict(Kind kind, BigDecimal ratio, BigDecimal factor, String detail) {
        public boolean suspected() {
            return kind == Kind.MERGE_SUSPECTED || kind == Kind.SPLIT_SUSPECTED;
        }
    }

    /**
     * @param storedClose    저장 이력의 (굳은) 종가 — 보정 안 된 낡은 봉이어야 의미가 있다
     * @param currentPrice   현재가
     * @param priceAgeDays   현재가를 마지막으로 받아온 지 며칠 됐는지 — 낡으면 판정하지 않는다
     * @param zeroVolumeBars 최근 창에서 거래량 0 봉 수 — 정지 증거. 부족하면 판정하지 않는다.
     */
    public static Verdict judge(BigDecimal storedClose, BigDecimal currentPrice,
                                long priceAgeDays, long zeroVolumeBars) {
        if (storedClose == null || currentPrice == null
                || storedClose.compareTo(BigDecimal.ZERO) <= 0
                || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return new Verdict(Kind.INSUFFICIENT, null, null, "가격 결측 — 판정 skip");
        }
        if (priceAgeDays > MAX_PRICE_AGE_DAYS) {
            return new Verdict(Kind.INSUFFICIENT, null, null,
                    "현재가가 " + priceAgeDays + "일 묵음(> " + MAX_PRICE_AGE_DAYS
                            + ") — 정지 종목은 시세 조회가 멈춰 캐시가 낡고 봉은 수정주가로 보정된다. "
                            + "낡은 값을 현재가로 쓰면 방향이 뒤집힌다(2026-09-11 285800 오탐)");
        }
        if (zeroVolumeBars < MIN_ZERO_VOLUME_BARS) {
            return new Verdict(Kind.INSUFFICIENT, null, null,
                    "거래정지 증거 부족(거래량 0 봉 " + zeroVolumeBars + "개 < " + MIN_ZERO_VOLUME_BARS
                            + ") — 정수배여도 액면변경으로 단정하지 않는다(배수 오염일 수 있다)");
        }

        BigDecimal ratio = currentPrice.divide(storedClose, 4, RoundingMode.HALF_UP);

        if (ratio.compareTo(MIN_MERGE_RATIO) >= 0) {
            BigDecimal factor = ratio.setScale(0, RoundingMode.HALF_UP);
            if (isCloseToFactor(ratio, factor)) {
                return new Verdict(Kind.MERGE_SUSPECTED, scale2(ratio), factor,
                        "거래정지 " + zeroVolumeBars + "봉 뒤 현재가가 저장가의 " + factor.toPlainString()
                                + "배 — 액면병합(" + factor.toPlainString() + ":1) 의심, 저장 이력 비교 불가");
            }
            return new Verdict(Kind.NOT_CLEAN, scale2(ratio), null,
                    "배수 " + scale2(ratio).toPlainString() + " 가 정수에서 벗어남 — 액면변경으로 보기 어렵다");
        }

        if (ratio.compareTo(MAX_SPLIT_RATIO) <= 0) {
            BigDecimal inverse = storedClose.divide(currentPrice, 4, RoundingMode.HALF_UP);
            BigDecimal factor = inverse.setScale(0, RoundingMode.HALF_UP);
            if (isCloseToFactor(inverse, factor)) {
                return new Verdict(Kind.SPLIT_SUSPECTED, scale2(ratio), factor,
                        "거래정지 " + zeroVolumeBars + "봉 뒤 현재가가 저장가의 1/" + factor.toPlainString()
                                + " — 액면분할(1:" + factor.toPlainString() + ") 의심, 저장 이력 비교 불가");
            }
            return new Verdict(Kind.NOT_CLEAN, scale2(ratio), null,
                    "역배수 " + scale2(inverse).toPlainString() + " 가 정수에서 벗어남 — 액면변경으로 보기 어렵다");
        }

        return new Verdict(Kind.NOT_CLEAN, scale2(ratio), null,
                "배수 " + scale2(ratio).toPlainString() + " — 액면변경 폭이 아니다(정상 등락 범위)");
    }

    /** |ratio - factor| / factor ≤ 허용오차. */
    private static boolean isCloseToFactor(BigDecimal ratio, BigDecimal factor) {
        if (factor.compareTo(BigDecimal.ZERO) <= 0) return false;
        BigDecimal drift = ratio.subtract(factor).abs().divide(factor, 4, RoundingMode.HALF_UP);
        return drift.compareTo(RATIO_TOLERANCE) <= 0;
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
