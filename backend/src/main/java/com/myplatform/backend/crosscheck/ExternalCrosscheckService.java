package com.myplatform.backend.crosscheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.BotTradingPositionRepository;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.repository.StockWatchlistRepository;
import com.myplatform.backend.repository.VirtualAccountRepository;
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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 외부 공개 수치 자동 대조(V76, 2026-11-11, 개선 후보 3번) — <b>측정 전용</b>.
 *
 * <p>매 거래일 10:20:40 에 작은 표본(한 번 실행에 최대 {@value CrosscheckRules#MAX_SAMPLE}종목)의 마지막 확정 봉 종가(우리
 * {@code stock_price_history})를 네이버 종목 응답의 '전일'(직전 거래일 KRX 종가)과 견준다. 종가는 정확히 같아야 한다. 같은 응답의
 * EPS·BPS 는 판정 없이 기록만 한다(정의가 달라 허용 폭이 필요한데 근거로 댈 분포가 아직 없다 — 쌓인 뒤 사용자가 정한다).
 *
 * <p><b>지키는 것</b>
 * <ul>
 *   <li>우리 값을 고치지 않는다(§3) — 봉·재무 표는 읽기만 하고, 쓰는 곳은 자기 표 두 개(+ 모든 크론이 남기는 공용 배치 실행 기록)뿐.</li>
 *   <li>추천·봇·시그널에 넣지 않는다 — 이 패키지를 참조하는 바깥 클래스는 관제실뿐({@code CrosscheckIsolationTest}).</li>
 *   <li>브라우저를 흉내 내는 헤더(User-Agent 위장·Referer·Cookie)를 붙이지 않는다 — 공용 RestTemplate 의 기본 Accept 와 JDK 의
 *       기본 User-Agent(Java/…)만 나간다. 막히면 '대조 못 함'으로 드러날 뿐이다.</li>
 *   <li>대조 실패는 일치가 아니다(§4c) — 일치는 {@code MATCH} 하나.</li>
 *   <li>한 번 실행에 최대 {@value CrosscheckRules#MAX_SAMPLE}종목, 1.5초 간격, 연속
 *       {@value CrosscheckRules#ABORT_AFTER_CONSECUTIVE_FAILURES}건 값을 못 받으면 그 실행을 멈춘다. 수동 실행은 하루
 *       {@value #MANUAL_RUNS_PER_DAY}회까지, 꺼져 있으면 거절한다.</li>
 * </ul>
 *
 * <p>10:20 인 이유: 07:40:30 아침 봉 재수집 뒤이고, 10시에 장이 여는 날(수능일 등)에도 장이 열린 뒤라 '전일'이 직전 거래일 값이며,
 * 첫 신호 기록(11:30) 전에 어긋남이 보인다. 응답에 날짜가 없어 넘어가는 시각을 모르므로 우리 그 앞 봉과 같으면 '하루 늦음(대조 못 함)'으로
 * 가르고, 같은 실행에서 넘어갔음이 증명되면 그 '하루 늦음'은 불일치로 다시 분류한다.
 */
@Service
@Slf4j
public class ExternalCrosscheckService {

    static final String INTEGRATION_URL = "https://m.stock.naver.com/api/stock/%s/integration";
    static final int RETENTION_DAYS = 365;
    /** 이보다 오래 RUNNING 이면 끝나지 못한 실행으로 본다(정상 실행은 약 1분). */
    static final Duration RUNNING_STALE_AFTER = Duration.ofMinutes(30);
    /** 수동 실행 하루 상한 — 한 번에 최대 40회 호출이라 버튼을 거듭 누르면 그만큼 더 나간다. */
    static final int MANUAL_RUNS_PER_DAY = 3;
    static final String TRIGGER_CRON = "CRON";
    static final String TRIGGER_MANUAL = "MANUAL";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ExternalCrosscheckRunRepository runRepository;
    private final ExternalCrosscheckRowRepository rowRepository;
    private final StockPriceHistoryRepository historyRepository;
    private final StockFinancialDataRepository financialRepository;
    private final RecommendationSnapshotRepository snapshotRepository;
    private final VirtualAccountRepository virtualAccountRepository;
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
                                     VirtualAccountRepository virtualAccountRepository,
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
        this.virtualAccountRepository = virtualAccountRepository;
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
        run(TRIGGER_CRON);
    }

    /**
     * 관리자 수동 실행 — 백그라운드로 돌리고 바로 돌아온다. 시작했으면 null, 아니면 이유. 꺼져 있거나, 대조 창
     * ({@link #inRunWindow}) 밖이거나, 오늘 {@value #MANUAL_RUNS_PER_DAY}회를 다 썼거나, 이미 도는 중이면 시작하지 않는다.
     * 자리는 제출 전에 원자적으로 잡는다 — 거의 동시에 온 두 요청이 연달아 두 번 돌지 않게.
     */
    public String startManualRun() {
        if (!enabled) return "외부 대조가 꺼져 있다(EXTERNAL_CROSSCHECK_ENABLED=false) — 수동 실행도 하지 않는다";
        LocalDateTime now = LocalDateTime.now(clock);
        if (!inRunWindow(now)) {
            return "대조는 장이 열린 거래일의 장 시작 10분 뒤부터 장 마감 10분 전까지만 돌린다 — 그 밖엔 외부 '전일'이 어느 날 값인지 모른다";
        }
        long usedToday;
        try {
            usedToday = runRepository.countByRunDateAndTriggerType(now.toLocalDate(), TRIGGER_MANUAL);
        } catch (Exception e) {
            return "오늘 수동 실행 횟수를 확인하지 못했다 — 돌리지 않는다: " + e.getMessage();
        }
        if (usedToday >= MANUAL_RUNS_PER_DAY) {
            return "수동 실행은 하루 " + MANUAL_RUNS_PER_DAY + "회까지다(오늘 " + usedToday + "회)";
        }
        if (!running.compareAndSet(false, true)) return "이미 도는 중이다";
        try {
            manualExecutor.submit(() -> {
                try {
                    runClaimed(TRIGGER_MANUAL);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            return "실행을 시작하지 못했다: " + e.getMessage();
        }
        return null;
    }

    /**
     * 대조 창 — 장이 열린 거래일, 장 시작 10분 뒤부터 장 마감(접속매매 종료) 10분 전까지. 이 안에서는 네이버 '전일'이 오늘 세션의 전일,
     * 곧 직전 거래일 KRX 종가다(2026-11-10 16:33, 11-11 10:55 확인). 장 시작 전·휴장일엔 넘어갔는지 몰라 돌리지 않는다.
     */
    boolean inRunWindow(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        if (marketCalendar.isMarketClosed(today)) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(marketCalendar.marketOpenOf(today).plusMinutes(10))
                && t.isBefore(marketCalendar.marketCloseOf(today).minusMinutes(10));
    }

    /** 실행 한 번(크론). 이미 도는 중이면 null. */
    ExternalCrosscheckRun run(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.info("[외부 대조] 이미 도는 중 — {} 실행 건너뜀", trigger);
            return null;
        }
        try {
            return runClaimed(trigger);
        } finally {
            running.set(false);
        }
    }

    /** 자리를 잡은 뒤의 실행 본체. 실행 기록은 예외·중단이 나도 남긴다. */
    private ExternalCrosscheckRun runClaimed(String trigger) {
        ExternalCrosscheckRun runEntity = null;
        List<CrosscheckRules.CloseOutcome> outcomes = new ArrayList<>();
        Map<String, ExternalCrosscheckRow> closeRows = new LinkedHashMap<>();
        List<String> extras = new ArrayList<>();
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDate today = now.toLocalDate();
            if (!inRunWindow(now)) {
                log.info("[외부 대조] 대조 창 밖({}) — {} 실행 건너뜀(거래일 장 시작 10분 뒤 ~ 장 마감 10분 전)", now, trigger);
                return null;
            }
            closeStaleRunning(now);
            // 오늘 세션의 전일 = 직전 거래일 — 네이버 '전일'이 가리키는 날
            LocalDate target = marketCalendar.minusTradingDays(today, 1);
            List<String> sourceFailures = new ArrayList<>();
            Set<String> withBar = new HashSet<>(safe("대상 봉 목록", () -> historyRepository.findStockCodesByTradeDate(target), sourceFailures));
            CrosscheckRules.Sample sample = CrosscheckRules.selectSample(
                    safe("직전 불일치", this::recheckCodes, sourceFailures),
                    safe("추천 스냅샷", this::candidates, sourceFailures),
                    safe("모의 보유·봇 포지션", this::holdings, sourceFailures),
                    safe("관심종목", this::watchlist, sourceFailures),
                    safe("시가총액 상위", this::marketCapTop, sourceFailures),
                    withBar, target.toEpochDay(), withBar::contains, this::halted);
            if (sample.excludedNoBar() > 0) {
                extras.add("대상 봉(" + target + ")이 없어 표본에서 뺀 고정 그룹 종목 " + sample.excludedNoBar() + "개");
            }
            if (!sourceFailures.isEmpty()) extras.add("표본 출처 조회 실패: " + String.join(", ", sourceFailures));
            if (sample.codes().isEmpty()) {
                extras.add("표본이 비었다 — " + target + " 봉을 가진 종목이 없다(07:40 아침 봉 재수집·일봉 수집을 확인)");
            }

            runEntity = runRepository.save(ExternalCrosscheckRun.builder()
                    .runDate(today).targetBarDate(target).source(ExternalCrosscheckRun.SOURCE_NAVER)
                    .triggerType(trigger).status(ExternalCrosscheckRun.STATUS_RUNNING)
                    .planned(sample.codes().size()).startedAt(now).build());

            int consecutiveFailures = 0;
            String status = ExternalCrosscheckRun.STATUS_COMPLETE;
            int[] perShareFailures = {0};
            int index = 0;
            for (Map.Entry<String, String> e : sample.codes().entrySet()) {
                if (index++ > 0) pause();
                if (Thread.currentThread().isInterrupted()) {
                    status = ExternalCrosscheckRun.STATUS_INTERRUPTED;
                    extras.add("중단 표시를 받아 멈췄다 — 남은 " + (sample.codes().size() - outcomes.size()) + "종목은 시도하지 않았다");
                    break;
                }
                CrosscheckRules.CloseOutcome outcome = checkOne(runEntity.getId(), e.getKey(), e.getValue(), target, today,
                        closeRows, perShareFailures);
                outcomes.add(outcome);
                consecutiveFailures = CrosscheckRules.countsTowardAbort(outcome.status()) ? consecutiveFailures + 1 : 0;
                if (consecutiveFailures >= CrosscheckRules.ABORT_AFTER_CONSECUTIVE_FAILURES) {
                    status = ExternalCrosscheckRun.STATUS_ABORTED;
                    extras.add("연속 " + consecutiveFailures + "건 값을 받지 못해 멈췄다 — 남은 "
                            + (sample.codes().size() - outcomes.size()) + "종목은 시도하지 않았다");
                    break;
                }
            }
            if (perShareFailures[0] > 0) extras.add("EPS·BPS 기록 행 저장 실패 " + perShareFailures[0] + "건(종가 대조는 계속했다)");
            // 중단 표시를 잠시 내려 둔다 — 표시가 켜진 채면 연결 풀이 연결을 내주지 않아 마감 기록을 못 남긴다
            boolean interrupted = Thread.interrupted();
            try {
                finish(runEntity, status, outcomes, closeRows, extras);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            log.info("[외부 대조] {} 봉 종가 — 표본 {} · 시도 {} · 일치 {} · 불일치 {} · 대조 못 함 {} ({}){}",
                    target, runEntity.getPlanned(), runEntity.getAttempted(), runEntity.getMatched(),
                    runEntity.getMismatched(), runEntity.getNotCompared(), status,
                    runEntity.getNote() == null ? "" : " — " + runEntity.getNote());
            if (runEntity.getMismatched() > 0) {
                log.warn("[외부 대조] 종가 불일치 {}종목 — 값은 고치지 않는다(§3), 관제실 이상 점검을 볼 것", runEntity.getMismatched());
            }
            purgeOld(today);
            return runEntity;
        } catch (Exception ex) {
            log.warn("[외부 대조] 실행 실패 — '일치'가 아니라 '대조 못 함'이다: {}", ex.getMessage());
            if (runEntity != null) {
                try {
                    extras.add("예외: " + ex.getMessage());
                    finish(runEntity, ExternalCrosscheckRun.STATUS_ERROR, outcomes, closeRows, extras);
                } catch (Exception ignore) {
                    log.debug("[외부 대조] 실패 기록도 못 남겼다: {}", ignore.getMessage());
                }
            }
            return runEntity;
        }
    }

    /** 종목 하나 — 종가 행(항상) + 응답을 받았으면 EPS·BPS 기록 행(저장이 실패해도 종가 대조를 끊지 않는다). */
    private CrosscheckRules.CloseOutcome checkOne(Long runId, String code, String reason, LocalDate target, LocalDate today,
                                                  Map<String, ExternalCrosscheckRow> closeRows, int[] perShareFailures) {
        List<StockPriceHistory> bars = historyRepository.findByStockCodeOrderByTradeDateDesc(code, PageRequest.of(0, 2));
        StockPriceHistory latest = bars.isEmpty() ? null : bars.get(0);
        boolean targetBarDay = latest != null && target.equals(latest.getTradeDate());
        boolean hasTargetBar = targetBarDay && latest.getClosePrice() != null;
        BigDecimal ourD = hasTargetBar ? latest.getClosePrice() : null;
        BigDecimal ourPrev = hasTargetBar && bars.size() > 1 ? bars.get(1).getClosePrice() : null;
        LocalDate ourDate = latest == null ? null : latest.getTradeDate();

        LocalDateTime fetchedAt = LocalDateTime.now(clock);
        CrosscheckRules.NaverQuote quote;
        try {
            String body = restTemplate.getForObject(URI.create(String.format(INTEGRATION_URL, code)), String.class);
            if (body == null || body.isBlank()) throw new IllegalStateException("빈 응답");
            quote = CrosscheckRules.parseIntegration(objectMapper.readTree(body));
        } catch (Exception ex) {
            String why = ex.getClass().getSimpleName() + ": " + Objects.toString(ex.getMessage(), "");
            log.debug("[외부 대조] {} 응답 못 받음: {}", code, why);
            saveClose(closeRows, ExternalCrosscheckRow.builder()
                    .runId(runId).stockCode(code).sampleReason(reason).item(CrosscheckRules.ITEM_CLOSE)
                    .ourValue(ourD).ourValueDate(ourDate).ourPrevValue(ourPrev)
                    .status(CrosscheckRules.FETCH_FAILED).reason(CrosscheckRules.truncate(why, 200)).fetchedAt(fetchedAt)
                    .build());
            return new CrosscheckRules.CloseOutcome(code, CrosscheckRules.FETCH_FAILED, ourD, ourPrev, null);
        }

        if (CrosscheckRules.isWrongItem(code, quote)) {
            saveClose(closeRows, ExternalCrosscheckRow.builder()
                    .runId(runId).stockCode(code).sampleReason(reason).item(CrosscheckRules.ITEM_CLOSE)
                    .ourValue(ourD).ourValueDate(ourDate).ourPrevValue(ourPrev)
                    .externalRaw(quote.lastClose().raw())
                    .status(CrosscheckRules.WRONG_ITEM)
                    .reason(CrosscheckRules.truncate("다른 종목 응답(응답 종목코드 " + quote.itemCode() + ")", 200))
                    .fetchedAt(fetchedAt).build());
            return new CrosscheckRules.CloseOutcome(code, CrosscheckRules.WRONG_ITEM, ourD, ourPrev, null);
        }

        BigDecimal external = quote.lastClose().value();
        String status = CrosscheckRules.classifyClose(ourD, ourPrev, external);
        String why = switch (status) {
            case CrosscheckRules.NO_OUR_BAR -> latest == null ? "우리 봉 없음"
                    : targetBarDay ? target + " 봉은 있는데 종가 칸이 비어 있다"
                    : "우리 마지막 봉 " + latest.getTradeDate() + " — " + target + " 봉 없음";
            case CrosscheckRules.NO_EXTERNAL_VALUE -> "응답에 '전일' 값 없음(칸 없음·숫자 아님·0)";
            case CrosscheckRules.EXTERNAL_STALE -> "외부 값이 우리 그 앞 봉과 같다 — 아직 넘어가지 않았을 수 있다";
            default -> null;
        };
        if (CrosscheckRules.diffPctOverflows(ourD, external)) why = "차이가 표 칸 범위를 넘어 diff_pct 를 비웠다";
        saveClose(closeRows, ExternalCrosscheckRow.builder()
                .runId(runId).stockCode(code).sampleReason(reason).item(CrosscheckRules.ITEM_CLOSE)
                .ourValue(ourD).ourValueDate(ourDate).ourPrevValue(ourPrev)
                .externalValue(external).externalRaw(quote.lastClose().raw())
                .status(status).diffPct(CrosscheckRules.diffPctForColumn(ourD, external)).reason(why).fetchedAt(fetchedAt)
                .build());

        recordPerShare(runId, code, reason, today, quote, fetchedAt, perShareFailures);
        return new CrosscheckRules.CloseOutcome(code, status, ourD, ourPrev, external);
    }

    private void saveClose(Map<String, ExternalCrosscheckRow> closeRows, ExternalCrosscheckRow row) {
        closeRows.put(row.getStockCode(), rowRepository.save(row));
    }

    /** EPS·BPS — 판정 없이 기록만. 우리 쪽은 최신 KIS 일별 행(미래 날짜 행 제외). 저장 실패는 세기만 한다. */
    private void recordPerShare(Long runId, String code, String reason, LocalDate today,
                                CrosscheckRules.NaverQuote quote, LocalDateTime fetchedAt, int[] perShareFailures) {
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
        // per_basis 는 순이익(EPS·PER) 정의일 뿐이다 — BPS 행엔 붙이지 않는다(CLAUDE.md 10/19 ⑧)
        recordOne(runId, code, reason, CrosscheckRules.ITEM_EPS, ours == null ? null : ours.getEps(), ours,
                ours == null ? null : ours.getPerBasis(), quote.eps(), fetchedAt, perShareFailures);
        recordOne(runId, code, reason, CrosscheckRules.ITEM_BPS, ours == null ? null : ours.getBps(), ours,
                null, quote.bps(), fetchedAt, perShareFailures);
    }

    private void recordOne(Long runId, String code, String reason, String item, BigDecimal ourValue,
                           StockFinancialData ours, String ourBasis, CrosscheckRules.Field ext, LocalDateTime fetchedAt,
                           int[] perShareFailures) {
        String status = ourValue == null ? CrosscheckRules.NO_OUR_VALUE
                : ext.value() == null ? CrosscheckRules.NO_EXTERNAL_VALUE : CrosscheckRules.RECORDED;
        try {
            rowRepository.save(ExternalCrosscheckRow.builder()
                    .runId(runId).stockCode(code).sampleReason(reason).item(item)
                    .ourValue(ourValue).ourValueDate(ours == null ? null : ours.getReportDate())
                    .ourBasis(ourBasis)
                    .externalValue(ext.value()).externalRaw(ext.raw()).externalBasis(ext.basis())
                    .status(status).diffPct(CrosscheckRules.diffPctForColumn(ourValue, ext.value()))
                    .reason(CrosscheckRules.diffPctOverflows(ourValue, ext.value()) ? "차이가 표 칸 범위를 넘어 diff_pct 를 비웠다" : null)
                    .fetchedAt(fetchedAt)
                    .build());
        } catch (Exception ex) {
            perShareFailures[0]++;
            log.debug("[외부 대조] {} {} 기록 행 저장 실패: {}", code, item, ex.getMessage());
        }
    }

    /** 실행 마감 — 넘어갔음이 증명되면 '하루 늦음' 행을 불일치로 다시 쓰고, 집계는 종목 결과에서 센다. */
    private void finish(ExternalCrosscheckRun run, String status, List<CrosscheckRules.CloseOutcome> outcomes,
                        Map<String, ExternalCrosscheckRow> closeRows, List<String> extras) {
        List<CrosscheckRules.CloseOutcome> classified = CrosscheckRules.reclassifyStale(outcomes);
        int reclassified = 0;
        for (int i = 0; i < classified.size(); i++) {
            if (classified.get(i).status().equals(outcomes.get(i).status())) continue;
            reclassified++;
            ExternalCrosscheckRow row = closeRows.get(classified.get(i).stockCode());
            if (row == null) continue;
            row.setStatus(classified.get(i).status());
            row.setReason(CrosscheckRules.STALE_RECLASSIFIED_REASON);
            try {
                rowRepository.save(row);
            } catch (Exception e) {
                extras.add("다시 분류한 행 저장 실패(" + row.getStockCode() + ")");
            }
        }
        CrosscheckRules.Tally t = CrosscheckRules.tally(classified);
        run.setStatus(status);
        run.setAttempted(t.attempted());
        run.setMatched(t.matched());
        run.setMismatched(t.mismatched());
        run.setNotCompared(t.notCompared());
        run.setNote(CrosscheckRules.runNote(classified, reclassified, extras));
        run.setFinishedAt(LocalDateTime.now(clock));
        runRepository.save(run);
    }

    /** 30분 넘게 RUNNING 으로 남은 실행(재시작·강제 종료)을 INTERRUPTED 로 마감한다 — 집계는 남은 종목 행에서 센다. */
    void closeStaleRunning(LocalDateTime now) {
        try {
            for (ExternalCrosscheckRun r : runRepository.findByStatusAndStartedAtBefore(
                    ExternalCrosscheckRun.STATUS_RUNNING, now.minus(RUNNING_STALE_AFTER))) {
                CrosscheckRules.Tally t = CrosscheckRules.tally(closeOutcomesOf(r.getId()));
                r.setStatus(ExternalCrosscheckRun.STATUS_INTERRUPTED);
                r.setAttempted(t.attempted());
                r.setMatched(t.matched());
                r.setMismatched(t.mismatched());
                r.setNotCompared(t.notCompared());
                r.setNote(CrosscheckRules.truncate(Objects.toString(r.getNote(), "")
                        + (r.getNote() == null ? "" : " / ") + "끝나지 못한 실행 — 다음 실행이 마감했다(종목 행 " + t.attempted() + "건으로 다시 셈)", 1000));
                r.setFinishedAt(now);
                runRepository.save(r);
                log.warn("[외부 대조] 끝나지 못한 실행 #{}({} 시작)을 INTERRUPTED 로 마감 — 종목 행 {}건", r.getId(), r.getStartedAt(), t.attempted());
            }
        } catch (Exception e) {
            log.warn("[외부 대조] 남은 RUNNING 실행 정리 실패(다음 실행이 다시 본다): {}", e.getMessage());
        }
    }

    private List<CrosscheckRules.CloseOutcome> closeOutcomesOf(Long runId) {
        List<CrosscheckRules.CloseOutcome> out = new ArrayList<>();
        for (ExternalCrosscheckRow row : rowRepository.findByRunIdOrderByIdAsc(runId)) {
            if (!CrosscheckRules.ITEM_CLOSE.equals(row.getItem())) continue;
            out.add(new CrosscheckRules.CloseOutcome(row.getStockCode(), row.getStatus(),
                    row.getOurValue(), row.getOurPrevValue(), row.getExternalValue()));
        }
        return out;
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

    /**
     * 실행 한 번의 요약 — 관제실 이상 점검·조회 API. {@code detailed} 면 집계·종목별 결과를 종목 행에서 다시 센 것이다
     * (끝나지 못한 실행의 저장 집계가 0 이어도 이미 찾은 불일치가 사라지지 않게). {@code status} 가 {@code INTERRUPTED} 면
     * 끝나지 못한 실행이다(30분 넘게 RUNNING 이던 행 포함).
     */
    public record RunSummary(Long id, LocalDate runDate, LocalDate targetBarDate, String triggerType, String status,
                             int planned, int attempted, int matched, int mismatched, int notCompared,
                             Map<String, Integer> notComparedReasons, Map<String, String> closeStatusByCode,
                             Map<String, BigDecimal> diffPctByCode, boolean staleMixedWithMatch, boolean detailed,
                             String note, LocalDateTime startedAt) {}

    /**
     * 최근 실행(새 것부터, 막 시작한 RUNNING 은 뺀다). 서로 다른 대상 봉 두 개가 채워질 때까지의 실행은 종목 행까지 읽는다 —
     * 규칙 ⑯이 같은 봉의 실행들을 합치고 직전 봉과 견주는 데 쓴다.
     */
    public List<RunSummary> recentRuns() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<RunSummary> out = new ArrayList<>();
        Set<LocalDate> detailedBars = new HashSet<>();
        for (ExternalCrosscheckRun r : runRepository.findTop10ByOrderByStartedAtDesc()) {
            String status = r.getStatus();
            if (ExternalCrosscheckRun.STATUS_RUNNING.equals(status)) {
                if (r.getStartedAt() != null && r.getStartedAt().isAfter(now.minus(RUNNING_STALE_AFTER))) continue;
                status = ExternalCrosscheckRun.STATUS_INTERRUPTED;
            }
            boolean detail = detailedBars.contains(r.getTargetBarDate()) || detailedBars.size() < 2;
            if (!detail) {
                out.add(new RunSummary(r.getId(), r.getRunDate(), r.getTargetBarDate(), r.getTriggerType(), status,
                        r.getPlanned(), r.getAttempted(), r.getMatched(), r.getMismatched(), r.getNotCompared(),
                        Map.of(), Map.of(), Map.of(), false, false, r.getNote(), r.getStartedAt()));
                continue;
            }
            detailedBars.add(r.getTargetBarDate());
            List<CrosscheckRules.CloseOutcome> outcomes = closeOutcomesOf(r.getId());
            CrosscheckRules.Tally t = CrosscheckRules.tally(outcomes);
            Map<String, String> byCode = new LinkedHashMap<>();
            Map<String, BigDecimal> diffs = new LinkedHashMap<>();
            for (CrosscheckRules.CloseOutcome o : outcomes) {
                byCode.put(o.stockCode(), o.status());
                if (o.diffPct() != null) diffs.put(o.stockCode(), o.diffPct());
            }
            out.add(new RunSummary(r.getId(), r.getRunDate(), r.getTargetBarDate(), r.getTriggerType(), status,
                    r.getPlanned(), t.attempted(), t.matched(), t.mismatched(), t.notCompared(),
                    t.notComparedReasons(), byCode, diffs, CrosscheckRules.staleMixedWithMatch(outcomes), true,
                    r.getNote(), r.getStartedAt()));
        }
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

    /** 가장 최근 실행에서 어긋난 종목 — 다음 실행에서 다시 본다. */
    private List<String> recheckCodes() {
        List<ExternalCrosscheckRun> recent = runRepository.findTop10ByOrderByStartedAtDesc();
        if (recent.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (CrosscheckRules.CloseOutcome o : closeOutcomesOf(recent.get(0).getId())) {
            if (CrosscheckRules.MISMATCH.equals(o.status())) out.add(o.stockCode());
        }
        return out;
    }

    private List<String> candidates() {
        return snapshotRepository.findLatestSnapshot().stream()
                .map(com.myplatform.backend.entity.RecommendationSnapshot::getStockCode).toList();
    }

    /** 활성 가상계좌의 보유(읽기 전용 조회 — 계좌를 만들지 않는다) + 봇 포지션. 옛 계좌의 죽은 포지션은 읽지 않는다. */
    private List<String> holdings() {
        List<String> out = new ArrayList<>();
        virtualAccountRepository.findFirstByIsActiveTrueOrderByIdDesc().ifPresent(account ->
                virtualPortfolioRepository.findByAccountId(account.getId()).forEach(p -> {
                    if (p.getQuantity() != null && p.getQuantity() > 0) out.add(p.getStockCode());
                }));
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

    /** 표본 출처 조회 — 실패하면 그 몫 없이 진행하고 실행 메모에 남긴다(로그만으로 숨기지 않는다). */
    private List<String> safe(String label, java.util.function.Supplier<List<String>> source, List<String> failures) {
        try {
            List<String> v = source.get();
            return v == null ? List.of() : v;
        } catch (Exception e) {
            log.warn("[외부 대조] 표본 출처 '{}' 조회 실패 — 그 몫 없이 진행: {}", label, e.getMessage());
            failures.add(label);
            return List.of();
        }
    }

    private void pause() {
        if (pauseMillis <= 0) return;
        try {
            Thread.sleep(pauseMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // 루프가 이 표시를 보고 멈춘다
        }
    }
}
