package com.myplatform.backend.crosscheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.BotTradingPositionRepository;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.repository.StockWatchlistRepository;
import com.myplatform.backend.repository.VirtualPortfolioRepository;
import com.myplatform.backend.service.MarketCalendarService;
import com.myplatform.backend.service.SchedulerLockService;
import com.myplatform.backend.service.StockStatusService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 외부 공개 수치 자동 대조(V76, 2026-11-11, 개선 후보 3번) — <b>측정 전용</b>.
 *
 * <p>매 거래일 10:20:40 에 작은 표본(최대 {@value CrosscheckRules#MAX_SAMPLE}종목)의 마지막 확정 봉 종가(우리 {@code stock_price_history})를
 * 네이버 종목 응답의 '전일'(직전 거래일 KRX 종가)과 견준다. 종가는 정확히 같아야 한다. 같은 응답의 EPS·BPS 는 판정 없이 기록만 한다
 * (정의가 달라 허용 폭이 필요한데 근거로 댈 분포가 아직 없다 — 쌓인 뒤 사용자가 정한다).
 *
 * <p><b>지키는 것</b>
 * <ul>
 *   <li>우리 값을 고치지 않는다(§3) — 봉·재무 표는 읽기만 하고, 쓰는 곳은 이 패키지의 두 표뿐이다.</li>
 *   <li>추천·봇·시그널에 넣지 않는다 — 이 패키지를 참조하는 바깥 클래스는 관제실뿐({@code CrosscheckIsolationTest}).</li>
 *   <li>요청에 헤더를 붙이지 않는다 — 봇 차단을 우회하지 않는다. 막히면 '대조 못 함'으로 드러날 뿐이다.</li>
 *   <li>대조 실패는 일치가 아니다(§4c) — 일치는 {@code MATCH} 하나.</li>
 *   <li>하루 최대 {@value CrosscheckRules#MAX_SAMPLE}회, 1.5초 간격, 연속 {@value CrosscheckRules#ABORT_AFTER_CONSECUTIVE_FAILURES}건 실패면 그 실행을 멈춘다.</li>
 * </ul>
 *
 * <p>10:20 인 이유: 07:40:30 아침 봉 재수집 뒤이고, 10시에 장이 여는 날(수능일 등)에도 장이 열린 뒤라 '전일'이 직전 거래일 값이며,
 * 첫 신호 기록(11:30) 전에 어긋남이 보인다. 응답에 날짜가 없어 넘어가는 시각을 모르므로 우리 그 앞 봉과 같으면 '하루 늦음(대조 못 함)'으로 가른다.
 */
@Service
@Slf4j
public class ExternalCrosscheckService {

    static final String INTEGRATION_URL = "https://m.stock.naver.com/api/stock/%s/integration";
    static final int RETENTION_DAYS = 365;
    /** 이보다 오래 RUNNING 이면 중단된 실행으로 본다(정상 실행은 약 1분). */
    static final Duration RUNNING_STALE_AFTER = Duration.ofMinutes(30);
    static final String INTERRUPTED = "INTERRUPTED";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ExternalCrosscheckRunRepository runRepository;
    private final ExternalCrosscheckRowRepository rowRepository;
    private final StockPriceHistoryRepository historyRepository;
    private final StockFinancialDataRepository financialRepository;
    private final RecommendationSnapshotRepository snapshotRepository;
    private final VirtualPortfolioRepository virtualPortfolioRepository;
    private final BotTradingPositionRepository botPositionRepository;
    private final StockWatchlistRepository watchlistRepository;
    private final StockStatusService stockStatusService;
    private final MarketCalendarService marketCalendar;
    private final SchedulerLockService schedulerLockService;
    private final Clock clock;
    private final boolean enabled;

    /** 요청 사이 쉬는 시간(ms). 테스트는 0. */
    long pauseMillis = 1500;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService manualExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "external-crosscheck-manual");
        t.setDaemon(true);
        return t;
    });

    public ExternalCrosscheckService(RestTemplate restTemplate, ObjectMapper objectMapper,
                                     ExternalCrosscheckRunRepository runRepository,
                                     ExternalCrosscheckRowRepository rowRepository,
                                     StockPriceHistoryRepository historyRepository,
                                     StockFinancialDataRepository financialRepository,
                                     RecommendationSnapshotRepository snapshotRepository,
                                     VirtualPortfolioRepository virtualPortfolioRepository,
                                     BotTradingPositionRepository botPositionRepository,
                                     StockWatchlistRepository watchlistRepository,
                                     StockStatusService stockStatusService,
                                     MarketCalendarService marketCalendar,
                                     SchedulerLockService schedulerLockService,
                                     Clock clock,
                                     @Value("${external-crosscheck.enabled:true}") boolean enabled) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.runRepository = runRepository;
        this.rowRepository = rowRepository;
        this.historyRepository = historyRepository;
        this.financialRepository = financialRepository;
        this.snapshotRepository = snapshotRepository;
        this.virtualPortfolioRepository = virtualPortfolioRepository;
        this.botPositionRepository = botPositionRepository;
        this.watchlistRepository = watchlistRepository;
        this.stockStatusService = stockStatusService;
        this.marketCalendar = marketCalendar;
        this.schedulerLockService = schedulerLockService;
        this.clock = clock;
        this.enabled = enabled;
    }

    /** 꺼져 있으면 부팅 때 한 번 알린다 — 조용히 안 도는 조합을 만들지 않는다(§4b). */
    @PostConstruct
    void announceState() {
        if (!enabled) {
            log.warn("[외부 대조] 꺼져 있다 — 우리 종가를 외부 공개 수치와 견주지 않는다. 켜려면 EXTERNAL_CROSSCHECK_ENABLED=true");
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        manualExecutor.shutdownNow();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 매 거래일 10:20:40 KST — 초 필드는 :00 을 피한다(§4b). 휴장일은 아무것도 쓰지 않는다. */
    @Scheduled(scheduler = "batchScheduler", cron = "40 20 10 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledRun() {
        if (!enabled) {
            log.debug("[외부 대조] 꺼져 있음 — 건너뜀");
            return;
        }
        if (marketCalendar.isMarketClosed()) {
            log.debug("[외부 대조] 휴장일 — 건너뜀");
            return;
        }
        if (!schedulerLockService.tryLock("external-crosscheck.daily", Duration.ofMinutes(20))) {
            log.debug("[외부 대조] 다른 인스턴스에서 진행 중 — 건너뜀");
            return;
        }
        run("CRON");
    }

    /**
     * 관리자 수동 실행 — 백그라운드로 돌리고 바로 돌아온다. 시작했으면 null, 아니면 이유.
     * 대조 창({@link #inRunWindow}) 밖이면 시작하지 않는다 — 외부 '전일'이 어느 날 값인지 모르는 시각이다.
     */
    public String startManualRun() {
        if (running.get()) return "이미 도는 중이다";
        LocalDateTime now = LocalDateTime.now(clock);
        if (!inRunWindow(now)) return "대조는 장이 열린 거래일 장 시작 10분 뒤부터 15:30 까지만 돌린다 — 그 밖엔 외부 '전일'이 어느 날 값인지 모른다";
        manualExecutor.submit(() -> run("MANUAL"));
        return null;
    }

    /**
     * 대조 창 — 장이 열린 거래일, 장 시작 10분 뒤부터 장 마감(접속매매 종료) 전까지. 이 안에서는 네이버 '전일'이 오늘 세션의 전일,
     * 곧 직전 거래일 KRX 종가다(2026-11-10 16:33 에도 그랬다). 장 시작 전·휴장일엔 넘어갔는지 몰라 돌리지 않는다.
     */
    boolean inRunWindow(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        if (marketCalendar.isMarketClosed(today)) return false;
        java.time.LocalTime t = now.toLocalTime();
        return !t.isBefore(marketCalendar.marketOpenOf(today).plusMinutes(10))
                && t.isBefore(marketCalendar.marketCloseOf(today).minusMinutes(10));
    }

    /** 실행 한 번. 이미 도는 중이면 null. 실행 기록은 예외가 나도 남긴다. */
    ExternalCrosscheckRun run(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.info("[외부 대조] 이미 도는 중 — {} 실행 건너뜀", trigger);
            return null;
        }
        ExternalCrosscheckRun runEntity = null;
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDate today = now.toLocalDate();
            if (!inRunWindow(now)) {
                log.info("[외부 대조] 대조 창 밖({}) — {} 실행 건너뜀(장이 열린 거래일 장 시작 10분 뒤 ~ 15:30)", now, trigger);
                return null;
            }
            // 오늘 세션의 전일 = 직전 거래일 — 네이버 '전일'이 가리키는 날
            LocalDate target = marketCalendar.minusTradingDays(today, 1);
            Map<String, String> sample = CrosscheckRules.selectSample(
                    safe(this::candidates), safe(this::holdings), safe(this::watchlist), safe(this::marketCapTop),
                    safe(() -> historyRepository.findStockCodesByTradeDate(target)),
                    target.toEpochDay(), this::halted);

            runEntity = runRepository.save(ExternalCrosscheckRun.builder()
                    .runDate(today).targetBarDate(target).source(ExternalCrosscheckRun.SOURCE_NAVER)
                    .triggerType(trigger).status(ExternalCrosscheckRun.STATUS_RUNNING)
                    .planned(sample.size()).startedAt(now).build());

            List<CrosscheckRules.CloseOutcome> outcomes = new ArrayList<>();
            int consecutiveFailures = 0;
            String extra = null;
            String status = ExternalCrosscheckRun.STATUS_COMPLETE;
            int index = 0;
            for (Map.Entry<String, String> e : sample.entrySet()) {
                if (index++ > 0) pause();
                CrosscheckRules.CloseOutcome outcome = checkOne(runEntity.getId(), e.getKey(), e.getValue(), target, today);
                outcomes.add(outcome);
                consecutiveFailures = CrosscheckRules.FETCH_FAILED.equals(outcome.status()) ? consecutiveFailures + 1 : 0;
                if (consecutiveFailures >= CrosscheckRules.ABORT_AFTER_CONSECUTIVE_FAILURES) {
                    status = ExternalCrosscheckRun.STATUS_ABORTED;
                    extra = "연속 " + consecutiveFailures + "건 응답을 받지 못해 멈췄다 — 남은 "
                            + (sample.size() - outcomes.size()) + "종목은 시도하지 않았다";
                    break;
                }
            }
            finish(runEntity, status, outcomes, extra);
            log.info("[외부 대조] {} 봉 종가 — 표본 {} · 시도 {} · 일치 {} · 불일치 {} · 대조 못 함 {} ({}){}",
                    target, runEntity.getPlanned(), runEntity.getAttempted(), runEntity.getMatched(),
                    runEntity.getMismatched(), runEntity.getNotCompared(), status,
                    runEntity.getNote() == null ? "" : " — " + runEntity.getNote());
            if (runEntity.getMismatched() > 0) {
                log.warn("[외부 대조] 종가 불일치 {}종목: {} — 값은 고치지 않는다(§3), 관제실 이상 점검을 볼 것",
                        runEntity.getMismatched(), mismatchedCodes(outcomes));
            }
            purgeOld(today);
            return runEntity;
        } catch (Exception ex) {
            log.warn("[외부 대조] 실행 실패 — '일치'가 아니라 '대조 못 함'이다: {}", ex.getMessage());
            if (runEntity != null) {
                try {
                    runEntity.setStatus(ExternalCrosscheckRun.STATUS_ERROR);
                    runEntity.setNote(CrosscheckRules.truncate("예외: " + ex.getMessage(), 1000));
                    runEntity.setFinishedAt(LocalDateTime.now(clock));
                    runRepository.save(runEntity);
                } catch (Exception ignore) {
                    log.debug("[외부 대조] 실패 기록도 못 남겼다: {}", ignore.getMessage());
                }
            }
            return runEntity;
        } finally {
            running.set(false);
        }
    }

    /** 종목 하나 — 종가 행(항상) + 응답을 받았으면 EPS·BPS 기록 행. */
    private CrosscheckRules.CloseOutcome checkOne(Long runId, String code, String reason, LocalDate target, LocalDate today) {
        List<StockPriceHistory> bars = historyRepository.findByStockCodeOrderByTradeDateDesc(code, PageRequest.of(0, 2));
        StockPriceHistory latest = bars.isEmpty() ? null : bars.get(0);
        boolean hasTargetBar = latest != null && target.equals(latest.getTradeDate()) && latest.getClosePrice() != null;
        BigDecimal ourD = hasTargetBar ? latest.getClosePrice() : null;
        BigDecimal ourPrev = hasTargetBar && bars.size() > 1 ? bars.get(1).getClosePrice() : null;

        LocalDateTime fetchedAt = LocalDateTime.now(clock);
        CrosscheckRules.NaverQuote quote;
        try {
            String body = restTemplate.getForObject(URI.create(String.format(INTEGRATION_URL, code)), String.class);
            if (body == null || body.isBlank()) throw new IllegalStateException("빈 응답");
            quote = CrosscheckRules.parseIntegration(objectMapper.readTree(body));
        } catch (Exception ex) {
            String why = ex.getClass().getSimpleName() + ": " + Objects.toString(ex.getMessage(), "");
            log.debug("[외부 대조] {} 응답 못 받음: {}", code, why);
            rowRepository.save(ExternalCrosscheckRow.builder()
                    .runId(runId).stockCode(code).sampleReason(reason).item(CrosscheckRules.ITEM_CLOSE)
                    .ourValue(ourD).ourValueDate(latest == null ? null : latest.getTradeDate()).ourPrevValue(ourPrev)
                    .status(CrosscheckRules.FETCH_FAILED).reason(CrosscheckRules.truncate(why, 200)).fetchedAt(fetchedAt)
                    .build());
            return new CrosscheckRules.CloseOutcome(code, CrosscheckRules.FETCH_FAILED, ourD, ourPrev, null);
        }

        BigDecimal external = quote.lastClose().value();
        String status = CrosscheckRules.classifyClose(ourD, ourPrev, external);
        String why = switch (status) {
            case CrosscheckRules.NO_OUR_BAR -> latest == null ? "우리 봉 없음"
                    : "우리 마지막 봉 " + latest.getTradeDate() + " — " + target + " 봉 없음";
            case CrosscheckRules.NO_EXTERNAL_VALUE -> "응답에 '전일' 값 없음";
            case CrosscheckRules.EXTERNAL_STALE -> "외부 값이 우리 그 앞 봉과 같다 — 아직 넘어가지 않은 것으로 본다";
            default -> null;
        };
        rowRepository.save(ExternalCrosscheckRow.builder()
                .runId(runId).stockCode(code).sampleReason(reason).item(CrosscheckRules.ITEM_CLOSE)
                .ourValue(ourD).ourValueDate(latest == null ? null : latest.getTradeDate()).ourPrevValue(ourPrev)
                .externalValue(external).externalRaw(quote.lastClose().raw())
                .status(status).diffPct(CrosscheckRules.diffPct(ourD, external)).reason(why).fetchedAt(fetchedAt)
                .build());

        recordPerShare(runId, code, reason, today, quote, fetchedAt);
        return new CrosscheckRules.CloseOutcome(code, status, ourD, ourPrev, external);
    }

    /** EPS·BPS — 판정 없이 기록만. 우리 쪽은 최신 KIS 일별 행(미래 날짜 행 제외). */
    private void recordPerShare(Long runId, String code, String reason, LocalDate today,
                                CrosscheckRules.NaverQuote quote, LocalDateTime fetchedAt) {
        StockFinancialData ours = null;
        try {
            for (StockFinancialData f : financialRepository.findTop10ByStockCodeOrderByReportDateDesc(code)) {
                if (f.getMarketCap() != null && f.getReportDate() != null && !f.getReportDate().isAfter(today)) {
                    ours = f;
                    break;
                }
            }
        } catch (Exception ex) {
            log.debug("[외부 대조] {} 재무 행 조회 실패: {}", code, ex.getMessage());
        }
        recordOne(runId, code, reason, CrosscheckRules.ITEM_EPS, ours == null ? null : ours.getEps(), ours, quote.eps(), fetchedAt);
        recordOne(runId, code, reason, CrosscheckRules.ITEM_BPS, ours == null ? null : ours.getBps(), ours, quote.bps(), fetchedAt);
    }

    private void recordOne(Long runId, String code, String reason, String item, BigDecimal ourValue,
                           StockFinancialData ours, CrosscheckRules.Field ext, LocalDateTime fetchedAt) {
        String status = ourValue == null ? CrosscheckRules.NO_OUR_VALUE
                : ext.value() == null ? CrosscheckRules.NO_EXTERNAL_VALUE : CrosscheckRules.RECORDED;
        rowRepository.save(ExternalCrosscheckRow.builder()
                .runId(runId).stockCode(code).sampleReason(reason).item(item)
                .ourValue(ourValue).ourValueDate(ours == null ? null : ours.getReportDate())
                .ourBasis(ours == null ? null : ours.getPerBasis())
                .externalValue(ext.value()).externalRaw(ext.raw()).externalBasis(ext.basis())
                .status(status).diffPct(CrosscheckRules.diffPct(ourValue, ext.value())).fetchedAt(fetchedAt)
                .build());
    }

    private void finish(ExternalCrosscheckRun run, String status, List<CrosscheckRules.CloseOutcome> outcomes, String extra) {
        int matched = 0;
        int mismatched = 0;
        for (CrosscheckRules.CloseOutcome o : outcomes) {
            if (CrosscheckRules.MATCH.equals(o.status())) matched++;
            else if (CrosscheckRules.MISMATCH.equals(o.status())) mismatched++;
        }
        run.setStatus(status);
        run.setAttempted(outcomes.size());
        run.setMatched(matched);
        run.setMismatched(mismatched);
        run.setNotCompared(outcomes.size() - matched - mismatched);
        run.setNote(CrosscheckRules.runNote(outcomes, extra));
        run.setFinishedAt(LocalDateTime.now(clock));
        runRepository.save(run);
    }

    private void purgeOld(LocalDate today) {
        try {
            LocalDate cutoff = today.minusDays(RETENTION_DAYS);
            int rows = runRepository.deleteRowsOfRunsBefore(cutoff);
            int runs = runRepository.deleteRunsBefore(cutoff);
            if (runs > 0) log.info("[외부 대조] 보존 {}일 지난 실행 {}건(종목 행 {}) 지움", RETENTION_DAYS, runs, rows);
        } catch (Exception ex) {
            log.warn("[외부 대조] 옛 기록 정리 실패(다음 실행이 다시 지운다): {}", ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- 관제실 조회(읽기 전용)

    /** 실행 한 번의 요약 — 관제실 이상 점검·조회 API. {@code status} 가 {@link #INTERRUPTED} 면 끝나지 못한 실행이다. */
    public record RunSummary(Long id, LocalDate runDate, LocalDate targetBarDate, String triggerType, String status,
                             int planned, int attempted, int matched, int mismatched, int notCompared,
                             Map<String, Integer> notComparedReasons, List<String> mismatchedCodes,
                             boolean staleMixedWithMatch, String note, LocalDateTime startedAt) {}

    /** 최근 실행(새 것부터, 막 시작한 RUNNING 은 뺀다). 앞 네 실행은 종목 행까지 읽어 불일치 종목·사유를 채운다. */
    public List<RunSummary> recentRuns() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<RunSummary> out = new ArrayList<>();
        int detailed = 0;
        for (ExternalCrosscheckRun r : runRepository.findTop10ByOrderByStartedAtDesc()) {
            String status = r.getStatus();
            if (ExternalCrosscheckRun.STATUS_RUNNING.equals(status)) {
                if (r.getStartedAt() != null && r.getStartedAt().isAfter(now.minus(RUNNING_STALE_AFTER))) continue;
                status = INTERRUPTED;
            }
            Map<String, Integer> reasons = new LinkedHashMap<>();
            List<String> mismatched = new ArrayList<>();
            boolean staleMixed = false;
            if (detailed < 4) {
                detailed++;
                List<CrosscheckRules.CloseOutcome> outcomes = new ArrayList<>();
                for (ExternalCrosscheckRow row : rowRepository.findByRunIdOrderByIdAsc(r.getId())) {
                    if (!CrosscheckRules.ITEM_CLOSE.equals(row.getItem())) continue;
                    outcomes.add(new CrosscheckRules.CloseOutcome(row.getStockCode(), row.getStatus(),
                            row.getOurValue(), row.getOurPrevValue(), row.getExternalValue()));
                    if (CrosscheckRules.MISMATCH.equals(row.getStatus())) mismatched.add(row.getStockCode());
                    else if (!CrosscheckRules.MATCH.equals(row.getStatus())) reasons.merge(row.getStatus(), 1, Integer::sum);
                }
                staleMixed = CrosscheckRules.staleMixedWithMatch(outcomes);
            }
            out.add(new RunSummary(r.getId(), r.getRunDate(), r.getTargetBarDate(), r.getTriggerType(), status,
                    r.getPlanned(), r.getAttempted(), r.getMatched(), r.getMismatched(), r.getNotCompared(),
                    reasons, mismatched, staleMixed, r.getNote(), r.getStartedAt()));
        }
        out.sort(Comparator.comparing(RunSummary::startedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /** 조회 API — 최근 실행 요약과 마지막 실행의 종목 행. */
    public Map<String, Object> overview() {
        List<RunSummary> runs = recentRuns();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("runs", runs);
        out.put("latestRows", runs.isEmpty() ? List.of() : rowRepository.findByRunIdOrderByIdAsc(runs.get(0).id()));
        return out;
    }

    // ---------------------------------------------------------------- 표본 출처(DB 만 — KIS 를 부르지 않는다)

    private List<String> candidates() {
        return snapshotRepository.findLatestSnapshot().stream()
                .map(com.myplatform.backend.entity.RecommendationSnapshot::getStockCode).toList();
    }

    private List<String> holdings() {
        List<String> out = new ArrayList<>();
        virtualPortfolioRepository.findAll().forEach(p -> {
            if (p.getQuantity() != null && p.getQuantity() > 0) out.add(p.getStockCode());
        });
        botPositionRepository.findAll().forEach(p -> out.add(p.getStockCode()));
        return out;
    }

    private List<String> watchlist() {
        return watchlistRepository.findByIsActiveTrue().stream()
                .map(com.myplatform.backend.entity.StockWatchlist::getStockCode).toList();
    }

    private List<String> marketCapTop() {
        List<Object[]> rows = new ArrayList<>(financialRepository.findLatestMarketCaps());
        rows.sort((a, b) -> ((BigDecimal) b[1]).compareTo((BigDecimal) a[1]));
        return rows.stream().limit(CrosscheckRules.GROUP_CAP).map(r -> (String) r[0]).toList();
    }

    private boolean halted(String code) {
        try {
            return stockStatusService.activeStatus(code) == StockStatusService.ActiveStatus.HALTED;
        } catch (Exception e) {
            return false;   // 모르면 빼지 않는다
        }
    }

    private List<String> safe(java.util.function.Supplier<List<String>> source) {
        try {
            List<String> v = source.get();
            return v == null ? List.of() : v;
        } catch (Exception e) {
            log.warn("[외부 대조] 표본 출처 조회 실패 — 그 몫 없이 진행: {}", e.getMessage());
            return List.of();
        }
    }

    private void pause() {
        if (pauseMillis <= 0) return;
        try {
            Thread.sleep(pauseMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> mismatchedCodes(List<CrosscheckRules.CloseOutcome> outcomes) {
        return outcomes.stream().filter(o -> CrosscheckRules.MISMATCH.equals(o.status()))
                .map(CrosscheckRules.CloseOutcome::stockCode).toList();
    }
}
