package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.entity.InvestorStockDaily;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.InvestorStockDailyRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 투자자 순위를 장 마감 확정치로 다시 만든다 — {@link InvestorFinalDataService}(2026-11-05).
 *
 * <p>순위 표는 소비처가 그대로 읽는다. 지켜야 할 것: 종목이 모자란 날은 가집계를 지우지 않는다 · 값이 바뀐 날만 다시 만든다 ·
 * 장 마감 전 값을 그날 기록으로 쓰지 않는다 · 금액 단위를 모르면 쓰지 않는다.
 */
class InvestorFinalDataServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ObjectMapper M = new ObjectMapper();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 11, 4);

    private KoreaInvestmentService kis;
    private InvestorDailyTradeRepository tradeRepo;
    private InvestorTradeService tradeService;
    private BatchHeartbeatService heartbeat;
    private SchedulerLockService lock;
    private StockPriceHistoryRepository history;
    private InvestorFinalDataService service;

    /** 저장된 확정치(가짜 저장소) · 종목별 응답 · FINAL 순위 행이 있는 날. */
    private final List<InvestorStockDaily> store = new ArrayList<>();
    private final Map<String, String> responses = new HashMap<>();
    private final Set<LocalDate> finalDays = new HashSet<>();

    private InvestorFinalDataService newService(LocalDateTime now) {
        InvestorStockDailyRepository stockRepo = mock(InvestorStockDailyRepository.class);
        when(stockRepo.findByTradeDateGreaterThanEqual(any())).thenAnswer(inv -> new ArrayList<>(store));
        when(stockRepo.findByTradeDate(any())).thenAnswer(inv ->
                store.stream().filter(r -> r.getTradeDate().equals(inv.getArgument(0))).toList());
        when(stockRepo.saveAll(anyList())).thenAnswer(inv -> {
            for (InvestorStockDaily r : inv.<List<InvestorStockDaily>>getArgument(0)) {
                if (!store.contains(r)) store.add(r);
            }
            return inv.getArgument(0);
        });
        StockMasterService master = mock(StockMasterService.class);
        when(master.getNameOrDefault(anyString(), any())).thenAnswer(inv -> "이름" + inv.getArgument(0));
        when(master.getMarket("005930")).thenReturn("KOSPI");
        @SuppressWarnings("unchecked")
        ObjectProvider<RecommendationService> provider = mock(ObjectProvider.class);
        InvestorFinalDataService s = new InvestorFinalDataService(kis, stockRepo, tradeRepo, history,
                master, new MarketCalendarService(), lock, tradeService, heartbeat, provider,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                Clock.fixed(now.atZone(KST).toInstant(), KST));
        s.callIntervalMillis = 0;
        return s;
    }

    @BeforeEach
    void setUp() throws Exception {
        kis = mock(KoreaInvestmentService.class);
        tradeRepo = mock(InvestorDailyTradeRepository.class);
        tradeService = mock(InvestorTradeService.class);
        heartbeat = mock(BatchHeartbeatService.class);
        lock = mock(SchedulerLockService.class);
        history = mock(StockPriceHistoryRepository.class);
        when(lock.tryLock(anyString(), any())).thenReturn(true);
        when(tradeRepo.saveAll(anyList())).thenAnswer(inv -> {
            for (InvestorDailyTrade t : inv.<List<InvestorDailyTrade>>getArgument(0)) finalDays.add(t.getTradeDate());
            return inv.getArgument(0);
        });
        when(tradeRepo.existsByTradeDateAndDataBasis(any(), eq(InvestorDailyTrade.BASIS_FINAL)))
                .thenAnswer(inv -> finalDays.contains(inv.<LocalDate>getArgument(0)));
        when(kis.isConfigured()).thenReturn(true);
        when(kis.getInvestorTradeByStockDaily(anyString(), any())).thenAnswer(inv -> {
            String body = responses.get(inv.<String>getArgument(0));
            return body == null ? null : M.readTree(body);
        });
        service = newService(TODAY.atTime(16, 15));
    }

    private static String row(LocalDate date, long foreignEok) {
        long million = foreignEok * 100;
        return "{\"stck_bsop_date\":\"" + date.toString().replace("-", "") + "\",\"stck_clpr\":\"100000\",\"prdy_ctrt\":\"1.00\",\"acml_vol\":\"1000\","
                + "\"frgn_ntby_tr_pbmn\":\"" + million + "\",\"orgn_ntby_tr_pbmn\":\"0\",\"fund_ntby_tr_pbmn\":\"0\","
                + "\"frgn_shnu_vol\":\"1000\",\"frgn_shnu_tr_pbmn\":\"100\"}";
    }

    private static String ok(String... rows) {
        return "{\"rt_cd\":\"0\",\"output2\":[" + String.join(",", rows) + "]}";
    }

    /** n 개 종목 — 최근 순위에 든 것으로 universe 를 만든다. 오늘·어제 행, 외국인 순매수는 종목마다 다르다. */
    private List<String> stocks(int n) {
        List<String> codes = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String code = String.format("%06d", i);
            codes.add(code);
            responses.put(code, ok(row(TODAY, i), row(YESTERDAY, -i)));
        }
        when(tradeRepo.findDistinctStockCodesSince(any())).thenReturn(codes);
        return codes;
    }

    @SuppressWarnings("unchecked")
    private List<InvestorDailyTrade> savedRanking(int call) {
        org.mockito.ArgumentCaptor<List<InvestorDailyTrade>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(tradeRepo, org.mockito.Mockito.atLeastOnce()).saveAll(captor.capture());
        return captor.getAllValues().get(call);
    }

    @Test
    @DisplayName("재현: 받은 값으로 그날 순위를 다시 만든다 — 가집계 행을 지우고 FINAL 행을 넣고 연속 매수 캐시를 비운다")
    void rebuildsDays() {
        stocks(320);

        InvestorFinalDataService.Summary s = service.collect();

        assertThat(s.ok()).isEqualTo(320);
        assertThat(s.todayRows()).isEqualTo(320);
        assertThat(s.rebuiltDays()).isEqualTo(2);
        verify(tradeRepo).deleteByTradeDateAndInvestorType(TODAY, "FOREIGN");
        verify(tradeRepo).deleteByTradeDateAndInvestorType(YESTERDAY, "FOREIGN");
        verify(tradeRepo, never()).deleteByTradeDateAndInvestorType(any(), eq("PENSION"));
        verify(tradeService).evictConsecutiveBuys();

        List<InvestorDailyTrade> yesterday = savedRanking(0);
        assertThat(yesterday).allMatch(r -> r.getTradeDate().equals(YESTERDAY) && InvestorDailyTrade.BASIS_FINAL.equals(r.getDataBasis()));
        List<InvestorDailyTrade> today = savedRanking(1);
        assertThat(today).hasSize(30);
        assertThat(today.get(0).getStockCode()).isEqualTo("000320");
        assertThat(today.get(0).getNetBuyAmount()).isEqualByComparingTo("320");
        assertThat(today.get(0).getStockName()).isEqualTo("이름000320");
    }

    @Test
    @DisplayName("§4c: 받은 종목이 모자란 날은 가집계를 지우지 않는다 — 적은 종목으로 만든 순위는 순위가 아니다")
    void lowCoverageKeepsTally() {
        stocks(299);

        InvestorFinalDataService.Summary s = service.collect();

        assertThat(s.rebuiltDays()).isZero();
        assertThat(s.skippedDays()).containsExactly(YESTERDAY, TODAY);
        verify(tradeRepo, never()).deleteByTradeDateAndInvestorType(any(), anyString());
        verify(tradeService, never()).evictConsecutiveBuys();
    }

    @Test
    @DisplayName("다음 회차에 값이 그대로면 쓰는 것도 다시 만드는 것도 없다 · 바뀐 날만 다시 만든다")
    void onlyChangedDaysAreRebuilt() {
        stocks(320);
        service.collect();
        org.mockito.Mockito.clearInvocations(tradeRepo);

        InvestorFinalDataService.Summary same = service.collect();
        assertThat(same.changedRows()).isZero();
        verify(tradeRepo, never()).deleteByTradeDateAndInvestorType(any(), anyString());

        responses.put("000001", ok(row(TODAY, 999), row(YESTERDAY, -1)));
        InvestorFinalDataService.Summary changed = service.collect();

        assertThat(changed.changedRows()).isEqualTo(1);
        assertThat(changed.rebuiltDays()).isEqualTo(1);
        verify(tradeRepo).deleteByTradeDateAndInvestorType(TODAY, "FOREIGN");
        verify(tradeRepo, never()).deleteByTradeDateAndInvestorType(eq(YESTERDAY), anyString());
    }

    @Test
    @DisplayName("재현: 앞 회차가 종목 행만 쓰고 순위를 만들기 전에 끊겼으면(재시작·예외) 다음 회차가 그날을 다시 만든다 — 값이 같아 '바뀐 날'에 안 들어 가집계로 영영 남았다")
    void interruptedDayIsRebuiltNextRun() {
        stocks(320);
        service.collect();
        finalDays.remove(YESTERDAY);   // 어제 행까지 썼는데 어제 순위를 만들기 전에 끊긴 상태
        org.mockito.Mockito.clearInvocations(tradeRepo);

        InvestorFinalDataService.Summary s = service.collect();

        assertThat(s.changedRows()).isZero();
        assertThat(s.rebuiltDays()).isEqualTo(1);
        verify(tradeRepo).deleteByTradeDateAndInvestorType(YESTERDAY, "FOREIGN");
        verify(tradeRepo, never()).deleteByTradeDateAndInvestorType(eq(TODAY), anyString());
        verify(tradeService, times(2)).evictConsecutiveBuys();
    }

    @Test
    @DisplayName("장 마감 전에 불리면 오늘 값을 그날 기록으로 쓰지 않는다 — 어제까지만")
    void todayIsNotStoredBeforeClose() {
        stocks(320);
        service = newService(TODAY.atTime(11, 0));

        service.collect();

        assertThat(store).noneMatch(r -> r.getTradeDate().equals(TODAY));
        verify(tradeRepo, never()).deleteByTradeDateAndInvestorType(eq(TODAY), anyString());
        verify(tradeRepo).deleteByTradeDateAndInvestorType(YESTERDAY, "FOREIGN");
    }

    @Test
    @DisplayName("연속 실패면 회차를 멈춘다 — 토큰·한도 문제면 나머지도 똑같이 실패한다. 가집계는 그대로")
    void consecutiveFailuresAbort() throws Exception {
        stocks(320);
        responses.clear();

        InvestorFinalDataService.Summary s = service.collect();

        assertThat(s.abortReason()).contains("연속 실패");
        verify(kis, times(InvestorFinalDataService.MAX_CONSECUTIVE_FAILURES)).getInvestorTradeByStockDaily(anyString(), any());
        verify(tradeRepo, never()).deleteByTradeDateAndInvestorType(any(), anyString());
    }

    @Test
    @DisplayName("KIS 가 거절한 응답(rt_cd≠0)은 실패로 센다 — 200 이어도 값이 없다")
    void rejectedResponse() {
        stocks(320);
        responses.put("000005", "{\"rt_cd\":\"1\",\"msg1\":\"조회 불가\"}");

        InvestorFinalDataService.Summary s = service.collect();

        assertThat(s.rejected()).isEqualTo(1);
        assertThat(s.ok()).isEqualTo(319);
    }

    @Test
    @DisplayName("금액 단위를 모르는 응답은 저장하지 않는다")
    void unknownUnitNotStored() {
        stocks(320);
        responses.put("000007", ok(row(TODAY, 7).replace("\"frgn_shnu_tr_pbmn\":\"100\"", "\"frgn_shnu_tr_pbmn\":\"100000\"")));

        InvestorFinalDataService.Summary s = service.collect();

        assertThat(s.unknownUnit()).isEqualTo(1);
        assertThat(store).noneMatch(r -> r.getStockCode().equals("000007"));
    }

    @Test
    @DisplayName("크론 — 휴장일·장 마감 전(수능일 16:15)은 호출하지 않는다")
    void gates() {
        stocks(320);

        newService(LocalDate.of(2026, 11, 7).atTime(16, 15)).runScheduled("16:15");   // 토요일
        newService(LocalDate.of(2026, 11, 19).atTime(16, 15)).runScheduled("16:15");  // 수능일 — 장 마감 16:40

        verify(kis, never()).getInvestorTradeByStockDaily(anyString(), any());
    }

    @Test
    @DisplayName("수능일(마감 16:40)은 16:15 를 건너뛰고 18:10 회차가 그날 확정치를 받아 순위를 만든다 — 시각은 바꾸지 않는다(2026-11-10 리허설)")
    void lateCloseDayIsCollectedAtEvening() {
        LocalDate csat = LocalDate.of(2026, 11, 19);
        List<String> codes = new ArrayList<>();
        for (int i = 1; i <= 320; i++) {
            String code = String.format("%06d", i);
            codes.add(code);
            responses.put(code, ok(row(csat, i), row(csat.minusDays(1), -i)));
        }
        when(tradeRepo.findDistinctStockCodesSince(any())).thenReturn(codes);

        newService(csat.atTime(16, 15)).runScheduled("16:15");
        verify(kis, never()).getInvestorTradeByStockDaily(anyString(), any());

        newService(csat.atTime(18, 10)).runScheduled("18:10");

        assertThat(store).filteredOn(r -> r.getTradeDate().equals(csat)).hasSize(320);
        assertThat(finalDays).contains(csat);
    }

    @Test
    @DisplayName("다음 거래일 16:15 회차가 어제 행의 종가·금액을 고치고 순위 행을 다시 만든다 — 아침엔 KIS 가 이 API 를 거부해 그 전엔 고칠 길이 없다(11/5 행 910개 중 610개 금액이 다음 날 달랐다)")
    void nextDayAfterCloseFixesYesterdaysRows() {
        stocks(320);
        service.runScheduled("16:15");
        org.mockito.Mockito.clearInvocations(tradeRepo);
        // 다음 날 — 어제 행의 종가가 KRX 값으로, 한 종목은 금액까지 바뀌어 온다
        LocalDate nextDay = TODAY.plusDays(1);
        for (String code : new ArrayList<>(responses.keySet())) {
            responses.put(code, responses.get(code).replace("\"stck_clpr\":\"100000\"", "\"stck_clpr\":\"101000\""));
        }
        responses.put("000001", ok(row(TODAY, 999).replace("100000", "101000"), row(YESTERDAY, -1).replace("100000", "101000")));

        newService(nextDay.atTime(16, 15)).runScheduled("16:15");

        assertThat(store).filteredOn(r -> r.getTradeDate().equals(TODAY)).isNotEmpty()
                .allMatch(r -> r.getClosePrice().compareTo(new java.math.BigDecimal("101000")) == 0);
        assertThat(store).filteredOn(r -> r.getTradeDate().equals(TODAY) && r.getStockCode().equals("000001"))
                .singleElement().satisfies(r -> assertThat(r.getForeignNet()).isEqualByComparingTo("999"));
        verify(tradeRepo).deleteByTradeDateAndInvestorType(TODAY, "FOREIGN");
        assertThat(store).noneMatch(r -> r.getTradeDate().equals(nextDay));
    }

    @Test
    @DisplayName("회차 요약은 금액이 바뀐 행을 따로 센다 — 종가만 바뀐 행과 섞이면 금액이 언제 확정되는지 못 본다")
    void summaryCountsAmountChangesApart() {
        stocks(320);
        service.collect();
        for (String code : new ArrayList<>(responses.keySet())) {
            responses.put(code, responses.get(code).replace("\"stck_clpr\":\"100000\"", "\"stck_clpr\":\"101000\""));
        }
        assertThat(service.collect().amountChangedRows()).isZero();

        responses.put("000001", ok(row(TODAY, 999).replace("100000", "101000"), row(YESTERDAY, -1).replace("100000", "101000")));
        InvestorFinalDataService.Summary s = service.collect();
        assertThat(s.changedRows()).isEqualTo(1);
        assertThat(s.amountChangedRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("KIS 가 받지 않는 시각(00:00~15:40)엔 부르지 않는다 — 11/9 07:50 아침 회차가 30건 전부 'TIME LIMIT 00:00 ~ 15:40' 으로 거부됐다")
    void noCallBeforeKisWindow() {
        stocks(320);

        newService(LocalDate.of(2026, 11, 9).atTime(7, 50)).runScheduled("07:50");    // 월요일 아침
        newService(LocalDate.of(2026, 11, 9).atTime(15, 39)).runScheduled("15:39");

        verify(kis, never()).getInvestorTradeByStockDaily(anyString(), any());
    }

    @Test
    @DisplayName("크론 — 온전히 받았을 때만 심박을 남긴다. 멈추면 수급이 조용히 가집계로 남는다")
    void heartbeatOnlyWhenComplete() {
        stocks(320);
        service.runScheduled("16:15");
        verify(heartbeat).recordSuccess(BatchHeartbeatService.JOB_INVESTOR_FINAL);

        org.mockito.Mockito.clearInvocations(heartbeat);
        store.clear();
        stocks(100);
        service.runScheduled("18:10");
        verify(heartbeat, never()).recordSuccess(eq(BatchHeartbeatService.JOB_INVESTOR_FINAL));
    }

    @Test
    @DisplayName("대상 — 후보 풀 + 최근 순위에 든 종목, 중복·빈 값·칸보다 긴 코드는 뺀다")
    void universe() {
        assertThat(InvestorFinalDataService.universeOf(List.of("005930", " 000660 ", ""), java.util.Arrays.asList("005930", null, "12345678901", "0039P0")))
                .containsExactly("005930", "000660", "0039P0");
    }

    @Test
    @DisplayName("재현: 후보 풀의 ETF 는 받지 않는다 — 가집계 순위엔 ETF 가 없다. 우선주는 순위에 있으면 받는다")
    void etfInPoolIsSkipped() {
        when(tradeRepo.findDistinctStockCodesSince(any())).thenReturn(List.of("005935"));
        when(history.findStockCodesByTradeDate(any())).thenReturn(List.of("005930", "069500"));
        responses.put("005930", ok(row(TODAY, 1)));
        responses.put("005935", ok(row(TODAY, 2)));
        responses.put("069500", ok(row(TODAY, 3)));

        InvestorFinalDataService.Summary s = service.collect();

        assertThat(s.stocks()).isEqualTo(2);
        verify(kis, never()).getInvestorTradeByStockDaily(eq("069500"), any());
        verify(kis).getInvestorTradeByStockDaily(eq("005935"), any());
    }

    @Test
    @DisplayName("KIS 응답이 null 인 본문 필드도 터지지 않는다")
    void nullBodySafe() throws Exception {
        JsonNode empty = M.readTree("{\"rt_cd\":\"0\"}");
        assertThat(InvestorFinalRows.parse(empty).unitKnown()).isTrue();
    }
}
