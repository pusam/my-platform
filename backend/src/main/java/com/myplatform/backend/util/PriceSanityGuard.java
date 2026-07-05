package com.myplatform.backend.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 봇 진입 가격 sanity 가드 (순수 함수 — 가격 보정 없음, 주문 차단 판정만).
 *
 * <p>×10 가격 이상치(KIS UN 통합시세 의심, {@link PriceOutlierDiagnostics} 참조)가 봇 주문으로
 * 이어지는 것을 막는 방어선. 진입가가 <b>전일 종가 대비 ±50% 초과</b>면 {@code BLOCKED}.
 * KRX 일일 변동제한이 ±30%라 정상 장에서 오탐이 없다.
 *
 * <p><b>앵커 규약 (중요)</b>:
 * <ul>
 *   <li>앵커는 {@code StockPriceHistory}(KIS 일봉, <b>FID_COND_MRKT_DIV_CODE=J</b> KRX 단독) 종가를 쓴다.
 *       현재가 경로(UN 통합시세)와 소스가 분리돼 있어 UN 응답 전체가 ×10 이어도 앵커는 오염되지 않는다.</li>
 *   <li>KIS 응답 역산(현재가 − prdy_vrss)은 <b>앵커로 쓰면 안 된다</b> — 응답 전체 동일배수(BATCH_SCALED)면
 *       prdy_vrss 도 같이 ×10 이라 비율이 정상으로 위장되어 가드가 무력화된다.</li>
 *   <li>앵커 결측/0/오래됨(4일 초과)은 {@code UNKNOWN} = 통과 — 결측을 근거로 차단하지 않는다(§4c).
 *       연휴 + 연속 상한가면 정당하게 +50% 를 넘을 수 있어 낡은 앵커로는 판정하지 않는다.</li>
 * </ul>
 *
 * <p>§16-3(가격 이상치 로깅만·미보정)과 비충돌 — 가격을 고치는 게 아니라 <b>주문만 막는다</b>.
 */
public final class PriceSanityGuard {

    private PriceSanityGuard() {}

    /** 전일 종가 대비 허용 편차 — 이 값 "초과" 시 차단. (KRX 일일 제한 ±30% + 여유) */
    private static final BigDecimal MAX_DEVIATION = new BigDecimal("0.5");
    /** 앵커 유효 기간(캘린더일) — 초과하면 UNKNOWN. 주말+공휴일 커버, 연속 상한가 오탐 방지. */
    private static final long MAX_ANCHOR_AGE_DAYS = 4;

    public enum Verdict { SANE, BLOCKED, UNKNOWN }

    /**
     * 판정 결과.
     *
     * @param verdict SANE=정상 / BLOCKED=진입 차단 / UNKNOWN=판정 불가(통과)
     * @param ratio   진입가/앵커 비율(소수 2자리). 판정 불가면 null.
     * @param detail  사람이 읽는 근거 한 줄.
     */
    public record Result(Verdict verdict, BigDecimal ratio, String detail) {}

    /**
     * 진입가가 전일 종가(J 소스 앵커) 대비 ±50% 이내인지 판정한다.
     *
     * @param currentPrice  봇이 주문에 쓰려는 진입가
     * @param prevClose     앵커 종가(StockPriceHistory 최신 close) — null/0 이면 UNKNOWN
     * @param prevCloseDate 앵커 일자 — null 이면 신선도 판단 불가 = UNKNOWN
     * @param today         오늘(봇 Clock 기준)
     */
    public static Result judge(BigDecimal currentPrice, BigDecimal prevClose,
                               LocalDate prevCloseDate, LocalDate today) {
        if (currentPrice == null || currentPrice.signum() <= 0) {
            return new Result(Verdict.UNKNOWN, null, "진입가 없음/0 — 판정 불가");
        }
        if (prevClose == null || prevClose.signum() <= 0) {
            return new Result(Verdict.UNKNOWN, null, "앵커(전일 종가) 없음 — 판정 불가(통과)");
        }
        if (prevCloseDate == null || today == null) {
            return new Result(Verdict.UNKNOWN, null, "앵커 일자 없음 — 신선도 판단 불가(통과)");
        }
        long ageDays = ChronoUnit.DAYS.between(prevCloseDate, today);
        if (ageDays > MAX_ANCHOR_AGE_DAYS) {
            return new Result(Verdict.UNKNOWN, null,
                    "앵커 " + ageDays + "일 경과(>" + MAX_ANCHOR_AGE_DAYS + "일) — 연속 상한가 오탐 방지 위해 판정 skip");
        }

        BigDecimal ratio = currentPrice.divide(prevClose, 2, RoundingMode.HALF_UP);
        BigDecimal deviation = currentPrice.subtract(prevClose).abs()
                .divide(prevClose, 4, RoundingMode.HALF_UP);
        if (deviation.compareTo(MAX_DEVIATION) > 0) {
            return new Result(Verdict.BLOCKED, ratio,
                    "전일 종가 " + prevClose.toPlainString() + "(" + prevCloseDate + ") 대비 ×"
                            + ratio.toPlainString() + " — ±50% 초과, ×10 오염 의심으로 진입 차단");
        }
        return new Result(Verdict.SANE, ratio, "전일 종가 대비 ×" + ratio.toPlainString() + " — 정상 범위");
    }
}
