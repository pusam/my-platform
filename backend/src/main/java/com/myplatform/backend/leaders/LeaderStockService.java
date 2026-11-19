package com.myplatform.backend.leaders;

import com.myplatform.backend.entity.InvestorStockDaily;
import com.myplatform.backend.repository.InvestorStockDailyRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.service.KoreaInvestmentService;
import com.myplatform.backend.service.MarketCalendarService;
import com.myplatform.backend.service.SchedulerLockService;
import com.myplatform.backend.service.SignalD3EvaluationService;
import com.myplatform.backend.service.StockMasterService;
import com.myplatform.backend.service.StockStatusService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 주도주 목록(2026-11-19) — 시가총액 1조 원 이상 종목을 20거래일 평균 거래대금 순으로 세워 발굴 탭에 보인다.
 *
 * <p><b>표시 전용</b>: 추천 점수·봇·시그널 기록 어디에도 들어가지 않는다({@code LeaderIsolationTest}). '사라'는 신호가 아니고
 * 성과를 재 본 적도 없다. 규칙은 {@link LeaderRules}.
 *
 * <p><b>언제 계산하나</b>: 매일 08:20:40(보완 10:40:40). KIS 일봉의 그날 값은 저녁 내내 바뀌고 다음 날 08:00 에야 KRX 종가로 굳는다
 * ({@link SignalD3EvaluationService#lastFinalTradingDay}) — 그래서 굳은 마지막 거래일(기준일)까지로만 계산한다. 저녁에 보면 목록은
 * 전 거래일 종가 기준이다(20거래일 평균이라 하루 차이로 순위가 거의 달라지지 않는다). 주말에도 도는 까닭은 금요일 값이 토요일 아침에
 * 굳기 때문이다. 행의 날짜는 '오늘'이 아니라 달력이 정한 기준일이고, 그 기준일 목록이 이미 있으면 KIS 를 부르지 않는다 —
 * 휴장일·주말에 가짜 날짜 행이 생기지 않는다. 못 낸 회차는 다음 회차, 부팅 따라잡기(부팅 4분·26분 뒤)가 다시 시도한다.
 *
 * <p><b>일봉은 저장하지 않는다</b>: {@code stock_price_history} 는 추천 기술 점수·낙폭과대 트랙·거래정지 감지의 입력이라 대형주 봉을
 * 거기에 넣으면 추천 입력이 바뀐다. 받은 봉은 이 계산에만 쓰고 결과 한 줄만 {@code leader_stock_daily} 에 남긴다.
 */
@Service
@Slf4j
public class LeaderStockService {

    /** KIS 호출 간격(ms) — 다른 일괄 일봉 수집({@code DailyBarRefreshService})과 같은 예산. 대상 280여 종목이면 약 2분. */
    static final long RATE_LIMIT_MS = 450L;
    /** 일봉을 받는 달력일 창 — 60거래일 전 봉까지(61봉)에 넉넉하고 KIS 한 번 응답(약 100봉) 안이다. */
    static final int BAR_WINDOW_CALENDAR_DAYS = 110;
    /** 시가총액을 찾는 달력일 창 — 그 안의 가장 새 재무 일별 행. */
    static final int MARKET_CAP_LOOKBACK_DAYS = 10;
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    /** 부팅 직후 거래정지 게이트를 기다리는 상한 — AI 워밍과 같은 3분. */
    static final int GATE_WAIT_POLLS = 90;
    /** 스케줄러 잠금 — 한 회차는 게이트 대기(최대 3분) + 일봉 280여 건(약 3분)이라 수명 20분이면 넉넉하다. */
    static final String LOCK_NAME = "leaders.refresh";
    static final Duration LOCK_TTL = Duration.ofMinutes(20);
    /** 부팅 따라잡기 — 첫 회차는 부팅 4분 뒤, 둘째는 남은 잠금의 수명({@link #LOCK_TTL})보다 뒤. */
    static final long BOOT_CATCH_UP_MINUTES = 4;
    static final long BOOT_RETRY_MINUTES = 26;

    private final KoreaInvestmentService koreaInvestmentService;
    private final StockFinancialDataRepository financialRepository;
    private final InvestorStockDailyRepository investorStockDailyRepository;
    private final StockMasterService stockMasterService;
    private final StockStatusService stockStatusService;
    private final MarketCalendarService marketCalendar;
    private final SchedulerLockService schedulerLockService;
    private final LeaderStockDailyRepository repository;
    private final LeaderStockStore store;
    private final Clock clock;
    private final boolean enabled;

    /** 호출 사이 쉬는 시간(ms). 테스트는 0. */
    long pauseMillis = RATE_LIMIT_MS;
    /** 거래정지 게이트 확인 간격(ms). 테스트는 0. */
    long gatePollMillis = 2000;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public LeaderStockService(KoreaInvestmentService koreaInvestmentService,
                              StockFinancialDataRepository financialRepository,
                              InvestorStockDailyRepository investorStockDailyRepository,
                              StockMasterService stockMasterService,
                              StockStatusService stockStatusService,
                              MarketCalendarService marketCalendar,
                              SchedulerLockService schedulerLockService,
                              LeaderStockDailyRepository repository,
                              LeaderStockStore store,
                              Clock clock,
                              @Value("${leaders.refresh.enabled:true}") boolean enabled) {
        this.koreaInvestmentService = koreaInvestmentService;
        this.financialRepository = financialRepository;
        this.investorStockDailyRepository = investorStockDailyRepository;
        this.stockMasterService = stockMasterService;
        this.stockStatusService = stockStatusService;
        this.marketCalendar = marketCalendar;
        this.schedulerLockService = schedulerLockService;
        this.repository = repository;
        this.store = store;
        this.clock = clock;
        this.enabled = enabled;
    }

    /** 꺼져 있으면 부팅 때 한 번 알린다 — 조용히 안 도는 조합을 만들지 않는다(§4b). */
    @PostConstruct
    void announceState() {
        if (!enabled) {
            log.warn("[주도주] 목록 갱신이 꺼져 있다 — 발굴 탭 주도주 목록은 마지막으로 계산한 날짜에 멈춘다. 켜려면 LEADERS_REFRESH_ENABLED=true");
        }
    }

    public enum Outcome { PUBLISHED, ALREADY_DONE, NOT_PUBLISHED, SKIPPED, FAILED }

    /**
     * 매일 08:20:40·10:40:40 KST — 초 필드는 :00 을 피한다(§4b). 기준일 목록이 이미 있으면 아무것도 부르지 않는다(DB 조회 한 번).
     *
     * <p>둘째 회차(10:40)는 평소엔 할 일이 없다. 필요한 날은 ① 08:20 회차가 목록을 내지 못한 날, ② 장이 늦게 닫힌 날(수능일)의 다음
     * 아침 — 값이 굳는 시각이 마감 + 16시간 20분이라 그날은 09:00 에야 기준일이 넘어가 08:20 회차가 '이미 있다'로 지나간다.
     */
    @Scheduled(scheduler = "batchScheduler", cron = "40 20 8 * * *", zone = "Asia/Seoul")
    @Scheduled(scheduler = "batchScheduler", cron = "40 40 10 * * *", zone = "Asia/Seoul")
    public void scheduledRefresh() {
        if (!enabled) {
            log.debug("[주도주] 꺼져 있음 — 건너뜀");
            return;
        }
        refreshIfMissing("CRON");
    }

    /**
     * 부팅 따라잡기 — 부팅 4분 뒤 한 번, 26분 뒤 한 번 더. 기준일 목록이 없으면(첫 배포·아침 회차를 놓친 날) 계산하고, 있으면 아무것도
     * 하지 않는다(DB 조회 한 번). 부팅 직후가 아닌 까닭: 종목 마스터·거래정지 게이트·다른 부팅 워밍이 끝난 뒤에 돌게 하고, 공용 비동기
     * 풀(4개)을 몇 분씩 잡지 않게 배치 풀에서 돈다.
     *
     * <p>둘째 회차가 있는 까닭(2026-11-19): 회차 도중 재시작으로 죽으면 잠금을 풀지 못한다 — 새로 뜬 서버의 첫 따라잡기가 그 잠금에
     * 막히면 다음 날 08:20 까지 목록이 없다(첫 배포면 화면이 통째로 빈다). 남은 잠금은 새 서버가 뜨기 전에 잡힌 것이라 부팅 뒤
     * {@link #LOCK_TTL} 보다 늦게 도는 둘째 회차 때는 반드시 풀려 있다. 첫 회차가 KIS 장애로 목록을 내지 못했을 때도 한 번 더 본다.
     */
    @Scheduled(scheduler = "batchScheduler", initialDelay = BOOT_CATCH_UP_MINUTES, timeUnit = TimeUnit.MINUTES)
    @Scheduled(scheduler = "batchScheduler", initialDelay = BOOT_RETRY_MINUTES, timeUnit = TimeUnit.MINUTES)
    public void catchUpOnStartup() {
        if (!enabled) return;
        refreshIfMissing("BOOT");
    }

    /** 기준일(값이 굳은 마지막 거래일) 목록이 없을 때만 계산한다. 예외는 삼키고 결과로 말한다 — 크론·부팅을 죽이지 않는다. */
    Outcome refreshIfMissing(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.info("[주도주] 이미 도는 중 — {} 실행 건너뜀", trigger);
            return Outcome.SKIPPED;
        }
        boolean locked = false;
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDate basis = SignalD3EvaluationService.lastFinalTradingDay(now, marketCalendar);
            if (repository.existsByBasisDate(basis)) {
                log.debug("[주도주] {} 기준 목록이 이미 있다 — {} 실행 건너뜀", basis, trigger);
                return Outcome.ALREADY_DONE;
            }
            locked = schedulerLockService.tryLock(LOCK_NAME, LOCK_TTL);
            if (!locked) {
                // 드문 일이라 INFO — 다른 인스턴스가 돌고 있거나, 직전 회차가 잠금을 풀지 못하고 죽었다(회차 도중 재시작)
                log.info("[주도주] 잠금이 잡혀 있어 {} 실행 건너뜀 — {} 종가 기준 목록은 아직 없다. 잠금은 길어야 {}분 뒤 풀리고 "
                                + "부팅 따라잡기 둘째 회차(부팅 {}분 뒤)나 다음 회차(08:20·10:40)가 다시 시도한다",
                        trigger, basis, LOCK_TTL.toMinutes(), BOOT_RETRY_MINUTES);
                return Outcome.SKIPPED;
            }
            awaitHaltGate();
            return compute(basis, now, trigger);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[주도주] 중단됨 — {} 실행은 목록을 내지 않았다", trigger);
            return Outcome.FAILED;
        } catch (Exception e) {
            log.error("[주도주] 목록 계산 실패({}): {}", trigger, e.getMessage(), e);
            return Outcome.FAILED;
        } finally {
            // 회차가 끝나면 잠금을 푼다(2026-11-19) — 목록을 내지 못한 회차 뒤에 다시 뜬 서버의 따라잡기가 남은 잠금에 막히지 않게.
            // 잡지 못한 잠금은 풀지 않는다(남의 것). 회차 도중 죽으면 못 풀지만 LOCK_TTL 뒤 저절로 풀리고 따라잡기 둘째 회차가 받는다.
            if (locked) schedulerLockService.release(LOCK_NAME);
            running.set(false);
        }
    }

    /** 거래정지 게이트는 메모리라 재시작 직후엔 비어 있다 — 최대 3분 기다리고, 넘으면 알리고 진행한다. */
    private void awaitHaltGate() throws InterruptedException {
        for (int i = 0; i < GATE_WAIT_POLLS && !stockStatusService.isHaltGateLoaded(); i++) {
            Thread.sleep(gatePollMillis);
        }
        if (!stockStatusService.isHaltGateLoaded()) {
            // 같은 기준일 목록은 다시 계산하지 않는다 — 섞인 정지 종목은 이 목록에 남고 다음 기준일 목록부터 빠진다
            log.warn("[주도주] 거래정지 게이트가 채워지지 않았다 — 게이트 없이 계산한다(정지 종목이 이 기준일 목록에 섞일 수 있고, "
                    + "다음 기준일 목록부터 빠진다)");
        }
    }

    private record Target(String code, String name, String market, BigDecimal capEok) {}

    private Outcome compute(LocalDate basis, LocalDateTime now, String trigger) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        List<Target> universe = universe(now.toLocalDate());
        LocalDate day20 = marketCalendar.minusTradingDays(basis, LeaderRules.SHORT_DAYS);
        LocalDate day60 = marketCalendar.minusTradingDays(basis, LeaderRules.LONG_DAYS);
        LocalDate from = basis.minusDays(BAR_WINDOW_CALENDAR_DAYS);

        List<LeaderRules.Candidate> measured = new ArrayList<>();
        int failed = 0, noBasisBar = 0, consecutiveFailures = 0;
        String abortReason = null;
        for (int i = 0; i < universe.size(); i++) {
            if (i > 0 && pauseMillis > 0) Thread.sleep(pauseMillis);
            Target t = universe.get(i);
            List<KoreaInvestmentService.OhlcvData> ohlcv = fetchBars(t.code(), from, basis);
            if (ohlcv.isEmpty()) {
                failed++;
                if (++consecutiveFailures >= LeaderRules.ABORT_AFTER_CONSECUTIVE_FAILURES) {
                    abortReason = "연속 " + consecutiveFailures + "종목 일봉을 받지 못해 멈췄다";
                    break;
                }
                continue;
            }
            consecutiveFailures = 0;
            LeaderRules.Metrics m = LeaderRules.measure(toBars(ohlcv), basis, day20, day60);
            if (m == null) {
                noBasisBar++;
                continue;
            }
            measured.add(new LeaderRules.Candidate(t.code(), t.name(), t.market(), t.capEok(), m));
        }

        if (abortReason != null || !LeaderRules.publishable(universe.size(), measured.size())) {
            log.warn("[주도주] {} 종가 기준 목록을 내지 않는다({}) — 대상 {} · 잰 종목 {} · 일봉 못 받음 {} · 기준일 봉 없음·봉 부족 {}{} "
                            + "(직전 목록을 그대로 둔다. 다음 회차(08:20·10:40)나 부팅 따라잡기(부팅 4분·26분 뒤)가 다시 시도한다)",
                    basis, trigger, universe.size(), measured.size(), failed, noBasisBar,
                    abortReason == null ? "" : " · " + abortReason);
            return Outcome.NOT_PUBLISHED;
        }

        List<LeaderRules.Candidate> ranked = LeaderRules.rank(measured);
        List<LeaderStockDaily> rows = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            LeaderRules.Candidate c = ranked.get(i);
            rows.add(LeaderStockDaily.builder()
                    .basisDate(basis).stockCode(c.stockCode()).stockName(c.stockName()).market(c.market())
                    .marketCapEok(c.marketCapEok().setScale(2, java.math.RoundingMode.HALF_UP))
                    .closePrice(c.metrics().close())
                    .avgTradedValueEok(c.metrics().avgTradedValueEok())
                    .return20d(c.metrics().return20()).return60d(c.metrics().return60())
                    .rankOrder(i + 1).createdAt(now).build());
        }
        store.replace(basis, rows);
        LeaderRules.Candidate top = ranked.get(0);
        log.info("[주도주] {} 종가 기준 목록 저장({}) — 대상 {} · 순위 {} · 일봉 못 받음 {} · 기준일 봉 없음·봉 부족 {} · 1위 {} ({}초)",
                basis, trigger, universe.size(), rows.size(), failed, noBasisBar, top.stockName(),
                (System.currentTimeMillis() - t0) / 1000);
        return Outcome.PUBLISHED;
    }

    /** 일봉 조회 — 실패·빈 응답은 빈 목록('못 받음'으로 센다). 응답은 저장하지 않는다. */
    private List<KoreaInvestmentService.OhlcvData> fetchBars(String code, LocalDate from, LocalDate to) {
        try {
            List<KoreaInvestmentService.OhlcvData> rows = koreaInvestmentService.getDailyOhlcvRange(code, from, to);
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.debug("[주도주] {} 일봉 조회 실패: {}", code, e.getMessage());
            return List.of();
        }
    }

    private static List<LeaderRules.Bar> toBars(List<KoreaInvestmentService.OhlcvData> ohlcv) {
        List<LeaderRules.Bar> bars = new ArrayList<>(ohlcv.size());
        for (KoreaInvestmentService.OhlcvData d : ohlcv) {
            if (d != null) bars.add(new LeaderRules.Bar(d.getTradeDate(), d.getClose(), d.getVolume()));
        }
        return bars;
    }

    /** 대상 — 종목별 가장 새 시가총액이 1조 원 이상이고, 마스터의 시장이 KOSPI·KOSDAQ 이고, 거래정지가 아닌 종목. 종목코드 순. */
    private List<Target> universe(LocalDate today) {
        Map<String, BigDecimal> caps = new HashMap<>();
        for (Object[] row : financialRepository.findLatestMarketCapPerStockSince(today.minusDays(MARKET_CAP_LOOKBACK_DAYS))) {
            if (row == null || row.length < 2 || row[0] == null || !(row[1] instanceof BigDecimal cap)) continue;
            caps.merge((String) row[0], cap, BigDecimal::max);   // 같은 날짜 행이 둘이면 큰 쪽(중복 행 방어)
        }
        List<Target> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : caps.entrySet()) {
            String code = e.getKey();
            String market = stockMasterService.getMarket(code);
            if (!LeaderRules.inUniverse(e.getValue(), market, stockStatusService.isActive(code))) continue;
            out.add(new Target(code, stockMasterService.getNameOrDefault(code, code), market.toUpperCase(), e.getValue()));
        }
        out.sort(Comparator.comparing(Target::code));
        return out;
    }

    // ==================== 조회 ====================

    /** 화면에 주는 한 줄. 순매수는 모르면 null(0 아님). */
    public record LeaderItem(int rank, String stockCode, String stockName, String market, BigDecimal marketCapEok,
                             BigDecimal closePrice, BigDecimal avgTradedValueEok, BigDecimal return20d, BigDecimal return60d,
                             BigDecimal netBuyEok, Integer netBuyDays) {}

    /**
     * 화면에 주는 목록.
     *
     * @param basisDate     목록의 기준일(그날 종가). 아직 한 번도 계산하지 않았으면 null
     * @param stale         기준일이 값이 굳은 마지막 거래일보다 한 거래일 넘게 뒤처졌는가(갱신이 이틀 이상 실패)
     * @param rankedCount   그 기준일에 순위를 매긴 종목 수(대상 가운데 잰 종목)
     * @param netAvailable  외국인·기관 순매수 표를 읽었는가 — false 면 항목의 순매수 칸은 '모름'이다(0 아님)
     * @param netFrom       순매수 합의 첫 날(20거래일 창의 시작)
     * @param netThrough    순매수 표에서 실제로 읽힌 마지막 날 — 창 끝(기준일)보다 앞이면 수급이 그날까지만 있다
     */
    public record LeaderList(LocalDate basisDate, boolean stale, long rankedCount, BigDecimal minMarketCapEok,
                             int windowDays, boolean netAvailable, LocalDate netFrom, LocalDate netThrough,
                             List<LeaderItem> items) {}

    /** 가장 새 목록의 위에서 {@code limit}줄. 조회 실패는 예외로 올린다(컨트롤러가 '데이터 없음'으로 말한다). */
    public LeaderList getLeaders(int limit) {
        int n = Math.max(1, Math.min(MAX_LIMIT, limit <= 0 ? DEFAULT_LIMIT : limit));
        LocalDate basis = repository.findLatestBasisDate();
        if (basis == null) {
            return new LeaderList(null, false, 0, LeaderRules.MIN_MARKET_CAP_EOK, LeaderRules.SHORT_DAYS, false, null, null, List.of());
        }
        List<LeaderStockDaily> rows = repository.findByBasisDateOrderByRankOrderAsc(basis, PageRequest.of(0, n));
        long ranked = repository.countByBasisDate(basis);
        LocalDate netFrom = marketCalendar.minusTradingDays(basis, LeaderRules.SHORT_DAYS - 1);

        Map<String, List<InvestorStockDaily>> netRows = null;
        LocalDate netThrough = null;
        try {
            List<String> codes = rows.stream().map(LeaderStockDaily::getStockCode).toList();
            netRows = new LinkedHashMap<>();
            if (!codes.isEmpty()) {
                for (InvestorStockDaily r : investorStockDailyRepository.findByStockCodeInAndTradeDateBetween(codes, netFrom, basis)) {
                    if (r == null || r.getStockCode() == null) continue;
                    netRows.computeIfAbsent(r.getStockCode(), k -> new ArrayList<>()).add(r);
                    if (r.getTradeDate() != null && (netThrough == null || r.getTradeDate().isAfter(netThrough))) {
                        netThrough = r.getTradeDate();
                    }
                }
            }
        } catch (Exception e) {
            // 순위는 보이고 순매수 칸만 '모름'으로 — 수급 표 조회 실패가 목록 전체를 지우지 않게(§4c: 0 으로 채우지 않는다)
            log.warn("[주도주] 외국인·기관 순매수 조회 실패 — 순매수 칸 없이 목록을 준다: {}", e.getMessage());
            netRows = null;
            netThrough = null;
        }

        List<LeaderItem> items = new ArrayList<>(rows.size());
        for (LeaderStockDaily r : rows) {
            LeaderRules.NetBuy net = netRows == null ? null : LeaderRules.netBuy(netRows.get(r.getStockCode()));
            items.add(new LeaderItem(r.getRankOrder(), r.getStockCode(), r.getStockName(), r.getMarket(), r.getMarketCapEok(),
                    r.getClosePrice(), r.getAvgTradedValueEok(), r.getReturn20d(), r.getReturn60d(),
                    net == null ? null : net.sumEok(), net == null ? null : net.days()));
        }
        return new LeaderList(basis, isStale(basis, LocalDateTime.now(clock)), ranked, LeaderRules.MIN_MARKET_CAP_EOK,
                LeaderRules.SHORT_DAYS, netRows != null, netFrom, netThrough, items);
    }

    /** 기준일이 값이 굳은 마지막 거래일보다 한 거래일 넘게 뒤처졌는가 — 08:00~08:20 사이의 하루 차이는 정상이다. */
    boolean isStale(LocalDate basis, LocalDateTime now) {
        LocalDate expected = SignalD3EvaluationService.lastFinalTradingDay(now, marketCalendar);
        return basis.isBefore(marketCalendar.minusTradingDays(expected, 1));
    }
}
