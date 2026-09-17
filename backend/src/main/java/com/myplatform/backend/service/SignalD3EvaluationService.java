package com.myplatform.backend.service;

import com.myplatform.backend.entity.SignalOutcome;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
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
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * V59 교정 평가 배치 — "기록시점 → D+3 KRX 종가"를 일봉으로 다시 잰다(2026-09-17).
 *
 * <p>판정은 {@link SignalD3Evaluator}(순수함수) 단일 출처. 여기는 ① 어떤 행이 대상인지 고르고
 * ② 봉을 받고 ③ 결과를 새 컬럼에 쓰는 일만 한다. 구값(price_after_3d 등)은 건드리지 않는다.
 *
 * <p><b>응답에 있는 봉으로만 잰다.</b> {@code stock_price_history} 의 과거 봉은 장중 수집분이 확정값으로
 * 덮이기 전일 수 있다(확정 플래그 없음). 그래서 평가할 종목은 창을 덮는 날짜 범위를 KIS 에서 받고
 * ({@code collectPriceHistoryRange}), <b>그 응답에서 받은 봉만으로</b> 창을 채운다 — 받은 개수가 아니라
 * <i>필요한 날짜가 응답에 있는가</i>로 판단하고, 일부만 온 응답을 DB 의 옛 봉과 섞어 성공으로 만들지
 * 않는다(코덱스 리뷰 2차 ②). 저장 실패는 예외로 올라와 그 종목은 평가하지 않는다.
 *
 * <p><b>수집 실패도 기록한다(2차 ①).</b> 빈 응답·예외·저장 실패·동시 수집 중이면 그 종목의 대기 행에
 * {@code FETCH_FAILED} + 사유 + 시각을 쓴다. 조용히 넘기면 "왜 아직 미평가인가"를 아무도 모른다(§4c).
 *
 * <p><b>재시도 가능 여부와 순서는 DB 가 정한다(2차 ①).</b> {@code findD3Pending} 이 조건·정렬을 적용한
 * 뒤에 상한 {@link #MAX_ROWS_PER_RUN} 을 건다 — 자바에서 먼저 자르면 앞쪽 영구 결측 종목이 상한을 다
 * 먹어 뒤쪽 신규 종목이 계속 밀린다. 수집 실패는 일시 장애일 수 있어 다음 날 다시({@link
 * #FETCH_RETRY_INTERVAL_DAYS}), 봉/지수 없음은 {@link #RETRY_INTERVAL_DAYS} 뒤에 다시.
 *
 * <p><b>마감된 거래일까지만 확정한다(F2, 2026-09-17 감사).</b> {@link #lastSettledTradingDay} 가 시계와
 * 거래일 달력으로 "지금까지 마감이 확정된 마지막 거래일"을 정하고, 크론·기동 따라잡기·수동·force·dryRun 이
 * <b>모두 같은 경계</b>를 쓴다. D+3 이 아직 마감 전이면 <b>아무 상태도 쓰지 않고</b> 미도래로 둔다 —
 * 장중 잠정 봉으로 OK 를 굳히지도, 장전에 MISSING_BARS 를 박아 재시도를 7일 밀지도 않는다.
 * 반대로 D+3 이 과거 확정일이면 <b>장중에도 평가한다</b>(장중이라는 이유로 전체 백필을 막지 않는다).
 *
 * <p><b>멱등.</b> OK 행은 대상에서 빠지고, 결과의 <b>모든 저장 필드</b>가 같으면 쓰지 않는다
 * ({@link #needsWrite}). {@code force} 는 관리자가 비교표를 다시 만들 때만 — 그때 수집이 실패해도 OK 행은
 * 덮지 않는다.
 */
@Service
@Slf4j
public class SignalD3EvaluationService {

    /** 한 실행에서 봉을 받아올 종목 상한 — 450ms 간격이면 약 1분. */
    static final int MAX_FETCH_STOCKS_PER_RUN = 120;
    /** 한 실행에서 읽어올 대기 행 상한 — DB 가 조건·정렬을 적용한 뒤의 상한이다. */
    static final int MAX_ROWS_PER_RUN = 2000;
    /** 봉/지수 없음 재시도 간격(일). */
    static final int RETRY_INTERVAL_DAYS = 7;
    /** 수집 실패 재시도 간격(일) — 일시 장애가 7일 묶이지 않게 짧다. */
    static final int FETCH_RETRY_INTERVAL_DAYS = 1;
    /** 창 앞쪽 여유(달력일) — D0 봉(단위 판정)과 주말·연휴를 덮는다. */
    static final int FETCH_LEAD_DAYS = 10;
    /** D+3 이 오늘 이전이려면 시그널일은 최소 3달력일 전이다 — 미도래 행이 상한을 먹지 않게 하는 상한. */
    static final int MIN_CALENDAR_DAYS_TO_DUE = 3;
    /** 일봉 확정 여유(분) — KRX 종가 단일가 종료(15:40) 후 KIS 일봉에 반영되기까지. */
    static final int BAR_SETTLE_MARGIN_MINUTES = 30;
    /** 기동 따라잡기 지연(ms) — 토큰·마스터 동기화가 끝난 뒤. */
    static final long STARTUP_DELAY_MS = 120_000L;
    static final Set<String> RETRYABLE = Set.of(
            SignalD3Evaluator.Status.MISSING_BARS.name(), SignalD3Evaluator.Status.NO_INDEX.name());
    static final String FETCH_FAILED = SignalD3Evaluator.Status.FETCH_FAILED.name();
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

    /** 기동 시 따라잡기 — 배포·재시작으로 19:45 를 놓친 날을 메운다. 장중이면 미룬다. */
    @Value("${signal.d3.catch-up-on-startup:true}")
    private boolean catchUpOnStartup;

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
     * 실행 보고.
     *
     * @param coverageByType    타입별 {allBars, missingBars} — 달력이 정한 D+1·D+2·D+3 봉의 DB 존재 여부(수집 전 사전 점검)
     * @param fetched           봉을 받은 종목 수 / fetchFailed 못 받은 종목 수 / fetchFailedRows 그로 인해 FETCH_FAILED 로 기록된 행 수
     * @param skippedByCap      종목 상한에 걸려 다음 실행으로 넘긴 행 수(조용히 빠지지 않는다 §4c)
     * @param evaluatedByStatus 이번 실행에서 쓴 행의 상태별 건수
     */
    public record Report(boolean dryRun, LocalDate today, LocalDate settledTradingDay,
                         int candidateRows, int notDue, int dueRows,
                         int distinctStocks, Map<String, Map<String, Integer>> coverageByType,
                         int fetched, int fetchFailed, int fetchFailedRows, int skippedByCap, int unchanged,
                         Map<String, Integer> evaluatedByStatus, List<String> notes) {}

    /** 19:45 — 18:30 일봉 갱신·19:30 기존 평가 뒤. */
    @Scheduled(scheduler = "batchScheduler", cron = "0 45 19 * * MON-FRI", zone = "Asia/Seoul")
    public void nightly() {
        runAndLog("야간");
    }

    /**
     * 기동 따라잡기 — 배포로 19:45 를 지나 재시작한 날, 다음 날 저녁까지 기다리지 않는다.
     *
     * <p>⚠ <b>장중이라는 이유로 막지 않는다</b>(F2): 확정 경계({@link #lastSettledTradingDay})가 미도래 행을
     * 걸러내므로 장중 실행도 안전하고, 오히려 막으면 <b>과거 확정일 행의 백필이 저녁까지 밀린다</b>.
     * 대기 행이 없으면 KIS 를 부르지 않는다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void catchUpOnStartup() {
        if (!catchUpOnStartup) return;
        sleepQuietly(STARTUP_DELAY_MS);
        runAndLog("기동 따라잡기");
    }

    private void runAndLog(String label) {
        try {
            Report r = run(false, MAX_FETCH_STOCKS_PER_RUN, false);
            log.info("[D3평가] {} {}", label, summarize(r));
        } catch (Exception e) {
            log.error("[D3평가] {} 배치 실패: {}", label, e.getMessage(), e);
        }
    }

    /**
     * @param dryRun         true 면 봉 완전성 점검만(KIS 호출·저장 없음)
     * @param maxFetchStocks 이번 실행에서 봉을 받아올 종목 상한
     * @param force          OK 행도 다시 잰다(비교표 재작성용). 결과가 같으면 쓰지 않는다.
     */
    public Report run(boolean dryRun, int maxFetchStocks, boolean force) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        // 확정 경계 — 크론·기동·수동·force·dryRun 이 모두 이 한 값을 쓴다(F2).
        LocalDate settled = lastSettledTradingDay(now, calendar);
        List<String> notes = new ArrayList<>();
        // 시그널일 상한도 확정일 기준 — 미도래 행이 2,000행 상한을 먹지 않게.
        LocalDate latestDue = settled.minusDays(MIN_CALENDAR_DAYS_TO_DUE);
        List<SignalOutcome> candidates = force
                ? repository.findD3Pending(SignalOutcomeService.PHASE38_CUTOFF, latestDue,
                        ALL_STATUSES, now.plusYears(100), FETCH_FAILED, now.plusYears(100),
                        PageRequest.of(0, MAX_ROWS_PER_RUN))
                : repository.findD3Pending(SignalOutcomeService.PHASE38_CUTOFF, latestDue,
                        RETRYABLE, now.minusDays(RETRY_INTERVAL_DAYS), FETCH_FAILED, now.minusDays(FETCH_RETRY_INTERVAL_DAYS),
                        PageRequest.of(0, MAX_ROWS_PER_RUN));

        // ① 창 계산 + 도래 필터 — D+3 이 확정일 뒤면 저장하지 않고 미도래로 둔다(실패 이력도 쓰지 않는다).
        List<Due> due = new ArrayList<>();
        int notDue = 0;
        for (SignalOutcome row : candidates) {
            List<LocalDate> window = windowFor(row.getSignalDate());
            if (window.get(2).isAfter(settled)) { notDue++; continue; }
            due.add(new Due(row, window));
        }
        Map<String, List<Due>> byStock = groupByStock(due);

        // ② 사전 점검 — 달력이 정한 D+1·D+2·D+3 봉의 DB 존재 여부(달력일 근사가 아니다).
        Map<String, Map<String, Integer>> coverage = new TreeMap<>();
        for (Map.Entry<String, List<Due>> e : byStock.entrySet()) {
            Map<LocalDate, SignalD3Evaluator.Bar> dbBars = loadBarsFromDb(e.getKey(), e.getValue());
            for (Due d : e.getValue()) {
                boolean all = d.window().stream().allMatch(dbBars::containsKey);
                coverage.computeIfAbsent(d.row().getSignalType(), k -> new TreeMap<>())
                        .merge(all ? "allBars" : "missingBars", 1, Integer::sum);
            }
        }
        if (dryRun) {
            return new Report(true, today, settled, candidates.size(), notDue, due.size(), byStock.size(),
                    coverage, 0, 0, 0, 0, 0, Map.of(), notes);
        }

        // ③ 지수 D+3 종가 — 실행당 1콜. 실패하면 이번 실행 행은 NO_INDEX(재시도 가능)로 남는다.
        LocalDate earliest = due.stream().map(d -> d.window().get(0)).min(LocalDate::compareTo).orElse(today);
        Map<LocalDate, BigDecimal> indexClose = loadIndexCloses(earliest, today, notes);

        // ④ 종목별: 봉 수집(상한) → 응답 봉으로만 평가 → 변한 것만 저장. 못 받으면 FETCH_FAILED 기록.
        Map<String, Integer> byStatus = new TreeMap<>();
        int fetched = 0, fetchFailed = 0, fetchFailedRows = 0, skippedByCap = 0, unchanged = 0;
        StockAnalysisService analysis = analysisProvider.getIfAvailable();
        Map<String, String> corporateActions = corporateActions();

        for (Map.Entry<String, List<Due>> e : byStock.entrySet()) {
            String code = e.getKey();
            List<Due> dues = e.getValue();
            if (fetched + fetchFailed >= maxFetchStocks) { skippedByCap += dues.size(); continue; }
            if (analysis == null) {
                notes.add("StockAnalysisService 미가용 — 봉 수집 불가, 이번 실행은 평가하지 않는다");
                skippedByCap += dues.size();
                continue;
            }
            LocalDate from = earliestWindowStart(dues).minusDays(FETCH_LEAD_DAYS);
            LocalDate to = latestWindowEnd(dues);
            String failReason = null;
            StockAnalysisService.CollectResult res = null;
            try {
                res = analysis.collectPriceHistoryRange(code, from, to);
                if (res.alreadyCollecting()) failReason = "수집 중(동시 실행)";
                else if (res.bars().isEmpty()) failReason = "빈 응답";
            } catch (Exception ex) {
                // KIS 예외든 저장 실패든 — 이 종목은 이번 실행에서 평가하지 않는다.
                failReason = "수집/저장 예외: " + ex.getClass().getSimpleName()
                        + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
            }
            sleepQuietly(DailyBarRefreshService.RATE_LIMIT_MS);
            if (failReason != null) {
                fetchFailed++;
                fetchFailedRows += markFetchFailed(dues, failReason, now);
                continue;
            }
            fetched++;

            Map<LocalDate, SignalD3Evaluator.Bar> bars = fromResponse(res.bars());   // 응답 봉만 — DB 와 섞지 않는다
            String caLabel = corporateActions.get(code);
            for (Due d : dues) {
                SignalOutcome row = d.row();
                SignalD3Evaluator.Result r = SignalD3Evaluator.evaluate(
                        settled, d.window(), row.getPriceAtSignal(), row.getBmPriceAtSignal(),
                        bars, indexClose.get(d.window().get(2)), caLabel);
                if (r.status() == SignalD3Evaluator.Status.NOT_DUE) continue;
                if (!needsWrite(row, r)) {
                    // 값은 같아도 "시도했다"는 남긴다 — 재시도 간격이 여기에 걸린다.
                    if (isRetryStatus(r.status())) { row.setD3EvaluatedAt(now); repository.save(row); }
                    unchanged++;
                    continue;
                }
                apply(row, r, now);
                repository.save(row);
                byStatus.merge(r.status().name(), 1, Integer::sum);
            }
        }
        Report report = new Report(false, today, settled, candidates.size(), notDue, due.size(), byStock.size(),
                coverage, fetched, fetchFailed, fetchFailedRows, skippedByCap, unchanged, byStatus, notes);
        if (skippedByCap > 0) {
            log.warn("[D3평가] 종목 상한 {}에 걸려 {}행은 다음 실행으로 — 조용히 빠지지 않는다",
                    maxFetchStocks, skippedByCap);
        }
        return report;
    }

    /**
     * 수집 실패를 행에 기록한다 — 사유·시각. 이미 OK 인 행(force 재평가 중)은 덮지 않는다. 반환 = 기록한 행 수.
     */
    int markFetchFailed(List<Due> dues, String reason, LocalDateTime now) {
        int n = 0;
        for (Due d : dues) {
            SignalOutcome row = d.row();
            if (SignalD3Evaluator.Status.OK.name().equals(row.getD3Status())) continue;
            apply(row, SignalD3Evaluator.Result.of(SignalD3Evaluator.Status.FETCH_FAILED, d.window().get(2), "봉 수집 실패: " + reason), now);
            repository.save(row);
            n++;
        }
        return n;
    }

    static boolean isRetryStatus(SignalD3Evaluator.Status s) {
        return RETRYABLE.contains(s.name()) || s == SignalD3Evaluator.Status.FETCH_FAILED;
    }

    /**
     * 지금까지 <b>마감이 확정된 마지막 거래일</b> — 시계 판정은 서비스 계층에 두고 평가 순수함수는
     * 날짜만 받는다(결정성 보존). 순수 함수(테스트 대상).
     *
     * <p><b>왜 필요한가(F2, 2026-09-17 감사)</b>: 날짜만 비교하면 {@code D+3 == 오늘}인 행이 시각과
     * 무관하게 평가된다. 장중이면 KIS 응답의 <b>미확정 장중 봉</b>으로 {@code OK} 가 저장되고 OK 는
     * 재평가 대상이 아니라 잠정 종가가 영구 고정된다. 장전이면 오늘 봉이 없어 {@code MISSING_BARS} 가
     * 기록되고 재시도 간격 7일에 걸려 정상 장마감 평가가 일주일 밀린다.
     *
     * <p><b>경계</b> = KRX 정규장 종료({@link MarketCalendarService#MARKET_CLOSE} 15:40) +
     * {@link #BAR_SETTLE_MARGIN_MINUTES}. 종가 단일가가 15:40 에 끝나도 KIS 일봉에 반영되기까지 여유가
     * 필요하다 — 여유 없이 15:40 을 쓰면 봉이 아직 없는 순간에 {@code MISSING_BARS} 가 박힌다.
     * ⚠ NXT 표시 시간(20:00)과 합치지 말 것 — 일봉 확정은 KRX 종가 기준이다(§2 시간대 경계 분리).
     */
    static LocalDate lastSettledTradingDay(LocalDateTime now, MarketCalendarService calendar) {
        // 마지막 마감 거래일 판정은 달력 한 곳에 있다 — 여기서는 일봉 확정 여유만 얹는다.
        return calendar.lastClosedTradingDay(now.minusMinutes(BAR_SETTLE_MARGIN_MINUTES));
    }

    /** 달력이 정한 [D+1, D+2, D+3]. */
    List<LocalDate> windowFor(LocalDate signalDate) {
        List<LocalDate> w = new ArrayList<>(SignalD3Evaluator.WINDOW_TRADING_DAYS);
        for (int i = 1; i <= SignalD3Evaluator.WINDOW_TRADING_DAYS; i++) {
            w.add(calendar.plusTradingDays(signalDate, i));
        }
        return w;
    }

    /** 종목별 묶기 — 입력(DB) 순서를 보존한다. 순수. */
    static Map<String, List<Due>> groupByStock(List<Due> due) {
        Map<String, List<Due>> by = new LinkedHashMap<>();
        for (Due d : due) by.computeIfAbsent(d.row().getStockCode(), k -> new ArrayList<>()).add(d);
        return by;
    }

    /** 응답 봉 → 날짜 맵. 순수. 날짜 없는 봉은 버린다(어느 날인지 모르는 봉으로 창을 채우지 않는다). */
    static Map<LocalDate, SignalD3Evaluator.Bar> fromResponse(List<KoreaInvestmentService.OhlcvData> bars) {
        Map<LocalDate, SignalD3Evaluator.Bar> out = new HashMap<>();
        if (bars == null) return out;
        for (KoreaInvestmentService.OhlcvData b : bars) {
            if (b == null || b.getTradeDate() == null) continue;
            out.put(b.getTradeDate(), new SignalD3Evaluator.Bar(
                    b.getTradeDate(), b.getHigh(), b.getLow(), b.getClose(), b.getVolume()));
        }
        return out;
    }

    private static LocalDate earliestWindowStart(List<Due> dues) {
        return dues.stream().map(d -> d.window().get(0)).min(LocalDate::compareTo).orElseThrow();
    }

    private static LocalDate latestWindowEnd(List<Due> dues) {
        return dues.stream().map(d -> d.window().get(2)).max(LocalDate::compareTo).orElseThrow();
    }

    /** 사전 점검(dryRun 완전성)용 DB 봉 — 평가에는 쓰지 않는다. */
    private Map<LocalDate, SignalD3Evaluator.Bar> loadBarsFromDb(String code, List<Due> dues) {
        LocalDate from = earliestWindowStart(dues).minusDays(FETCH_LEAD_DAYS);
        LocalDate to = latestWindowEnd(dues);
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
     * <b>저장하는 결과 필드 전부</b>를 비교한다. 사유 문구와 평가 시각은 비교하지 않는다.
     */
    static boolean needsWrite(SignalOutcome row, SignalD3Evaluator.Result r) {
        if (row.getD3Status() == null) return true;
        if (!row.getD3Status().equals(r.status().name())) return true;
        if (!Objects.equals(row.getD3EndDate(), r.endDate())) return true;
        if (neq(row.getD3Close(), r.close())) return true;
        if (neq(row.getD3PctChange(), r.pctChange())) return true;
        if (neq(row.getD3BmClose(), r.bmClose())) return true;
        if (neq(row.getD3BmReturn(), r.bmReturn())) return true;
        if (neq(row.getD3Alpha(), r.alpha())) return true;
        if (neq(row.getD3MfePct(), r.mfePct())) return true;
        if (neq(row.getD3MaePct(), r.maePct())) return true;
        return !Objects.equals(row.getD3Hit(), r.hit());
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
        return String.format("%s 확정일 %s · 대상 %d행(미도래 %d, 도래 %d, 종목 %d) 수집 %d/실패 %d(행 %d)/상한이월 %d · 동일 %d · 결과 %s · 완전성 %s%s",
                r.dryRun() ? "사전점검" : "실행", r.settledTradingDay(),
                r.candidateRows(), r.notDue(), r.dueRows(), r.distinctStocks(),
                r.fetched(), r.fetchFailed(), r.fetchFailedRows(), r.skippedByCap(), r.unchanged(),
                r.evaluatedByStatus(), r.coverageByType(),
                r.notes().isEmpty() ? "" : " · 비고 " + r.notes());
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    // ==================== 구값 · 교정값 비교표 ====================

    /**
     * 타입별 비교 — 게이트를 교정값으로 넘길지 판단하는 근거. 순수 집계(테스트 대상).
     * 교정값이 있는 행 = {@code d3_status = OK} 뿐이다(그 외는 수익 필드가 비어 자동 제외).
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
                boolean hasNew = SignalD3Evaluator.Status.OK.name().equals(s.getD3Status()) && s.getD3PctChange() != null;
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
                + "둘 다 있는 행(교정 OK)만 비교했다. 게이트 전환은 이 표를 본 뒤 별도로 결정한다.");
    }

    private static BigDecimal avg(BigDecimal sum, int n) {
        return n == 0 ? null : sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
    }
}
