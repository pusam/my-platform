package com.myplatform.backend.leaders;

import com.myplatform.backend.entity.InvestorStockDaily;
import com.myplatform.backend.repository.InvestorStockDailyRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.service.KoreaInvestmentService;
import com.myplatform.backend.service.MarketCalendarService;
import com.myplatform.backend.service.SchedulerLockService;
import com.myplatform.backend.service.StockMasterService;
import com.myplatform.backend.service.StockStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 주도주 목록 수집·조회(2026-11-19). 시각은 고정한다 — 기준일이 '값이 굳은 마지막 거래일'이라 실행 시각에 따라 달라진다.
 */
class LeaderStockServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-11-19(목) 08:20:40 — 전 거래일 11/18(수)의 봉이 08:00 에 굳은 뒤. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 8, 20, 40);
    private static final LocalDate BASIS = LocalDate.of(2026, 11, 18);

    private final MarketCalendarService calendar = new MarketCalendarService();
    private KoreaInvestmentService kis;
    private StockFinancialDataRepository financials;
    private InvestorStockDailyRepository investorDaily;
    private StockMasterService master;
    private StockStatusService status;
    private SchedulerLockService lock;
    private LeaderStockDailyRepository repository;
    private LeaderStockStore store;

    @BeforeEach
    void setUp() {
        kis = mock(KoreaInvestmentService.class);
        financials = mock(StockFinancialDataRepository.class);
        investorDaily = mock(InvestorStockDailyRepository.class);
        master = mock(StockMasterService.class);
        status = mock(StockStatusService.class);
        lock = mock(SchedulerLockService.class);
        repository = mock(LeaderStockDailyRepository.class);
        store = mock(LeaderStockStore.class);
        when(lock.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(status.isHaltGateLoaded()).thenReturn(true);
        when(status.isActive(anyString())).thenReturn(true);
        when(master.getMarket(anyString())).thenReturn("KOSPI");
        when(master.getNameOrDefault(anyString(), anyString())).thenAnswer(inv -> "종목" + inv.getArgument(0));
    }

    private LeaderStockService service(boolean enabled, LocalDateTime now) {
        LeaderStockService s = new LeaderStockService(kis, financials, investorDaily, master, status, calendar, lock,
                repository, store, Clock.fixed(now.atZone(KST).toInstant(), KST), enabled);
        s.pauseMillis = 0;
        s.gatePollMillis = 0;
        return s;
    }

    private static String code(int i) {
        return String.format("%06d", 100000 + i * 10);
    }

    /** 대상 n종목(시가총액 2조~) — 재무 일별 행의 [종목코드, 시가총액(억)]. */
    private void universe(int n, Object[]... extra) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= n; i++) rows.add(new Object[]{code(i), new BigDecimal(20000 + i)});
        rows.addAll(List.of(extra));
        when(financials.findLatestMarketCapPerStockSince(any(LocalDate.class))).thenReturn(rows);
    }

    /**
     * 기준일까지 거래일 61개의 봉 — k 거래일 전 봉의 종가는 100 + k(기준일 100 · 20거래일 전 120 · 60거래일 전 160), 거래량은 종목마다
     * volumeMillions 백만 주. 종가가 날마다 달라야 견주는 날짜가 하루만 어긋나도 값이 달라진다(전부 100 이면 어느 날과 견줘도 0% 다).
     * 20거래일 평균 거래대금 = 최근 20봉(k = 0~19, 평균 종가 109.5) → volumeMillions × 1.095 억 원.
     */
    private List<KoreaInvestmentService.OhlcvData> bars(int volumeMillions) {
        List<KoreaInvestmentService.OhlcvData> out = new ArrayList<>();
        for (int k = 0; k <= 60; k++) {
            BigDecimal price = BigDecimal.valueOf(100 + k);
            KoreaInvestmentService.OhlcvData d = new KoreaInvestmentService.OhlcvData(price, price, price, price,
                    BigDecimal.valueOf(volumeMillions * 1_000_000L));
            d.setTradeDate(calendar.minusTradingDays(BASIS, k));
            out.add(d);
        }
        return out;
    }

    @Test
    @DisplayName("기준일은 값이 굳은 마지막 거래일(전 거래일)이고, 일봉도 그날까지만 받는다 — 순위는 평균 거래대금 큰 순으로 통째 저장")
    @SuppressWarnings("unchecked")
    void publishesRankingForLastFinalTradingDay() {
        universe(60,
                new Object[]{"005935", new BigDecimal("1267747")},    // 우선주 — 마스터에 시장 없음
                new Object[]{"900010", new BigDecimal("9000")},       // 1조 미만
                new Object[]{"900020", new BigDecimal("50000")});     // 거래정지
        when(master.getMarket("005935")).thenReturn(null);
        when(status.isActive("900020")).thenReturn(false);
        for (int i = 1; i <= 60; i++) {
            when(kis.getDailyOhlcvRange(eq(code(i)), any(LocalDate.class), any(LocalDate.class))).thenReturn(bars(i));
        }

        LeaderStockService.Outcome outcome = service(true, NOW).refreshIfMissing("CRON");

        assertThat(outcome).isEqualTo(LeaderStockService.Outcome.PUBLISHED);
        ArgumentCaptor<List<LeaderStockDaily>> rows = ArgumentCaptor.forClass(List.class);
        verify(store).replace(eq(BASIS), rows.capture());
        List<LeaderStockDaily> saved = rows.getValue();
        assertThat(saved).hasSize(60);
        assertThat(saved.get(0).getStockCode()).isEqualTo(code(60));           // 거래량이 가장 큰 종목
        assertThat(saved.get(0).getRankOrder()).isEqualTo(1);
        assertThat(saved.get(0).getClosePrice()).isEqualByComparingTo("100");               // 기준일 봉의 종가
        // 평균은 최근 20봉(기준일 포함)만 — 21봉이면 66.00, 19봉이면 65.40 이 된다
        assertThat(saved.get(0).getAvgTradedValueEok()).isEqualByComparingTo("65.70");
        // 등락은 달력이 정한 20·60거래일 전 봉과 — 하루 어긋나면 −15.97·−17.36 / −37.11 이 된다
        assertThat(saved.get(0).getReturn20d()).isEqualByComparingTo("-16.67");             // 100 ÷ 120 − 1
        assertThat(saved.get(0).getReturn60d()).isEqualByComparingTo("-37.50");             // 100 ÷ 160 − 1
        assertThat(saved.get(59).getAvgTradedValueEok()).isEqualByComparingTo("1.10");      // 1 × 1.095 → 반올림
        assertThat(saved.get(0).getStockName()).isEqualTo("종목" + code(60));
        assertThat(saved.get(59).getStockCode()).isEqualTo(code(1));
        assertThat(saved.get(59).getRankOrder()).isEqualTo(60);
        assertThat(saved).allSatisfy(r -> assertThat(r.getBasisDate()).isEqualTo(BASIS));

        // 일봉 창의 끝은 '오늘'이 아니라 기준일 — 값이 굳지 않은 날을 받지 않는다
        verify(kis).getDailyOhlcvRange(eq(code(1)), eq(BASIS.minusDays(LeaderStockService.BAR_WINDOW_CALENDAR_DAYS)), eq(BASIS));
        verify(kis, never()).getDailyOhlcvRange(eq("005935"), any(), any());
        verify(kis, never()).getDailyOhlcvRange(eq("900010"), any(), any());
        verify(kis, never()).getDailyOhlcvRange(eq("900020"), any(), any());
    }

    @Test
    @DisplayName("그 기준일 목록이 이미 있으면 KIS 를 부르지 않는다 — 주말·휴장일·재시작에 같은 계산을 되풀이하지 않는다")
    void doesNothingWhenBasisAlreadyPublished() {
        when(repository.existsByBasisDate(BASIS)).thenReturn(true);

        assertThat(service(true, NOW).refreshIfMissing("CRON")).isEqualTo(LeaderStockService.Outcome.ALREADY_DONE);

        verifyNoInteractions(kis, store, financials);
    }

    @Test
    @DisplayName("토요일 아침이면 기준일은 금요일 — 주말에도 한 번 돌아 금요일 종가 기준 목록을 낸다")
    void saturdayRunUsesFriday() {
        LocalDateTime saturday = LocalDateTime.of(2026, 11, 21, 8, 20, 40);
        when(repository.existsByBasisDate(LocalDate.of(2026, 11, 20))).thenReturn(true);

        assertThat(service(true, saturday).refreshIfMissing("CRON")).isEqualTo(LeaderStockService.Outcome.ALREADY_DONE);

        verify(repository).existsByBasisDate(LocalDate.of(2026, 11, 20));
    }

    @Test
    @DisplayName("일봉을 못 받은 종목이 대상의 10% 를 넘으면 저장하지 않는다 — 일부로 만든 순위를 내지 않고 직전 목록을 둔다")
    void doesNotPublishPartialRanking() {
        universe(60);
        for (int i = 1; i <= 60; i++) {
            // 7종목(11.7%)을 띄엄띄엄 못 받는다 — 연속 실패 중단에는 걸리지 않는다
            when(kis.getDailyOhlcvRange(eq(code(i)), any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(i % 8 == 0 ? List.of() : bars(i));
        }

        assertThat(service(true, NOW).refreshIfMissing("CRON")).isEqualTo(LeaderStockService.Outcome.NOT_PUBLISHED);

        verify(store, never()).replace(any(), any());
    }

    @Test
    @DisplayName("연속 30종목 일봉을 못 받으면 그 회차를 멈춘다 — 장애 중인 KIS 를 끝까지 두드리지 않는다")
    void abortsAfterConsecutiveFailures() {
        universe(60);
        when(kis.getDailyOhlcvRange(anyString(), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());

        assertThat(service(true, NOW).refreshIfMissing("CRON")).isEqualTo(LeaderStockService.Outcome.NOT_PUBLISHED);

        verify(kis, times(LeaderRules.ABORT_AFTER_CONSECUTIVE_FAILURES)).getDailyOhlcvRange(anyString(), any(), any());
        verify(store, never()).replace(any(), any());
    }

    @Test
    @DisplayName("대상이 비정상적으로 적으면(시가총액·마스터 입력 이상) 저장하지 않는다")
    void doesNotPublishWhenUniverseTooSmall() {
        universe(10);
        when(kis.getDailyOhlcvRange(anyString(), any(LocalDate.class), any(LocalDate.class))).thenReturn(bars(5));

        assertThat(service(true, NOW).refreshIfMissing("BOOT")).isEqualTo(LeaderStockService.Outcome.NOT_PUBLISHED);

        verify(store, never()).replace(any(), any());
    }

    @Test
    @DisplayName("꺼져 있으면 크론·부팅 따라잡기 모두 아무것도 하지 않는다")
    void disabledDoesNothing() {
        LeaderStockService s = service(false, NOW);

        s.scheduledRefresh();
        s.catchUpOnStartup();

        verifyNoInteractions(kis, store, repository, financials);
    }

    @Test
    @DisplayName("계산 중 예외는 삼키고 FAILED 로 말한다 — 크론 스레드를 죽이지 않고 다음 회차가 다시 시도한다")
    void failureIsReportedNotThrown() {
        when(financials.findLatestMarketCapPerStockSince(any(LocalDate.class))).thenThrow(new IllegalStateException("db down"));

        assertThat(service(true, NOW).refreshIfMissing("CRON")).isEqualTo(LeaderStockService.Outcome.FAILED);

        verify(store, never()).replace(any(), any());
    }

    // ==================== 잠금·부팅 따라잡기 ====================

    @Test
    @DisplayName("재현: 회차가 끝나면 잠금을 푼다 — 목록을 내지 못한 회차 뒤 20분 안에 다시 뜬 서버의 따라잡기가 남은 잠금에 막혔다")
    void releasesLockWhenRunEnds() {
        universe(10);     // 대상이 너무 적어 목록을 내지 않는 회차
        when(kis.getDailyOhlcvRange(anyString(), any(LocalDate.class), any(LocalDate.class))).thenReturn(bars(5));

        assertThat(service(true, NOW).refreshIfMissing("BOOT")).isEqualTo(LeaderStockService.Outcome.NOT_PUBLISHED);

        verify(lock).tryLock(eq("leaders.refresh"), any(Duration.class));
        verify(lock).release("leaders.refresh");
    }

    @Test
    @DisplayName("목록을 낸 회차·예외로 끝난 회차도 잠금을 푼다")
    void releasesLockOnPublishAndOnFailure() {
        universe(60);
        when(kis.getDailyOhlcvRange(anyString(), any(LocalDate.class), any(LocalDate.class))).thenReturn(bars(5));
        assertThat(service(true, NOW).refreshIfMissing("CRON")).isEqualTo(LeaderStockService.Outcome.PUBLISHED);
        verify(lock, times(1)).release("leaders.refresh");

        when(financials.findLatestMarketCapPerStockSince(any(LocalDate.class))).thenThrow(new IllegalStateException("db down"));
        assertThat(service(true, NOW).refreshIfMissing("CRON")).isEqualTo(LeaderStockService.Outcome.FAILED);
        verify(lock, times(2)).release("leaders.refresh");
    }

    @Test
    @DisplayName("잠금을 얻지 못하면 계산하지 않고, 잡지 못한 잠금을 풀지도 않는다(남의 것)")
    void doesNotReleaseLockItDidNotAcquire() {
        when(lock.tryLock(anyString(), any(Duration.class))).thenReturn(false);

        assertThat(service(true, NOW).refreshIfMissing("BOOT")).isEqualTo(LeaderStockService.Outcome.SKIPPED);

        verify(lock, never()).release(anyString());
        verifyNoInteractions(kis, store, financials);
    }

    @Test
    @DisplayName("기준일 목록이 이미 있으면 잠금을 잡지도 풀지도 않는다")
    void alreadyDoneDoesNotTouchLock() {
        when(repository.existsByBasisDate(BASIS)).thenReturn(true);

        assertThat(service(true, NOW).refreshIfMissing("BOOT")).isEqualTo(LeaderStockService.Outcome.ALREADY_DONE);

        verifyNoInteractions(lock);
    }

    @Test
    @DisplayName("재현: 부팅 따라잡기는 두 번 — 회차 도중 재시작으로 죽으면 잠금(20분)이 남아 새 서버의 첫 따라잡기가 막힌다. 둘째는 그 잠금이 풀린 뒤에 돈다")
    void bootCatchUpRetriesAfterLeftoverLockExpires() throws Exception {
        Scheduled[] passes = LeaderStockService.class.getMethod("catchUpOnStartup").getAnnotationsByType(Scheduled.class);

        assertThat(passes).hasSize(2);
        assertThat(passes).allSatisfy(p -> {
            assertThat(p.scheduler()).isEqualTo("batchScheduler");
            assertThat(p.timeUnit()).isEqualTo(TimeUnit.MINUTES);
            // 한 번만 도는 예약 — 주기가 붙으면 장중에 아무 때나 KIS 를 부른다
            assertThat(p.cron()).isEmpty();
            assertThat(p.fixedDelay()).isEqualTo(-1);
            assertThat(p.fixedRate()).isEqualTo(-1);
            assertThat(p.fixedDelayString()).isEmpty();
            assertThat(p.fixedRateString()).isEmpty();
        });
        long first = Math.min(passes[0].initialDelay(), passes[1].initialDelay());
        long retry = Math.max(passes[0].initialDelay(), passes[1].initialDelay());
        assertThat(first).isEqualTo(4);
        // 남은 잠금은 새 서버가 뜨기 전에 잡힌 것이라, 부팅 뒤 잠금 수명(20분)보다 늦게 돌면 반드시 풀려 있다
        assertThat(retry).isGreaterThan(20);
    }

    // ==================== 조회 ====================

    private static LeaderStockDaily row(int rank, String code) {
        return LeaderStockDaily.builder().basisDate(BASIS).stockCode(code).stockName("종목" + code).market("KOSPI")
                .marketCapEok(new BigDecimal("8000000.00")).closePrice(new BigDecimal("1098000.00"))
                .avgTradedValueEok(new BigDecimal("49484.12")).return20d(new BigDecimal("-10.20")).return60d(null)
                .rankOrder(rank).createdAt(NOW).build();
    }

    private static InvestorStockDaily net(String code, LocalDate date, String foreign, String institution) {
        return InvestorStockDaily.builder().stockCode(code).tradeDate(date)
                .foreignNet(foreign == null ? null : new BigDecimal(foreign))
                .institutionNet(institution == null ? null : new BigDecimal(institution)).build();
    }

    @Test
    @DisplayName("아직 한 번도 계산하지 않았으면 기준일 없이 빈 목록 — '계산 전'이지 조회 실패가 아니다")
    void notComputedYet() {
        when(repository.findLatestBasisDate()).thenReturn(null);

        LeaderStockService.LeaderList list = service(true, NOW).getLeaders(20);

        assertThat(list.basisDate()).isNull();
        assertThat(list.items()).isEmpty();
        verifyNoInteractions(investorDaily);
    }

    @Test
    @DisplayName("순매수는 확정치 표에서 읽을 때 더한다 — 창은 기준일까지 20거래일, 표에 없는 종목은 0 이 아니라 모름")
    void attachesNetBuyFromConfirmedTable() {
        when(repository.findLatestBasisDate()).thenReturn(BASIS);
        when(repository.findByBasisDateOrderByRankOrderAsc(eq(BASIS), any(Pageable.class)))
                .thenReturn(List.of(row(1, "000660"), row(2, "005930")));
        when(repository.countByBasisDate(BASIS)).thenReturn(280L);
        LocalDate netFrom = calendar.minusTradingDays(BASIS, 19);
        when(investorDaily.findByStockCodeInAndTradeDateBetween(anyCollection(), eq(netFrom), eq(BASIS))).thenReturn(List.of(
                net("000660", BASIS, "-100.5", "20.25"),
                net("000660", BASIS.minusDays(1), "-10", "-5"),
                net("000660", BASIS.minusDays(2), null, "3")));     // 외국인을 모르는 날 — 세지 않는다

        LeaderStockService.LeaderList list = service(true, NOW).getLeaders(20);

        assertThat(list.basisDate()).isEqualTo(BASIS);
        assertThat(list.stale()).isFalse();
        assertThat(list.rankedCount()).isEqualTo(280);
        assertThat(list.netAvailable()).isTrue();
        assertThat(list.netFrom()).isEqualTo(netFrom);
        assertThat(list.netThrough()).isEqualTo(BASIS);
        assertThat(list.items()).hasSize(2);
        LeaderStockService.LeaderItem hynix = list.items().get(0);
        assertThat(hynix.stockCode()).isEqualTo("000660");
        assertThat(hynix.netBuyEok()).isEqualByComparingTo("-95.25");
        assertThat(hynix.netBuyDays()).isEqualTo(2);
        assertThat(hynix.return60d()).isNull();
        LeaderStockService.LeaderItem samsung = list.items().get(1);
        assertThat(samsung.netBuyEok()).isNull();        // 확정치 표에 행이 없다
        assertThat(samsung.netBuyDays()).isNull();
    }

    @Test
    @DisplayName("수급 표 조회가 실패해도 순위는 준다 — 순매수 칸만 모름이고 netAvailable=false")
    void netLookupFailureKeepsRanking() {
        when(repository.findLatestBasisDate()).thenReturn(BASIS);
        when(repository.findByBasisDateOrderByRankOrderAsc(eq(BASIS), any(Pageable.class))).thenReturn(List.of(row(1, "000660")));
        when(investorDaily.findByStockCodeInAndTradeDateBetween(anyCollection(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        LeaderStockService.LeaderList list = service(true, NOW).getLeaders(20);

        assertThat(list.items()).hasSize(1);
        assertThat(list.netAvailable()).isFalse();
        assertThat(list.items().get(0).netBuyEok()).isNull();
    }

    @Test
    @DisplayName("줄 수는 1~50 으로 자른다 — 0 이하는 기본 20")
    void clampsLimit() {
        when(repository.findLatestBasisDate()).thenReturn(BASIS);
        when(repository.findByBasisDateOrderByRankOrderAsc(eq(BASIS), any(Pageable.class))).thenReturn(List.of());
        LeaderStockService s = service(true, NOW);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);

        s.getLeaders(500);
        s.getLeaders(0);
        s.getLeaders(5);

        verify(repository, times(3)).findByBasisDateOrderByRankOrderAsc(eq(BASIS), page.capture());
        assertThat(page.getAllValues()).extracting(Pageable::getPageSize).containsExactly(50, 20, 5);
    }

    @Test
    @DisplayName("갱신 지연 — 기준일이 한 거래일 뒤처진 것은 정상(08:00~08:20), 두 거래일 넘으면 지연")
    void staleOnlyWhenMoreThanOneTradingDayBehind() {
        LeaderStockService s = service(true, NOW);

        assertThat(s.isStale(BASIS, NOW)).isFalse();                                   // 11/18 — 최신
        assertThat(s.isStale(LocalDate.of(2026, 11, 17), NOW)).isFalse();              // 한 거래일 뒤
        assertThat(s.isStale(LocalDate.of(2026, 11, 16), NOW)).isTrue();               // 두 거래일 뒤
    }
}
