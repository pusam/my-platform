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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 외부 대조 실행 — 우리 값은 읽기만 하고, 요청엔 헤더를 붙이지 않고, 실패는 '대조 못 함'으로 남고, 연속 실패면 멈춘다.
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
    private final VirtualPortfolioRepository portfolio = mock(VirtualPortfolioRepository.class);
    private final BotTradingPositionRepository bot = mock(BotTradingPositionRepository.class);
    private final StockWatchlistRepository watch = mock(StockWatchlistRepository.class);
    private final StockStatusService status = mock(StockStatusService.class);
    private final MarketCalendarService calendar = mock(MarketCalendarService.class);
    private final SchedulerLockService lock = mock(SchedulerLockService.class);

    private final List<ExternalCrosscheckRow> rows = new ArrayList<>();
    private final List<ExternalCrosscheckRun> savedRuns = new ArrayList<>();
    private final Map<String, List<StockPriceHistory>> bars = new HashMap<>();

    private ExternalCrosscheckService service(LocalTime at) {
        Clock clock = Clock.fixed(LocalDateTime.of(TODAY, at).atZone(KST).toInstant(), KST);
        ExternalCrosscheckService s = new ExternalCrosscheckService(rest, new ObjectMapper(), runRepo, rowRepo, history,
                financial, snapshots, portfolio, bot, watch, status, calendar, lock, clock, true);
        s.pauseMillis = 0;
        return s;
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
            savedRuns.add(r);
            return r;
        });
        when(rowRepo.save(any())).thenAnswer(inv -> {
            rows.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(history.findByStockCodeOrderByTradeDateDesc(anyString(), any(Pageable.class)))
                .thenAnswer(inv -> bars.getOrDefault(inv.<String>getArgument(0), List.of()));
        when(financial.findTop10ByStockCodeOrderByReportDateDesc(anyString())).thenReturn(List.of());
        when(financial.findLatestMarketCaps()).thenReturn(List.of());
    }

    private void stock(String code, String closeD, String closeD1) {
        bars.put(code, List.of(
                StockPriceHistory.builder().stockCode(code).tradeDate(D).closePrice(new BigDecimal(closeD)).build(),
                StockPriceHistory.builder().stockCode(code).tradeDate(D1).closePrice(new BigDecimal(closeD1)).build()));
    }

    private void pool(String... codes) {
        when(history.findStockCodesByTradeDate(D)).thenReturn(List.of(codes));
    }

    private static String body(String close) {
        return CrosscheckRulesTest.SAMSUNG_INTEGRATION.replace("\"189,900\"", "\"" + close + "\"");
    }

    private static String url(String code) {
        return "https://m.stock.naver.com/api/stock/" + code + "/integration";
    }

    private ExternalCrosscheckRow closeRow(String code) {
        return rows.stream().filter(r -> r.getStockCode().equals(code) && r.getItem().equals(CrosscheckRules.ITEM_CLOSE))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("일치·불일치·하루 늦음을 가르고, 요청엔 User-Agent·Referer·Cookie 를 붙이지 않는다")
    void classifiesAndSendsNoHeaders() {
        stock("005930", "189900", "187000");
        stock("000660", "500000", "490000");
        stock("035420", "200000", "210000");
        pool("000660", "005930", "035420");
        for (String[] c : new String[][]{{"005930", "189,900"}, {"000660", "501,000"}, {"035420", "210,000"}}) {
            server.expect(once(), requestTo(url(c[0])))
                    .andExpect(req -> {
                        assertThat(req.getHeaders().containsHeader("User-Agent")).as("User-Agent").isFalse();
                        assertThat(req.getHeaders().containsHeader("Referer")).as("Referer").isFalse();
                        assertThat(req.getHeaders().containsHeader("Cookie")).as("Cookie").isFalse();
                    })
                    .andRespond(withSuccess(body(c[1]), MediaType.APPLICATION_JSON));
        }

        ExternalCrosscheckRun run = service(LocalTime.of(10, 20, 40)).run("CRON");

        server.verify();
        assertThat(run.getStatus()).isEqualTo(ExternalCrosscheckRun.STATUS_COMPLETE);
        assertThat(run.getTargetBarDate()).isEqualTo(D);
        assertThat(run.getAttempted()).isEqualTo(3);
        assertThat(run.getMatched()).isEqualTo(1);
        assertThat(run.getMismatched()).isEqualTo(1);
        assertThat(run.getNotCompared()).isEqualTo(1);
        assertThat(closeRow("005930").getStatus()).isEqualTo(CrosscheckRules.MATCH);
        assertThat(closeRow("000660").getStatus()).isEqualTo(CrosscheckRules.MISMATCH);
        assertThat(closeRow("000660").getExternalRaw()).isEqualTo("501,000");
        assertThat(closeRow("035420").getStatus()).isEqualTo(CrosscheckRules.EXTERNAL_STALE);
        // 하루 늦음이 이미 넘어간 일치 종목과 섞였다 — 메모가 그렇게 말한다(분류는 대조 못 함 그대로)
        assertThat(run.getNote()).contains("진짜 불일치일 수 있다");
        // EPS·BPS 는 기록만 — 우리 재무 행이 없으니 NO_OUR_VALUE
        assertThat(rows).filteredOn(r -> r.getItem().equals(CrosscheckRules.ITEM_EPS))
                .extracting(ExternalCrosscheckRow::getStatus).containsOnly(CrosscheckRules.NO_OUR_VALUE);
    }

    @Test
    @DisplayName("우리 값을 고치지 않는다 — 봉·재무 표엔 아무것도 쓰지 않는다(§3)")
    void neverWritesOurTables() {
        stock("005930", "189900", "187000");
        pool("005930");
        server.expect(manyTimes(), requestTo(url("005930"))).andRespond(withSuccess(body("190,000"), MediaType.APPLICATION_JSON));

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
        String[] codes = {"000001", "000002", "000003", "000004", "000005", "000006", "000007"};
        for (String c : codes) stock(c, "1000", "990");
        pool(codes);
        for (int i = 0; i < 5; i++) {
            server.expect(once(), org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        }

        ExternalCrosscheckRun run = service(LocalTime.of(10, 20, 40)).run("CRON");

        server.verify();   // 다섯 번만 불렀다
        assertThat(run.getStatus()).isEqualTo(ExternalCrosscheckRun.STATUS_ABORTED);
        assertThat(run.getAttempted()).isEqualTo(5);
        assertThat(run.getMatched()).isZero();
        assertThat(run.getNotCompared()).isEqualTo(5);
        assertThat(run.getNote()).contains("FETCH_FAILED 5").contains("멈췄다");
        assertThat(rows).extracting(ExternalCrosscheckRow::getStatus).containsOnly(CrosscheckRules.FETCH_FAILED);
    }

    @Test
    @DisplayName("우리 그날 봉이 없으면 NO_OUR_BAR — 아침 재수집이 못 받은 종목")
    void missingOurBar() {
        bars.put("005930", List.of(StockPriceHistory.builder().stockCode("005930").tradeDate(D1)
                .closePrice(new BigDecimal("187000")).build()));
        pool();
        com.myplatform.backend.entity.RecommendationSnapshot snap = mock(com.myplatform.backend.entity.RecommendationSnapshot.class);
        when(snap.getStockCode()).thenReturn("005930");
        when(snapshots.findLatestSnapshot()).thenReturn(List.of(snap));
        server.expect(once(), requestTo(url("005930"))).andRespond(withSuccess(body("189,900"), MediaType.APPLICATION_JSON));

        ExternalCrosscheckRun run = service(LocalTime.of(10, 20, 40)).run("CRON");

        assertThat(closeRow("005930").getStatus()).isEqualTo(CrosscheckRules.NO_OUR_BAR);
        assertThat(closeRow("005930").getSampleReason()).isEqualTo(CrosscheckRules.REASON_CANDIDATE);
        assertThat(run.getMatched()).isZero();
        assertThat(run.getNotCompared()).isEqualTo(1);
    }

    @Test
    @DisplayName("EPS·BPS 는 최신 KIS 행(미래 날짜 제외)과 같이 기록만 한다")
    void recordsPerShare() {
        stock("005930", "189900", "187000");
        pool("005930");
        when(financial.findTop10ByStockCodeOrderByReportDateDesc("005930")).thenReturn(List.of(
                StockFinancialData.builder().stockCode("005930").reportDate(LocalDate.of(2026, 12, 31)).eps(new BigDecimal("1")).build(),
                StockFinancialData.builder().stockCode("005930").reportDate(D).marketCap(new BigDecimal("1100000"))
                        .eps(new BigDecimal("21000")).bps(new BigDecimal("86000")).perBasis("CTRL").build()));
        server.expect(once(), requestTo(url("005930"))).andRespond(withSuccess(body("189,900"), MediaType.APPLICATION_JSON));

        service(LocalTime.of(10, 20, 40)).run("CRON");

        ExternalCrosscheckRow eps = rows.stream().filter(r -> r.getItem().equals(CrosscheckRules.ITEM_EPS)).findFirst().orElseThrow();
        assertThat(eps.getStatus()).isEqualTo(CrosscheckRules.RECORDED);
        assertThat(eps.getOurValue()).isEqualByComparingTo("21000");
        assertThat(eps.getOurBasis()).isEqualTo("CTRL");
        assertThat(eps.getExternalValue()).isEqualByComparingTo("22292");
        assertThat(eps.getExternalBasis()).isEqualTo("2026.06.");
        assertThat(eps.getDiffPct()).isNotNull();
    }

    @Test
    @DisplayName("대조 창 밖(장 시작 전·휴장일)이면 돌지 않는다 — 외부 '전일'이 어느 날 값인지 모른다")
    void outsideWindow() {
        assertThat(service(LocalTime.of(8, 50)).run("MANUAL")).isNull();
        assertThat(service(LocalTime.of(8, 50)).startManualRun()).contains("장 시작 10분 뒤");
        assertThat(service(LocalTime.of(15, 35)).run("MANUAL")).isNull();
        when(calendar.isMarketClosed(TODAY)).thenReturn(true);
        assertThat(service(LocalTime.of(10, 20, 40)).run("MANUAL")).isNull();
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
    @DisplayName("크론은 휴장일에 아무것도 쓰지 않는다")
    void cronSkipsHoliday() {
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
        pool();
        service(LocalTime.of(10, 20, 40)).run("CRON");
        verify(runRepo).deleteRowsOfRunsBefore(TODAY.minusDays(365));
        verify(runRepo).deleteRunsBefore(TODAY.minusDays(365));
    }

    @Test
    @DisplayName("막 시작한 RUNNING 은 요약에서 빼고, 30분 넘게 RUNNING 이면 중단된 실행이다")
    void summaries() {
        when(runRepo.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of(
                ExternalCrosscheckRun.builder().id(2L).runDate(TODAY).targetBarDate(D).status("RUNNING")
                        .startedAt(LocalDateTime.of(TODAY, LocalTime.of(10, 20))).build(),
                ExternalCrosscheckRun.builder().id(1L).runDate(D).targetBarDate(D1).status("RUNNING")
                        .startedAt(LocalDateTime.of(D, LocalTime.of(10, 20))).build()));
        when(rowRepo.findByRunIdOrderByIdAsc(anyLong())).thenReturn(List.of());

        List<ExternalCrosscheckService.RunSummary> s = service(LocalTime.of(10, 25)).recentRuns();

        assertThat(s).hasSize(1);
        assertThat(s.get(0).status()).isEqualTo(ExternalCrosscheckService.INTERRUPTED);
    }
}
