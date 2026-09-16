package com.myplatform.backend.service;

import com.myplatform.backend.entity.SignalOutcome;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * V59 교정 평가 배치 — "기록시점 → D+3 KRX 종가"를 일봉으로 다시 잰다(2026-09-17).
 *
 * <p>판정은 {@link SignalD3Evaluator}(순수함수) 단일 출처. 여기는 ① 어떤 행이 대상인지 고르고
 * ② 봉을 채우고 ③ 결과를 새 컬럼에 쓰는 일만 한다. 구값(price_after_3d 등)은 건드리지 않는다.
 *
 * <p><b>봉을 먼저 받고 평가한다.</b> {@code stock_price_history} 의 과거 봉은 장중 수집분이 확정값으로
 * 덮이기 전일 수 있다(18:30 갱신은 하루 400종목 무작위). 확정 여부 플래그가 없으므로, 평가할 종목은
 * 그날 KIS 에서 60봉을 다시 받아({@code collectPriceHistory}, 업서트 규칙이 확정값만 덮는다) 그 뒤에
 * 잰다. 종목당 1콜이 그 종목의 모든 대기 창을 덮으므로 종목 단위로 묶고, 한 번에 받을 종목 수에
 * 상한을 둔다(넘친 종목은 다음 실행으로 — 시그널 기록 중에 동기 수집하지 않는다).
 *
 * <p><b>대조군 봉 편입.</b> 대조군 종목은 무작위라 400종목 갱신에 잘 안 들어가 봉이 없었다(실측 39%).
 * 이 배치가 대조군 행도 같은 규칙으로 봉을 받아오므로 별도 편입 장치가 필요 없다.
 *
 * <p><b>멱등.</b> OK 행은 대상에서 빠지고, 재시도 가능 상태(봉 없음·지수 없음)만 다시 본다. 결과가
 * 같으면 쓰지 않는다({@link #needsWrite}). {@code force} 는 관리자가 비교표를 다시 만들 때만.
 */
@Service
@Slf4j
public class SignalD3EvaluationService {

    /** 한 실행에서 봉을 받아올 종목 상한 — 450ms 간격이면 약 1분. 첫 백필(~150종목)은 이틀에 나뉜다. */
    static final int MAX_FETCH_STOCKS_PER_RUN = 120;
    /** 한 실행에서 읽어올 대기 행 상한. */
    static final int MAX_ROWS_PER_RUN = 2000;
    static final Set<String> RETRYABLE = Set.of(
            SignalD3Evaluator.Status.MISSING_BARS.name(), SignalD3Evaluator.Status.NO_INDEX.name());
    static final Set<String> ALL_STATUSES;
    static {
        Set<String> all = new java.util.HashSet<>();
        for (SignalD3Evaluator.Status s : SignalD3Evaluator.Status.values()) all.add(s.name());
        ALL_STATUSES = Collections.unmodifiableSet(all);
    }
    private static final String KOSPI_INDEX_CODE = "0001";
    private static final DateTimeFormatter KIS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SignalOutcomeRepository repository;
    private final StockPriceHistoryRepository historyRepository;
    private final MarketCalendarService calendar;
    private final ObjectProvider<StockAnalysisService> analysisProvider;
    private final ObjectProvider<KoreaInvestmentService> kisProvider;
    private final ObjectProvider<StockStatusService> statusProvider;
    private final Clock clock;

    public SignalD3EvaluationService(SignalOutcomeRepository repository,
                                     StockPriceHistoryRepository historyRepository,
                                     MarketCalendarService calendar,
                                     ObjectProvider<StockAnalysisService> analysisProvider,
                                     ObjectProvider<KoreaInvestmentService> kisProvider,
                                     ObjectProvider<StockStatusService> statusProvider,
                                     Clock clock) {
        this.repository = repository;
        this.historyRepository = historyRepository;
        this.calendar = calendar;
        this.analysisProvider = analysisProvider;
        this.kisProvider = kisProvider;
        this.statusProvider = statusProvider;
        this.clock = clock;
    }

    /** 대기 행 + 달력이 정한 창. */
    record Due(SignalOutcome row, List<LocalDate> window) {}

    /**
     * 실행 보고 — 사전 점검(dryRun)이면 정확한 거래일 기준 봉 완전성까지만, 실행이면 평가 결과까지.
     *
     * @param coverageByType  타입별 {allBars, missingBars} — <b>달력이 정한 D+1·D+2·D+3 봉의 존재 여부</b>
     *                        (달력일 근사 점검이 아니다). 수집 전 DB 기준.
     * @param evaluatedByStatus 이번 실행에서 쓴 행의 상태별 건수
     * @param skippedByCap    종목 상한에 걸려 다음 실행으로 넘긴 행 수(조용히 빠지지 않는다 §4c)
     */
    public record Report(boolean dryRun, LocalDate today, int candidateRows, int notDue, int dueRows,
                         int distinctStocks, Map<String, Map<String, Integer>> coverageByType,
                         int fetched, int fetchFailed, int skippedByCap, int unchanged,
                         Map<String, Integer> evaluatedByStatus, List<String> notes) {}

    /** 19:45 — 18:30 일봉 갱신·19:30 기존 평가 뒤. 첫 실행이 백필이고 이후는 그날 도래분만 돈다. */
    @Scheduled(scheduler = "batchScheduler", cron = "0 45 19 * * MON-FRI", zone = "Asia/Seoul")
    public void nightly() {
        try {
            Report r = run(false, MAX_FETCH_STOCKS_PER_RUN, false);
            log.info("[D3평가] {}", summarize(r));
        } catch (Exception e) {
            log.error("[D3평가] 배치 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * @param dryRun         true 면 봉 완전성 점검만(KIS 호출·저장 없음)
     * @param maxFetchStocks 이번 실행에서 봉을 받아올 종목 상한
     * @param force          OK 행도 다시 잰다(비교표 재작성용). 결과가 같으면 쓰지 않는다.
     */
    public Report run(boolean dryRun, int maxFetchStocks, boolean force) {
        LocalDate today = LocalDate.now(clock);
        List<String> notes = new ArrayList<>();
        List<SignalOutcome> candidates = repository.findD3Pending(
                SignalOutcomeService.PHASE38_CUTOFF, force ? ALL_STATUSES : RETRYABLE,
                PageRequest.of(0, MAX_ROWS_PER_RUN));

        // ① 창 계산 + 도래 필터 — 달력이 먼저 날짜를 정한다.
        List<Due> due = new ArrayList<>();
        int notDue = 0;
        for (SignalOutcome row : candidates) {
            List<LocalDate> window = windowFor(row.getSignalDate());
            if (window.get(2).isAfter(today)) { notDue++; continue; }
            due.add(new Due(row, window));
        }
        Map<String, List<Due>> byStock = groupByStock(due);

        // ② 정확한 거래일 봉 완전성(수집 전 DB 기준) — 코덱스 조건 ①: 달력일 근사가 아니라 D+1·D+2·D+3.
        Map<String, Map<String, Integer>> coverage = new TreeMap<>();
        for (Map.Entry<String, List<Due>> e : byStock.entrySet()) {
            Map<LocalDate, SignalD3Evaluator.Bar> bars = loadBars(e.getKey(), e.getValue());
            for (Due d : e.getValue()) {
                boolean all = d.window().stream().allMatch(bars::containsKey);
                coverage.computeIfAbsent(d.row().getSignalType(), k -> new TreeMap<>())
                        .merge(all ? "allBars" : "missingBars", 1, Integer::sum);
            }
        }
        if (dryRun) {
            return new Report(true, today, candidates.size(), notDue, due.size(), byStock.size(),
                    coverage, 0, 0, 0, 0, Map.of(), notes);
        }

        // ③ 지수 D+3 종가 — 실행당 1콜. 실패하면 이번 실행 행은 NO_INDEX(재시도 가능)로 남는다.
        LocalDate earliest = due.stream().map(d -> d.window().get(0)).min(LocalDate::compareTo).orElse(today);
        Map<LocalDate, BigDecimal> indexClose = loadIndexCloses(earliest, today, notes);

        // ④ 종목별: 봉 수집(상한) → 재조회 → 평가 → 변한 것만 저장.
        Map<String, Integer> byStatus = new TreeMap<>();
        int fetched = 0, fetchFailed = 0, skippedByCap = 0, unchanged = 0;
        StockAnalysisService analysis = analysisProvider.getIfAvailable();
        Map<String, String> corporateActions = corporateActions();
        LocalDateTime now = LocalDateTime.now(clock);

        for (Map.Entry<String, List<Due>> e : byStock.entrySet()) {
            String code = e.getKey();
            if (fetched >= maxFetchStocks) { skippedByCap += e.getValue().size(); continue; }
            if (analysis == null) {
                notes.add("StockAnalysisService 미가용 — 봉 수집 불가, 이번 실행은 평가하지 않는다");
                skippedByCap += e.getValue().size();
                continue;
            }
            try {
                analysis.collectPriceHistory(code);   // 60봉 재수집 — DailyBarUpsertRule 이 확정값만 덮는다
                fetched++;
            } catch (Exception ex) {
                // 받지 못한 종목은 이번 실행에서 평가하지 않는다 — 미확정 봉으로 재는 것보다 낫다.
                fetchFailed++;
                log.debug("[D3평가] {} 봉 수집 실패(스킵): {}", code, ex.getMessage());
                continue;
            }
            sleepQuietly(DailyBarRefreshService.RATE_LIMIT_MS);

            Map<LocalDate, SignalD3Evaluator.Bar> bars = loadBars(code, e.getValue());
            String caLabel = corporateActions.get(code);
            for (Due d : e.getValue()) {
                SignalOutcome row = d.row();
                SignalD3Evaluator.Result r = SignalD3Evaluator.evaluate(
                        today, d.window(), row.getPriceAtSignal(), row.getBmPriceAtSignal(),
                        bars, indexClose.get(d.window().get(2)), caLabel);
                if (r.status() == SignalD3Evaluator.Status.NOT_DUE) continue;
                if (!needsWrite(row, r)) { unchanged++; continue; }
                apply(row, r, now);
                repository.save(row);
                byStatus.merge(r.status().name(), 1, Integer::sum);
            }
        }
        Report report = new Report(false, today, candidates.size(), notDue, due.size(), byStock.size(),
                coverage, fetched, fetchFailed, skippedByCap, unchanged, byStatus, notes);
        if (skippedByCap > 0) {
            log.warn("[D3평가] 종목 상한 {}에 걸려 {}행은 다음 실행으로 — 조용히 빠지지 않는다",
                    maxFetchStocks, skippedByCap);
        }
        return report;
    }

    /** 달력이 정한 [D+1, D+2, D+3]. */
    List<LocalDate> windowFor(LocalDate signalDate) {
        List<LocalDate> w = new ArrayList<>(SignalD3Evaluator.WINDOW_TRADING_DAYS);
        for (int i = 1; i <= SignalD3Evaluator.WINDOW_TRADING_DAYS; i++) {
            w.add(calendar.plusTradingDays(signalDate, i));
        }
        return w;
    }

    /** 종목별 묶기 — 가장 오래된 시그널이 앞에 오도록(백로그 우선). 순수. */
    static Map<String, List<Due>> groupByStock(List<Due> due) {
        Map<String, List<Due>> by = new LinkedHashMap<>();
        due.stream()
           .sorted((a, b) -> a.row().getSignalDate().compareTo(b.row().getSignalDate()))
           .forEach(d -> by.computeIfAbsent(d.row().getStockCode(), k -> new ArrayList<>()).add(d));
        return by;
    }

    /** 종목의 대기 창을 전부 덮는 범위로 봉을 읽는다(D0 판정용으로 앞쪽 10일 여유). */
    private Map<LocalDate, SignalD3Evaluator.Bar> loadBars(String code, List<Due> dues) {
        LocalDate from = dues.stream().map(d -> d.window().get(0)).min(LocalDate::compareTo).orElseThrow().minusDays(10);
        LocalDate to = dues.stream().map(d -> d.window().get(2)).max(LocalDate::compareTo).orElseThrow();
        Map<LocalDate, SignalD3Evaluator.Bar> out = new HashMap<>();
        for (StockPriceHistory h : historyRepository.findByStockCodeAndDateRange(code, from, to)) {
            if (h.getTradeDate() == null) continue;
            out.put(h.getTradeDate(), new SignalD3Evaluator.Bar(
                    h.getTradeDate(), h.getHighPrice(), h.getLowPrice(), h.getClosePrice(), h.getVolume()));
        }
        return out;
    }

    /** KOSPI 일봉 종가 — 앵커=오늘, 필요한 창 시작일까지. 실패는 빈 맵 + 사유(행은 NO_INDEX 로 재시도). */
    private Map<LocalDate, BigDecimal> loadIndexCloses(LocalDate from, LocalDate today, List<String> notes) {
        KoreaInvestmentService kis = kisProvider.getIfAvailable();
        if (kis == null || !kis.isConfigured()) {
            notes.add("KIS 미구성 — 지수 종가 없음(행은 NO_INDEX 로 재시도)");
            return Map.of();
        }
        try {
            int span = (int) java.time.temporal.ChronoUnit.DAYS.between(from, today) + 5;
            List<KoreaInvestmentService.IndexOhlcvData> rows = kis.getIndexDailyOhlcv(KOSPI_INDEX_CODE, span, today);
            Map<LocalDate, BigDecimal> out = new HashMap<>();
            for (KoreaInvestmentService.IndexOhlcvData r : rows) {
                if (r == null || r.date() == null || r.close() == null || r.close().signum() <= 0) continue;
                out.put(LocalDate.parse(r.date().replace("-", ""), KIS_DATE), r.close());
            }
            if (out.isEmpty()) notes.add("지수 일봉 응답 비어 있음(행은 NO_INDEX 로 재시도)");
            return out;
        } catch (Exception e) {
            notes.add("지수 일봉 조회 실패: " + e.getMessage());
            return Map.of();
        }
    }

    private Map<String, String> corporateActions() {
        StockStatusService s = statusProvider.getIfAvailable();
        return s == null ? Map.of() : s.getSuspectedCorporateActions();
    }

    /**
     * 결과가 이미 저장된 것과 같으면 쓰지 않는다 — 백필 재실행이 같은 값을 다시 덮지 않게(멱등). 순수.
     * 상태·종료일·종가·수익률·hit 가 전부 같아야 "같다". 사유 문구만 다른 경우는 같다고 본다.
     */
    static boolean needsWrite(SignalOutcome row, SignalD3Evaluator.Result r) {
        if (row.getD3Status() == null) return true;
        if (!row.getD3Status().equals(r.status().name())) return true;
        if (!java.util.Objects.equals(row.getD3EndDate(), r.endDate())) return true;
        if (neq(row.getD3Close(), r.close())) return true;
        if (neq(row.getD3PctChange(), r.pctChange())) return true;
        if (neq(row.getD3Alpha(), r.alpha())) return true;
        return !java.util.Objects.equals(row.getD3Hit(), r.hit());
    }

    private static boolean neq(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return (a == null) != (b == null);
        return a.compareTo(b) != 0;
    }

    static void apply(SignalOutcome row, SignalD3Evaluator.Result r, LocalDateTime now) {
        row.setD3EndDate(r.endDate());
        row.setD3Close(r.close());
        row.setD3PctChange(r.pctChange());
        row.setD3BmClose(r.bmClose());
        row.setD3BmReturn(r.bmReturn());
        row.setD3Alpha(r.alpha());
        row.setD3MfePct(r.mfePct());
        row.setD3MaePct(r.maePct());
        row.setD3Hit(r.hit());
        row.setD3Status(r.status().name());
        String note = r.note();
        row.setD3Note(note == null ? null : (note.length() > 255 ? note.substring(0, 255) : note));
        row.setD3EvaluatedAt(now);
    }

    static String summarize(Report r) {
        return String.format("%s 대상 %d행(미도래 %d, 도래 %d, 종목 %d) 수집 %d/실패 %d/상한이월 %d · 동일 %d · 결과 %s · 완전성 %s%s",
                r.dryRun() ? "사전점검" : "실행", r.candidateRows(), r.notDue(), r.dueRows(), r.distinctStocks(),
                r.fetched(), r.fetchFailed(), r.skippedByCap(), r.unchanged(), r.evaluatedByStatus(), r.coverageByType(),
                r.notes().isEmpty() ? "" : " · 비고 " + r.notes());
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    // ==================== 구값 · 교정값 비교표 ====================

    /**
     * 타입별 비교 — 게이트를 교정값으로 넘길지 판단하는 근거. 순수 집계(테스트 대상).
     *
     * @param bothEvaluated 구값·교정값 둘 다 있는 행(비교 가능한 표본)
     * @param hitAgree/hitOldOnly/hitNewOnly hit 판정 일치·불일치 분해
     * @param avgAbsPctDiff |구 pct − 교정 pct| 평균 — 정의 차이가 실제로 얼마나 컸는지
     */
    public record TypeStats(int rows, int oldEvaluated, Map<String, Integer> d3ByStatus, int d3NotAttempted,
                            int bothEvaluated, int hitAgree, int hitOldOnly, int hitNewOnly,
                            BigDecimal oldAvgPct, BigDecimal newAvgPct, BigDecimal avgAbsPctDiff) {}

    public record CompareReport(LocalDate from, int totalRows, Map<String, TypeStats> byType, String caveat) {}

    public CompareReport compare() {
        return compare(repository.findAllSince(SignalOutcomeService.PHASE38_CUTOFF), SignalOutcomeService.PHASE38_CUTOFF);
    }

    static CompareReport compare(List<SignalOutcome> rows, LocalDate from) {
        Map<String, List<SignalOutcome>> byType = new TreeMap<>();
        for (SignalOutcome s : rows) byType.computeIfAbsent(s.getSignalType(), k -> new ArrayList<>()).add(s);
        Map<String, TypeStats> out = new TreeMap<>();
        for (Map.Entry<String, List<SignalOutcome>> e : byType.entrySet()) {
            int oldEval = 0, notAttempted = 0, both = 0, agree = 0, oldOnly = 0, newOnly = 0;
            Map<String, Integer> status = new TreeMap<>();
            BigDecimal oldSum = BigDecimal.ZERO, newSum = BigDecimal.ZERO, diffSum = BigDecimal.ZERO;
            for (SignalOutcome s : e.getValue()) {
                boolean hasOld = s.getEvaluatedAt() != null && s.getPctChange3d() != null;
                if (hasOld) oldEval++;
                if (s.getD3Status() == null) notAttempted++; else status.merge(s.getD3Status(), 1, Integer::sum);
                boolean hasNew = s.getD3PctChange() != null;
                if (hasOld && hasNew) {
                    both++;
                    oldSum = oldSum.add(s.getPctChange3d());
                    newSum = newSum.add(s.getD3PctChange());
                    diffSum = diffSum.add(s.getPctChange3d().subtract(s.getD3PctChange()).abs());
                    boolean o = Boolean.TRUE.equals(s.getHit()), n = Boolean.TRUE.equals(s.getD3Hit());
                    if (o == n) agree++; else if (o) oldOnly++; else newOnly++;
                }
            }
            out.put(e.getKey(), new TypeStats(e.getValue().size(), oldEval, status, notAttempted,
                    both, agree, oldOnly, newOnly,
                    avg(oldSum, both), avg(newSum, both), avg(diffSum, both)));
        }
        return new CompareReport(from, rows.size(), out,
                "구값은 배치 실행 시점 현재가 기준(정의 흔들림), 교정값은 D+3 KRX 종가 기준. "
                + "둘 다 있는 행만 비교했다. 게이트 전환은 이 표를 본 뒤 별도로 결정한다.");
    }

    private static BigDecimal avg(BigDecimal sum, int n) {
        return n == 0 ? null : sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
    }
}
