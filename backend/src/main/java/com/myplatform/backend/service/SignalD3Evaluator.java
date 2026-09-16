package com.myplatform.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "기록시점 → D+3 KRX 종가" 교정 평가 — 순수 함수(2026-09-17, V59).
 *
 * <p><b>왜 새로 만들었나.</b> 기존 3일 평가는 배치가 도는 시점의 현재가를 "3일 뒤 가격"으로 저장했다.
 * 배치가 밀리면 5일·6일째 시세가 들어가고, 19:30 시세는 KRX 종가가 아니라 NXT 야간 거래가일 수
 * 있었다. 최고·최저가 창도 상한이 없어 늦게 평가되면 4~7일째 봉이 섞였다. 그래서 정상 평가와
 * 며칠 늦은 평가가 <b>다른 값</b>을 냈다 — 측정이 실행 시각에 의존하면 성적표가 아니다.
 *
 * <p><b>정의.</b> 시작 = 기록 시점 가격(장중, NXT 시간대일 수 있음 — 바꾸지 않는다). 끝 = 거래일
 * 달력이 정한 D+3 의 KRX 종가. 창 = 정확히 D+1·D+2·D+3 세 봉. 지수도 같은 D+3 종가. 이 함수는
 * <b>날짜 목록을 입력으로 받는다</b> — 달력이 먼저 날짜를 정하고, 그 날짜의 봉이 없으면 다음 봉으로
 * 미루지 않고 사유를 남긴다(미루면 평가 기간이 다시 늘어난다).
 *
 * <p><b>실행 시각과 무관하다.</b> 입력이 같으면 오늘 돌리든 열흘 뒤 돌리든 같은 값이다 —
 * 그게 이 클래스가 순수 함수인 이유이고, 회귀 테스트가 고정하는 첫 번째 성질이다.
 *
 * <p><b>모르는 것은 사유로 남긴다(§4c).</b> 봉 결측을 0 으로, 거래정지 동결가를 수익 0% 로,
 * 액면변경으로 단위가 어긋난 봉을 -80% 손실로 위장하지 않는다.
 */
public final class SignalD3Evaluator {

    private SignalD3Evaluator() {}

    /** 창 길이(거래일). 컬럼 이름 d3_* 와 짝. */
    public static final int WINDOW_TRADING_DAYS = 3;

    /**
     * 단위 어긋남 판정 — <b>D0 종가 ÷ 기록시점 가격</b>이 이 범위를 벗어나면 의심.
     *
     * <p><b>수학적 전제(2026-09-17 코덱스 리뷰로 교정)</b>: 상하한가 ±30% 는 <i>전일 종가</i> 기준이다.
     * 기록시점 가격과 D0 종가는 <b>둘 다</b> 전일 종가의 0.7~1.3 배 안에 있으므로, 둘의 비율은
     * 0.7/1.3 = 0.538 ~ 1.3/0.7 = 1.857 까지 정상이다(전일 100·기록가 80·종가 115 → 1.4375 는 정상).
     * 처음엔 "같은 날 두 가격도 ±30%"로 잘못 잡아 0.65~1.35 를 썼다 — 정상 변동을 액면변경으로 제외했다.
     *
     * <p>⚠ <b>이 검사는 거친 안전망이다 — 소급 보정을 "잡는다"고 보장하지 않는다.</b> 5:1·10:1 처럼
     * 비율이 정상 범위 밖으로 확실히 나가는 재조정만 걸린다. 2:1(0.5)은 하한 0.538 을 아슬하게 밑돌아
     * 걸리지만 경계값이고, 3:2(0.667)·5:4 처럼 <b>비율이 1 에 가까운 재조정은 못 잡는다</b>
     * ({@code SignalD3EvaluatorTest} 가 그 한계를 그대로 고정한다). 작은 비율의 재조정은 별도 이벤트
     * 소스(분할 공시) 없이는 확인할 수 없다 — 그 행은 수익률이 왜곡된 채 OK 로 남을 수 있다.
     */
    static final BigDecimal UNIT_RATIO_MIN = new BigDecimal("0.53");
    static final BigDecimal UNIT_RATIO_MAX = new BigDecimal("1.90");
    /** D0 봉이 없을 때의 폴백 — D+1 종가는 두 세션(0.7²/1.3 ~ 1.3²/0.7 = 0.377~2.414)이라 더 넓다. */
    static final BigDecimal UNIT_RATIO_MIN_D1 = new BigDecimal("0.37");
    static final BigDecimal UNIT_RATIO_MAX_D1 = new BigDecimal("2.45");

    public enum Status {
        /** 평가 완료. */
        OK,
        /** D+3 이 아직 안 지났다 — 저장하지 않는다. */
        NOT_DUE,
        /** 기록 시점 가격이 없다/0 — 복원 불가. */
        NO_START_PRICE,
        /** D+1..D+3 중 봉이 하나라도 없다 — 재시도 가능(봉을 받아오면 풀린다). */
        MISSING_BARS,
        /** 창 안에 거래량 0 봉 — 동결가라 수익률이 의미 없다. */
        HALTED_IN_WINDOW,
        /** 봉 종가와 기록시점 가격의 단위가 어긋난다(수정주가 소급 보정 의심). */
        UNIT_MISMATCH_SUSPECT,
        /** 액면변경 감지기가 표시한 종목. */
        CORPORATE_ACTION_SUSPECT,
        /** D+3 지수 종가가 없다 — <b>미평가</b>(재시도 가능). 절대수익도 남기지 않는다. */
        NO_INDEX
    }

    /** 일봉 한 건 — 엔티티 의존을 끊은 최소 표현. volume null = 미수집(0 과 다르다). */
    public record Bar(LocalDate date, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume) {}

    /**
     * 평가 결과. 상태가 OK 가 아니면 수익 필드는 <b>전부</b> null 이다 — 부분 값을 남겨 "일부는 맞다"로
     * 읽히지 않게 한다. 지수가 없으면(NO_INDEX) 절대수익도 남기지 않는다: 시작·종료 시점을 맞춘
     * 초과수익이 이 교정의 목적이라, 지수 없는 행은 비교표·적중률에서 빠져야 한다(2026-09-17 합의).
     */
    public record Result(
            Status status,
            LocalDate endDate,
            BigDecimal close,
            BigDecimal pctChange,
            BigDecimal bmClose,
            BigDecimal bmReturn,
            BigDecimal alpha,
            BigDecimal mfePct,
            BigDecimal maePct,
            Boolean hit,
            String note
    ) {
        static Result of(Status status, LocalDate endDate, String note) {
            return new Result(status, endDate, null, null, null, null, null, null, null, null, note);
        }
    }

    /**
     * 평가한다.
     *
     * @param today          판정 기준일(KST). D+3 이 이보다 뒤면 NOT_DUE
     * @param window         달력이 정한 [D+1, D+2, D+3] — 정확히 3개
     * @param priceAtSignal  기록 시점 가격
     * @param bmAtSignal     기록 시점 KOSPI (없으면 null)
     * @param barsByDate     종목 일봉(D0 포함 권장 — 단위 판정에 쓴다)
     * @param indexCloseD3   D+3 KOSPI 종가(없으면 null → NO_INDEX)
     * @param corporateActionLabel 액면변경 감지기 표시(없으면 null)
     */
    public static Result evaluate(LocalDate today,
                                  List<LocalDate> window,
                                  BigDecimal priceAtSignal,
                                  BigDecimal bmAtSignal,
                                  Map<LocalDate, Bar> barsByDate,
                                  BigDecimal indexCloseD3,
                                  String corporateActionLabel) {
        if (window == null || window.size() != WINDOW_TRADING_DAYS) {
            throw new IllegalArgumentException("window 는 정확히 " + WINDOW_TRADING_DAYS + "개 거래일이어야 한다");
        }
        LocalDate end = window.get(WINDOW_TRADING_DAYS - 1);
        if (today == null || end.isAfter(today)) {
            return Result.of(Status.NOT_DUE, end, "D+3 " + end + " 미도래");
        }
        if (priceAtSignal == null || priceAtSignal.signum() <= 0) {
            return Result.of(Status.NO_START_PRICE, end, "기록 시점 가격 없음 — 복원 불가");
        }
        if (corporateActionLabel != null && !corporateActionLabel.isBlank()) {
            return Result.of(Status.CORPORATE_ACTION_SUSPECT, end, "액면변경 의심: " + corporateActionLabel);
        }

        // 창의 세 봉 — 하나라도 없으면 미루지 않고 사유를 남긴다.
        List<Bar> bars = new ArrayList<>();
        List<LocalDate> missing = new ArrayList<>();
        for (LocalDate d : window) {
            Bar b = barsByDate == null ? null : barsByDate.get(d);
            if (b == null || b.close() == null || b.close().signum() <= 0) missing.add(d);
            else bars.add(b);
        }
        if (!missing.isEmpty()) {
            return Result.of(Status.MISSING_BARS, end, "봉 없음: " + missing);
        }
        for (Bar b : bars) {
            // volume null 은 미수집(§4c) — 0 만 동결로 본다.
            if (b.volume() != null && b.volume().signum() == 0) {
                return Result.of(Status.HALTED_IN_WINDOW, end, "거래량 0 봉: " + b.date());
            }
        }

        // 단위 어긋남 — D0 종가 우선, 없으면 D+1(폭 넓힘).
        LocalDate d0 = window.get(0).minusDays(1);
        Bar anchor = null;
        BigDecimal lo, hi;
        Bar d0Bar = barsByDate.get(d0);
        // D0 는 달력상 직전 거래일이 아닐 수 있다(주말) — 가장 최근 과거 봉을 찾는다(최대 7일).
        for (int i = 0; i < 7 && d0Bar == null; i++) {
            d0Bar = barsByDate.get(window.get(0).minusDays(1 + i));
        }
        if (d0Bar != null && d0Bar.close() != null && d0Bar.close().signum() > 0) {
            anchor = d0Bar; lo = UNIT_RATIO_MIN; hi = UNIT_RATIO_MAX;
        } else {
            anchor = bars.get(0); lo = UNIT_RATIO_MIN_D1; hi = UNIT_RATIO_MAX_D1;
        }
        BigDecimal ratio = anchor.close().divide(priceAtSignal, 6, RoundingMode.HALF_UP);
        if (ratio.compareTo(lo) < 0 || ratio.compareTo(hi) > 0) {
            return Result.of(Status.UNIT_MISMATCH_SUSPECT, end,
                    "봉 종가/기록가 = " + ratio.setScale(3, RoundingMode.HALF_UP).toPlainString()
                            + " (" + anchor.date() + ") — 수정주가 소급 보정 의심");
        }

        Bar last = bars.get(bars.size() - 1);
        BigDecimal pct = pctOf(last.close(), priceAtSignal);
        BigDecimal maxHigh = null, minLow = null;
        for (Bar b : bars) {
            if (b.high() != null && (maxHigh == null || b.high().compareTo(maxHigh) > 0)) maxHigh = b.high();
            if (b.low() != null && (minLow == null || b.low().compareTo(minLow) < 0)) minLow = b.low();
        }
        BigDecimal mfe = maxHigh == null ? null : pctOf(maxHigh, priceAtSignal);
        BigDecimal mae = minLow == null ? null : pctOf(minLow, priceAtSignal);

        if (indexCloseD3 == null || indexCloseD3.signum() <= 0 || bmAtSignal == null || bmAtSignal.signum() <= 0) {
            // 지수 없으면 미평가 — 절대수익만 남기면 비교표에 "지수 없는 행"이 섞여 초과수익 해석이 깨진다.
            return Result.of(Status.NO_INDEX, end, bmAtSignal == null || bmAtSignal.signum() <= 0
                    ? "기록 시점 지수 없음 — 복원 불가" : "D+3 지수 종가 없음 — 재시도");
        }
        BigDecimal bmReturn = pctOf(indexCloseD3, bmAtSignal);
        BigDecimal alpha = pct.subtract(bmReturn);
        // hit 규칙은 기존 평가와 같은 단일 출처 — 정의를 두 벌 두지 않는다.
        boolean hit = SignalOutcomeService.isHit(alpha, pct);

        return new Result(Status.OK, end, last.close(), pct, indexCloseD3, bmReturn, alpha, mfe, mae, hit, null);
    }

    static BigDecimal pctOf(BigDecimal now, BigDecimal base) {
        return now.subtract(base).multiply(BigDecimal.valueOf(100)).divide(base, 4, RoundingMode.HALF_UP);
    }
}
