package com.myplatform.backend.service;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * KIS 호출 시도·실패 일별 집계 — 관제실 이상 점검({@code DataAnomalyRules})의 입력.
 *
 * <p><b>왜 필요한가(2026-09-02 실사고 둘)</b>: ① 당일분봉조회(FHKST03010200)에 필수 파라미터가 빠져
 * KIS 가 HTTP 200 + rt_cd≠0 으로 답했고 VWAP 는 WARN 몇 줄, '1일' 탭은 로그도 없이 죽어 있었다 — 언제부터인지
 * 아무도 모른다. ② 잔고 조회(TTTC8434R)가 같은 초 충돌로 하루 24건 실패했는데 ERROR 로그 속에 묻혀 있었다.
 * 둘 다 <b>예외가 안 나는 실패</b>라 rt_cd 를 세어야만 보인다. 이 클래스가 그 카운터다.
 *
 * <p><b>범위·한계(의도)</b>: 프로세스 메모리, 날짜별 키. 재시작하면 0 부터다 — 그래서 관제실은 "부팅 이후 오늘분"으로
 * 표기한다. 영속(DB/Redis)으로 올리지 않은 이유: 이 값은 판정이 아니라 "지금 이 프로세스가 KIS 와 잘 통하는가"의
 * 신호이고, 실패는 재시작 뒤에도 곧바로 다시 쌓인다. 정적 상태인 것도 의도 — {@code KoreaInvestmentService}
 * 생성자를 바꾸면 테스트 6개가 같이 바뀌어야 해서, 카운터는 주입 대상이 아니라 유틸로 둔다.
 *
 * <p>오늘·어제 두 날짜만 유지한다(그 밖은 기록 시점에 정리) — 무한 증식 방지.
 *
 * <p><b>단위는 호출자가 정한다</b>: 잔고는 {@code getBalance()} 가 호출 1건 = 시도 1, 실패 = 재시도 소진으로 센다
 * (재시도 안쪽에서 세면 복구된 블립이 실패로 남는다 — 2026-09-03, {@code KoreaInvestmentBalanceTallyTest}).
 * 분봉은 페이지네이션 호출마다 센다(각 호출이 독립 응답이라 그게 맞다).
 */
public final class KisCallTally {
    private KisCallTally() {}

    /** 당일분봉조회(FHKST03010200, inquire-time-itemchartprice) — VWAP·'1일' 탭. */
    public static final String MINUTE_CHART = "minute-chart";
    /** 잔고조회(TTTC8434R, inquire-balance) — 매매·알림 모니터. */
    public static final String BALANCE = "balance";

    /** 어느 날의 시도·실패 수. 실패 ⊆ 시도. */
    public record Counts(long attempts, long failures) {
        public static final Counts ZERO = new Counts(0, 0);
    }

    private record Key(String kind, LocalDate day) {}

    private static final class Cell {
        final LongAdder attempts = new LongAdder();
        final LongAdder failures = new LongAdder();
    }

    private static final Map<Key, Cell> CELLS = new ConcurrentHashMap<>();

    public static void attempt(String kind, LocalDate day) {
        if (kind == null || day == null) return;
        cell(kind, day).attempts.increment();
    }

    public static void failure(String kind, LocalDate day) {
        if (kind == null || day == null) return;
        cell(kind, day).failures.increment();
    }

    /** 그날의 집계. 기록이 없으면 {@link Counts#ZERO} — "호출이 없었다"는 정상이고 판정은 규칙이 시도 수로 보류한다. */
    public static Counts of(String kind, LocalDate day) {
        if (kind == null || day == null) return Counts.ZERO;
        Cell c = CELLS.get(new Key(kind, day));
        return c == null ? Counts.ZERO : new Counts(c.attempts.sum(), c.failures.sum());
    }

    /** 테스트 격리용. */
    public static void reset() {
        CELLS.clear();
    }

    private static Cell cell(String kind, LocalDate day) {
        Cell c = CELLS.computeIfAbsent(new Key(kind, day), k -> new Cell());
        // 오늘·어제 밖의 키 정리 — 호출 빈도가 낮아 매번 훑어도 비용이 없다(키는 kind 수 × 2 이내).
        LocalDate keepFrom = day.minusDays(1);
        CELLS.keySet().removeIf(k -> k.day().isBefore(keepFrom) || k.day().isAfter(day.plusDays(1)));
        return c;
    }
}
