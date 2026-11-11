package com.myplatform.backend.crosscheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.entity.VirtualAccount;
import com.myplatform.backend.entity.VirtualPortfolio;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 외부 대조 실행 — 우리 값은 읽기만 하고, 브라우저를 흉내 내는 헤더를 붙이지 않고, 실패는 '대조 못 함'으로 남고, 연속 실패면 멈춘다.
 */
class ExternalCrosscheckServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);
    private static final LocalDate D = LocalDate.of(2026, 11, 10);
    private static final LocalDate D1 = LocalDate.of(2026, 11, 9);

    private final RestTemplate rest = new RestTemplate();
    private MockRestServiceServer server;
    private final ExternalCrosscheckRunRepository runRepo = mock(ExternalCrosscheckRunRepository.class);
    private final ExternalCrosscheckRowRepository rowRepo = mock(ExternalCrosscheckRowRepository.class);
    private final StockPriceHistoryRepository history = mock(StockPriceHistoryRepository.class);
    private final StockFinancialDataRepository financial = mock(StockFinancialDataRepository.class);
    private final RecommendationSnapshotRepository snapshots = mock(RecommendationSnapshotRepository.class);
    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
    private final VirtualPortfolioRepository portfolio = mock(VirtualPortfolioRepository.class);
    private final BotTradingPositionRepository bot = mock(BotTradingPositionRepository.class);
    private final StockWatchlistRepository watch = mock(StockWatchlistRepository.class);
    private final StockStatusService status = mock(StockStatusService.class);
    private final MarketCalendarService calendar = mock(MarketCalendarService.class);
    private final SchedulerLockService lock = mock(SchedulerLockService.class);

    private final List<ExternalCrosscheckRow> rows = new ArrayList<>();
    private final Map<String, List<StockPriceHistory>> bars = new HashMap<>();

    static ExternalCrosscheckService build(RestTemplate rest, ExternalCrosscheckRunRepository runRepo,
                                           ExternalCrosscheckRowRepository rowRepo, StockPriceHistoryRepository history,
                                           StockFinancialDataRepository financial, RecommendationSnapshotRepository snapshots,
                                           VirtualAccountRepository accounts, VirtualPortfolioRepository portfolio,
                                           BotTradingPositionRepository bot, StockWatchlistRepository watch,
                                           StockStatusService status, MarketCalendarService calendar, SchedulerLockService lock,
                                           LocalTime at, boolean enabled) {
        Clock clock = Clock.fixed(LocalDateTime.of(TODAY, at).atZone(KST).toInstant(), KST);
        ExternalCrosscheckService s = new ExternalCrosscheckService(rest, new ObjectMapper(), runRepo, rowRepo, history,
                financial, snapshots, accounts, portfolio, bot, watch, status, calendar, lock, clock, enabled);
        s.pauseMillis = 0;
        return s;
    }

    private ExternalCrosscheckService service(LocalTime at) {
        return service(at, true);
    }

    private ExternalCrosscheckService service(LocalTime at, boolean enabled) {
        return build(rest, runRepo, rowRepo, history, financial, snapshots, accounts, portfolio, bot, watch, status,
                calendar, lock, at, enabled);
    }

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();   // 무작위 표본 순서와 무관하게
        when(calendar.isMarketClosed(any(LocalDate.class))).thenReturn(false);
        when(calendar.isMarketClosed()).thenReturn(false);
        when(calendar.marketOpenOf(any())).thenReturn(LocalTime.of(9, 0));
        when(calendar.marketCloseOf(any())).thenReturn(LocalTime.of(15, 40));
        when(calendar.minusTradingDays(eq(TODAY), eq(1))).thenReturn(D);
        when(status.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.ACTIVE);
        when(lock.tryLock(anyString(), any())).thenReturn(true);
        when(runRepo.save(any())).thenAnswer(inv -> {
            ExternalCrosscheckRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId(7L);
            return r;
        });
        when(rowRepo.save(any())).thenAnswer(inv -> {
            ExternalCrosscheckRow r = inv.getArgument(0);
            if (!rows.contains(r)) rows.add(r);
            return r;
        });
        when(history.findByStockCodeOrderByTradeDateDesc(anyString(), any(Pageable.class)))
                .thenAnswer(inv -> bars.getOrDefault(inv.<String>getArgument(0), List.of()));
        when(history.findStockCodesByTradeDate(D)).thenAnswer(inv -> new ArrayList<>(bars.keySet()));
        when(financial.findTop10ByStockCodeOrderByReportDateDesc(anyString())).thenReturn(List.of());
        when(financial.findLatestMarketCaps()).thenReturn(List.of());
    }

    private void stock(String code, String closeD, String closeD1) {
        bars.put(code, List.of(
                StockPriceHistory.builder().stockCode(code).tradeDate(D).closePrice(new BigDecimal(closeD)).build(),
                StockPriceHistory.builder().stockCode(code).tradeDate(D1).closePrice(new BigDecimal(closeD1)).build()));
    }

    private static String body(String code, String close) {
        return CrosscheckRulesTest.SAMSUNG_INTEGRATION.replace("\"189,900\"", "\"" + close + "\"")
                .replace("\"itemCode\":\"005930\"", "\"itemCode\":\"" + code + "\"");
    }

    private static String url(String code) {
        return "https://m.stock.naver.com/api/stock/" + code + "/integration";
    }

    private void respond(String code, String close) {
        server.expect(once(), requestTo(url(code))).andRespond(withSuccess(body(code, close), MediaType.APPLICATION_JSON));
    }

    private ExternalCrosscheckRow closeRow(String code) {
        return rows.stream().filter(r -> r.getStockCode().equals(code) && r.getItem().equals(CrosscheckRules.ITEM_CLOSE))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("일치·불일치·하루 늦음을 가르고, 브라우저를 흉내 내는 헤더(User-Agent·Referer·Cookie)를 붙이지 않는다")
    void classifiesAndSendsNoHeaders() {
        stock("005930", "189900", "187000");
        stock("000660", "500000", "490000");
        stock("035420", "200000", "210000");
        for (String[] c : new String[][]{{"005930", "189,900"}, {"000660", "501,000"}, {"035420", "210,000"}}) {
            server.expect(once(), requestTo(url(c[0])))
                    .andExpect(req -> {
                        assertThat(req.getHeaders().containsHeader("User-Agent")).as("User-Agent").isFalse();
                        assertThat(req.getHeaders().containsHeader("Referer")).as("Referer").isFalse();
                        assertThat(req.getHeaders().containsHeader("Cookie")).as("Cookie").isFalse();
                    })
                    .andRespond(withSuccess(body(c[0], c[1]), MediaType.APPLICATION_JSON));
        }

        ExternalCrosscheckRun run = service(LocalTime.of(10, 20, 40)).run("CRON");

        server.verify();
        assertThat(run.getStatus()).isEqualTo(ExternalCrosscheckRun.STATUS_COMPLETE);
        assertThat(run.getTargetBarDate()).isEqualTo(D);
        assertThat(run.getAttempted()).isEqualTo(3);
        assertThat(run.getMatched()).isEqualTo(1);
        assertThat(run.getMismatched()).isEqualTo(1);
        assertThat(run.getNotCompared()).isEqualTo(1);
        assertThat(closeRow("000660").getExternalRaw()).isEqualTo("501,000");
        assertThat(closeRow("035420").getStatus()).isEqualTo(CrosscheckRules.EXTERNAL_STALE);
        // 넘어간 증거(움직인 일치)가 1건뿐이라 다시 분류하지 않고 섞임만 말한다
        assertThat(run.getNote()).contains("진짜 불일치일 수 있다");
        assertThat(rows).filteredOn(r -> r.getItem().equals(CrosscheckRules.ITEM_EPS))
                .extracting(ExternalCrosscheckRow::getStatus).containsOnly(CrosscheckRules.NO_OUR_VALUE);
    }

    @Test
    @DisplayName("A4 — 같은 실행에서 넘어갔음이 증명되면(움직인 일치 5건) '하루 늦음' 행을 불일치로 다시 쓴다")
    void staleRowReclassifiedWhenProven() {
        for (int i = 0; i < 5; i++) {
            String code = "00000" + i;
            stock(code, "110", "100");
            respond(code, "110");
        }
        stock("999999", "10050", "10000");
        respond("999999", "10,000");

        ExternalCrosscheckRun run = service(LocalTime.of(10, 20, 40)).run("CRON");

        assertThat(closeRow("999999").getStatus()).isEqualTo(CrosscheckRules.MISMATCH);
        assertThat(closeRow("999999").getReason()).contains("넘어가 있었다");
        assertThat(run.getMismatched()).isEqualTo(1);
        assertThat(run.getNote()).contains("불일치로 다시 분류");
    }

    @Test
    @DisplayName("우리 값을 고치지 않는다 — 봉·재무 표엔 아무것도 쓰지 않는다(§3)")
    void neverWritesOurTables() {
        stock("005930", "189900", "187000");
        server.expect(manyTimes(), requestTo(url("005930"))).andRespond(withSuccess(body("005930", "190,000"), MediaType.APPLICATION_JSON));

        service(LocalTime.of(10, 20, 40)).run("CRON");

        verify(history, never()).save(any());
        verify(history, never()).saveAll(any());
        verify(history, never()).delete(any());
        verify(financial, never()).save(any());
        verify(financial, never()).saveAll(any());
        assertThat(closeRow("005930").getStatus()).isEqualTo(CrosscheckRules.MISMATCH);
    }

    @Test
    @DisplayName("연속 5건 응답을 못 받으면 멈춘다 — 남은 종목은 부르지 않고, 실패는 '대조 못 함'이다")
    void abortsAfterFiveFailures() {
        for (String c : new String[]{"000001", "000002", "000003", "000004", "000005", "000006", "000007"}) stock(c, "1000", "990");
        for (int i = 0; i < 5; i++) {
            server.expect(once(), anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        }

        ExternalCrosscheckRun run = service(LocalTime.of(10, 20, 40)).run("CRON");

        server.verify();
        assertThat(run.getStatus()).isEqualTo(ExternalCrosscheckRun.STATUS_ABORTED);
        assertThat(run.getAttempted()).isEqualTo(5);
        assertThat(run.getNotCompared()).isEqualTo(5);
        assertThat(run.getNote()).contains("FETCH_FAILED 5").contains("멈췄다");
    }

    @Test
    @DisplayName("200 이지만 값이 없는 응답이 연속 5건이어도 멈춘다(B8)")
    void abortsOnFiveEmptyValues() {
        for (String c : new String[]{"000001", "000002", "000003", "000004", "000005", "000006"}) stock(c, "1000", "990");
        server.expect(manyTimes(), anything()).andRespond(withSuccess("{\"totalInfos\":[]}", MediaType.APPLICATION_JSON));

        ExternalCrosscheckRun run = service(LocalTime.of(10, 20, 40)).run("CRON");

        assertThat(run.getStatus()).isEqualTo(ExternalCrosscheckRun.STATUS_ABORTED);
        assertThat(run.getAttempted()).isEqualTo(5);
    }

    @Test
    @DisplayName("대상 봉이 없는 종목은 표본에 넣지 않는다 — 외부 호출 없이 뺀 수를 메모에 남긴다(A7)")
    void excludesCodesWithoutTargetBar() {
        stock("000660", "500000", "490000");
        respond("000660", "500,000");
        com.myplatform.backend.entity.RecommendationSnapshot snap = mock(com.myplatform.backend.entity.RecommendationSnapshot.class);
        when(snap.getStockCode()).thenReturn("005930");   // 후보지만 D 봉이 없다
        when(snapshots.findLatestSnapshot()).thenReturn(List.of(snap));

        ExternalCrosscheckRun run = service(LocalTime.of(10, 20, 40)).run("CRON");

        server.verify();   // 005930 은 부르지 않았다
        assertThat(run.getPlanned()).isEqualTo(1);
        assertThat(run.getNote()).contains("대상 봉(" + D + ")이 없어 표본에서 뺀 고정 그룹 종목 1개");
    }

    @Test
    @DisplayName("대상 봉 종가 칸이 비었으면 NO_OUR_BAR — 사유가 그렇게 말한다")
    void targetBarWithoutClose() {
        bars.put("005930", List.of(StockPriceHistory.builder().stockCode("005930").tradeDate(D).build()));
        respond("005930", "189,900");

        service(LocalTime.of(10, 20, 40)).run("CRON");

        assertThat(closeRow("005930").getStatus()).isEqualTo(CrosscheckRules.NO_OUR_BAR);
        assertThat(closeRow("005930").getReason()).contains("종가 칸이 비어");
    }

    @Test
    @DisplayName("EPS·BPS 는 최신 KIS 행(미래 날짜 제외)과 같이 기록만 한다 — per_basis 는 EPS 에만")
    void recordsPerShare() {
        stock("005930", "189900", "187000");
        when(financial.findTop10ByStockCodeOrderByReportDateDesc("005930")).thenReturn(List.of(
                StockFinancialData.builder().stockCode("005930").reportDate(LocalDate.of(2026, 12, 31)).eps(new BigDecimal("1")).build(),
                StockFinancialData.builder().stockCode("005930").reportDate(D).marketCap(new BigDecimal("1100000"))
                        .eps(new BigDecimal("21000")).bps(new BigDecimal("86000")).perBasis("CTRL").build()));
        respond("005930", "189,900");

        service(LocalTime.of(10, 20, 40)).run("CRON");

        ExternalCrosscheckRow eps = rows.stream().filter(r -> r.getItem().equals(CrosscheckRules.ITEM_EPS)).findFirst().orElseThrow();
        ExternalCrosscheckRow bps = rows.stream().filter(r -> r.getItem().equals(CrosscheckRules.ITEM_BPS)).findFirst().orElseThrow();
        assertThat(eps.getStatus()).isEqualTo(CrosscheckRules.RECORDED);
        assertThat(eps.getOurValue()).isEqualByComparingTo("21000");
        assertThat(eps.getOurBasis()).isEqualTo("CTRL");
        assertThat(eps.getExternalValue()).isEqualByComparingTo("22292");
        assertThat(eps.getExternalBasis()).isEqualTo("2026.06.");
        assertThat(bps.getOurBasis()).isNull();
    }

    @Test
    @DisplayName("보유는 활성 가상계좌만 — 옛 계좌의 포지션은 읽지 않는다(A5)")
    void holdingsFromActiveAccountOnly() {
        stock("005930", "189900", "187000");
        respond("005930", "189,900");
        VirtualAccount active = mock(VirtualAccount.class);
        when(active.getId()).thenReturn(42L);
        when(accounts.findFirstByIsActiveTrueOrderByIdDesc()).thenReturn(Optional.of(active));
        VirtualPortfolio p = mock(VirtualPortfolio.class);
        when(p.getStockCode()).thenReturn("005930");
        when(p.getQuantity()).thenReturn(10);
        when(portfolio.findByAccountId(42L)).thenReturn(List.of(p));

        service(LocalTime.of(10, 20, 40)).run("CRON");

        assertThat(closeRow("005930").getSampleReason()).isEqualTo(CrosscheckRules.REASON_HOLDING);
        verify(portfolio, never()).findAll();
    }

    @Test
    @DisplayName("직전 실행의 불일치 종목은 맨 앞(RECHECK)으로 다시 본다(A3)")
    void recheckPreviousMismatch() {
        stock("005930", "189900", "187000");
        respond("005930", "189,900");
        when(runRepo.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of(ExternalCrosscheckRun.builder().id(3L).build()));
        when(rowRepo.findByRunIdOrderByIdAsc(3L)).thenReturn(List.of(ExternalCrosscheckRow.builder()
                .stockCode("005930").item(CrosscheckRules.ITEM_CLOSE).status(CrosscheckRules.MISMATCH).build()));

        service(LocalTime.of(10, 20, 40)).run("CRON");

        assertThat(closeRow("005930").getSampleReason()).isEqualTo(CrosscheckRules.REASON_RECHECK);
    }

    @Test
    @DisplayName("30분 넘게 RUNNING 으로 남은 실행은 다음 실행이 INTERRUPTED 로 마감한다 — 집계는 남은 종목 행에서(B6)")
    void closesStaleRunning() {
        ExternalCrosscheckRun stale = ExternalCrosscheckRun.builder().id(5L).runDate(D).targetBarDate(D1)
                .status(ExternalCrosscheckRun.STATUS_RUNNING).startedAt(LocalDateTime.of(D, LocalTime.of(10, 20))).build();
        when(runRepo.findByStatusAndStartedAtBefore(eq(ExternalCrosscheckRun.STATUS_RUNNING), any())).thenReturn(List.of(stale));
        when(rowRepo.findByRunIdOrderByIdAsc(5L)).thenReturn(List.of(ExternalCrosscheckRow.builder()
                .stockCode("005930").item(CrosscheckRules.ITEM_CLOSE).status(CrosscheckRules.MISMATCH).build()));

        service(LocalTime.of(10, 20, 40)).run("CRON");

        assertThat(stale.getStatus()).isEqualTo(ExternalCrosscheckRun.STATUS_INTERRUPTED);
        assertThat(stale.getMismatched()).isEqualTo(1);
        assertThat(stale.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("중단 표시를 받으면 남은 종목을 부르지 않고 INTERRUPTED 로 마감한다(B5)")
    void interruptStops() {
        stock("000001", "1000", "990");
        stock("000002", "1000", "990");
        stock("000003", "1000", "990");
        ExternalCrosscheckService svc = service(LocalTime.of(10, 20, 40));
        svc.pauseMillis = 1;
        server.expect(once(), anything()).andRespond(req -> {
            Thread.currentThread().interrupt();   // 첫 응답 뒤 종료 신호
            return withSuccess(body("000001", "1,000"), MediaType.APPLICATION_JSON).createResponse(req);
        });

        ExternalCrosscheckRun run = svc.run("CRON");
        boolean stillInterrupted = Thread.interrupted();   // 테스트 스레드 정리

        server.verify();   // 한 번만 불렀다
        assertThat(run.getStatus()).isEqualTo(ExternalCrosscheckRun.STATUS_INTERRUPTED);
        assertThat(run.getAttempted()).isEqualTo(1);
        assertThat(run.getNote()).contains("중단 표시");
        assertThat(stillInterrupted).as("중단 표시는 되돌려 둔다").isTrue();
    }

    @Test
    @DisplayName("diff_pct 가 표 칸을 넘으면 비우고 사유를 남긴다 — 실행은 계속(B1)")
    void diffOverflowIsNullWithReason() {
        bars.put("005930", List.of(
                StockPriceHistory.builder().stockCode("005930").tradeDate(D).closePrice(new BigDecimal("1")).build()));
        respond("005930", "189,900");

        ExternalCrosscheckRun run = service(LocalTime.of(10, 20, 40)).run("CRON");

        assertThat(closeRow("005930").getDiffPct()).isNull();
        assertThat(closeRow("005930").getReason()).contains("칸 범위");
        assertThat(run.getStatus()).isEqualTo(ExternalCrosscheckRun.STATUS_COMPLETE);
    }

    @Nested
    @DisplayName("수동 실행(B4)")
    class Manual {

        @Test
        @DisplayName("꺼져 있으면 거절한다")
        void disabled() {
            assertThat(service(LocalTime.of(10, 30), false).startManualRun()).contains("꺼져");
        }

        @Test
        @DisplayName("하루 3회를 넘기면 거절한다")
        void dailyLimit() {
            when(runRepo.countByRunDateAndTriggerType(TODAY, "MANUAL")).thenReturn(3L);
            assertThat(service(LocalTime.of(10, 30)).startManualRun()).contains("하루 3회");
        }

        @Test
        @DisplayName("창 밖(장 시작 전·장 마감 10분 전 뒤)이면 거절한다")
        void outsideWindow() {
            assertThat(service(LocalTime.of(8, 50)).startManualRun()).contains("장 시작 10분 뒤");
            assertThat(service(LocalTime.of(15, 35)).startManualRun()).contains("장 마감 10분 전");
        }

        @Test
        @DisplayName("정상이면 백그라운드로 시작하고, 도는 동안 두 번째 요청은 자리를 못 잡는다")
        void startsAndClaimsAtomically() throws Exception {
            stock("005930", "189900", "187000");
            java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            server.expect(once(), anything()).andRespond(req -> {
                entered.countDown();
                try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return withSuccess(body("005930", "189,900"), MediaType.APPLICATION_JSON).createResponse(req);
            });
            ExternalCrosscheckService svc = service(LocalTime.of(10, 30));

            assertThat(svc.startManualRun()).isNull();
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(svc.startManualRun()).isEqualTo("이미 도는 중이다");
            release.countDown();
            svc.shutdown();
        }
    }

    @Test
    @DisplayName("대조 창 밖(장 시작 전·휴장일)이면 크론도 돌지 않는다")
    void outsideWindow() {
        assertThat(service(LocalTime.of(8, 50)).run("CRON")).isNull();
        assertThat(service(LocalTime.of(15, 35)).run("CRON")).isNull();
        when(calendar.isMarketClosed(TODAY)).thenReturn(true);
        assertThat(service(LocalTime.of(10, 20, 40)).run("CRON")).isNull();
        verify(runRepo, never()).save(any());
    }

    @Test
    @DisplayName("수능일처럼 10시에 여는 날도 10:20:40 은 창 안이다")
    void lateOpenDay() {
        when(calendar.marketOpenOf(TODAY)).thenReturn(LocalTime.of(10, 0));
        assertThat(service(LocalTime.of(10, 20, 40)).inRunWindow(LocalDateTime.of(TODAY, LocalTime.of(10, 20, 40)))).isTrue();
        assertThat(service(LocalTime.of(10, 5)).inRunWindow(LocalDateTime.of(TODAY, LocalTime.of(10, 5)))).isFalse();
    }

    @Test
    @DisplayName("크론은 꺼져 있거나 휴장일이면 아무것도 쓰지 않는다")
    void cronSkips() {
        service(LocalTime.of(10, 20, 40), false).scheduledRun();
        when(calendar.isMarketClosed()).thenReturn(true);
        service(LocalTime.of(10, 20, 40)).scheduledRun();
        verify(runRepo, never()).save(any());
        verify(lock, never()).tryLock(anyString(), any());
    }

    @Test
    @DisplayName("크론 시각 — 평일 10:20:40(초 필드 :00 회피)")
    void cronExpression() throws Exception {
        org.springframework.scheduling.annotation.Scheduled s = ExternalCrosscheckService.class
                .getMethod("scheduledRun").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(s.cron()).isEqualTo("40 20 10 * * MON-FRI");
        assertThat(s.zone()).isEqualTo("Asia/Seoul");
    }

    @Test
    @DisplayName("보존 365일 — 실행 끝에 옛 기록을 지운다")
    void purgesOld() {
        service(LocalTime.of(10, 20, 40)).run("CRON");
        verify(runRepo).deleteRowsOfRunsBefore(TODAY.minusDays(365));
        verify(runRepo).deleteRunsBefore(TODAY.minusDays(365));
    }

    @Nested
    @DisplayName("요약(관제실 입력)")
    class Summaries {

        @Test
        @DisplayName("막 시작한 RUNNING 은 빼고, 30분 넘게 RUNNING 이면 INTERRUPTED 다")
        void runningHandling() {
            when(runRepo.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of(
                    ExternalCrosscheckRun.builder().id(2L).runDate(TODAY).targetBarDate(D).status("RUNNING")
                            .startedAt(LocalDateTime.of(TODAY, LocalTime.of(10, 20))).build(),
                    ExternalCrosscheckRun.builder().id(1L).runDate(D).targetBarDate(D1).status("RUNNING")
                            .startedAt(LocalDateTime.of(D, LocalTime.of(10, 20))).build()));
            when(rowRepo.findByRunIdOrderByIdAsc(anyLong())).thenReturn(List.of());

            List<ExternalCrosscheckService.RunSummary> s = service(LocalTime.of(10, 25)).recentRuns();

            assertThat(s).hasSize(1);
            assertThat(s.get(0).status()).isEqualTo(ExternalCrosscheckRun.STATUS_INTERRUPTED);
        }

        @Test
        @DisplayName("서로 다른 대상 봉 두 개까지는 종목 행을 읽어 종목별 결과·차이를 싣고, 그 뒤는 읽지 않는다(B7)")
        void detailsTwoDistinctBars() {
            LocalDate d2 = LocalDate.of(2026, 11, 6);
            List<ExternalCrosscheckRun> runs = new ArrayList<>();
            long id = 10;
            for (LocalDate bar : new LocalDate[]{D, D, D, D, D1, d2}) {
                runs.add(ExternalCrosscheckRun.builder().id(id--).runDate(TODAY).targetBarDate(bar).status("COMPLETE")
                        .startedAt(LocalDateTime.of(TODAY, LocalTime.of(10, 20)).minusMinutes(id)).build());
            }
            when(runRepo.findTop10ByOrderByStartedAtDesc()).thenReturn(runs);
            when(rowRepo.findByRunIdOrderByIdAsc(anyLong())).thenReturn(List.of(ExternalCrosscheckRow.builder()
                    .stockCode("005930").item(CrosscheckRules.ITEM_CLOSE).status(CrosscheckRules.MISMATCH)
                    .ourValue(new BigDecimal("222000")).externalValue(new BigDecimal("219500")).build()));

            List<ExternalCrosscheckService.RunSummary> s = service(LocalTime.of(11, 0)).recentRuns();

            assertThat(s).extracting(ExternalCrosscheckService.RunSummary::detailed)
                    .containsExactly(true, true, true, true, true, false);
            assertThat(s.get(4).closeStatusByCode()).containsEntry("005930", CrosscheckRules.MISMATCH);
            assertThat(s.get(4).diffPctByCode().get("005930")).isEqualByComparingTo("-1.1261");
        }
    }
}
