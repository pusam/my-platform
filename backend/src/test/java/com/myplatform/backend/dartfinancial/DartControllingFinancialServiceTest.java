package com.myplatform.backend.dartfinancial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dartfinancial.ControllingEarnings.ReportKey;
import com.myplatform.backend.entity.StockQuarterlyFinancial;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockQuarterlyFinancialRepository;
import com.myplatform.backend.service.DartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DART 지배주주 수집 — {@link DartControllingFinancialService}(2026-09-30). 오늘 = 2026-09-30 → 최신 후보는 2026 반기
 * (기한 8/14), 짝은 2025 사업보고서. 응답은 실제 모양({@code status}·{@code list}), 호출은 기록해 센다.
 */
class DartControllingFinancialServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private DartControllingFinancialRepository repo;
    private DartCompanyRepository companyRepo;
    private DartService dart;
    private StockFinancialDataRepository financialRepo;
    private StockQuarterlyFinancialRepository quarterRepo;
    private RestTemplate rest;
    private DartControllingFinancialService service;

    /** 저장된 행(가짜 저장소) · 응답표(키 = corp/year/reprt/fs) · 호출 기록. */
    private final List<DartControllingFinancial> saved = new ArrayList<>();
    private final List<DartCompany> companies = new ArrayList<>();
    private final Map<String, String> responses = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    /** 저장된 분기 행 — 기본은 universe() 종목의 KIS 분기가 다 있다(채우기 대상 아님). {@link #noKisQuarters} 종목은 비어 있다. */
    private final List<StockQuarterlyFinancial> quarters = new ArrayList<>();
    private final Set<String> noKisQuarters = new HashSet<>();
    private List<String> universeCodes = List.of();

    @BeforeEach
    void setUp() {
        repo = mock(DartControllingFinancialRepository.class);
        companyRepo = mock(DartCompanyRepository.class);
        dart = mock(DartService.class);
        financialRepo = mock(StockFinancialDataRepository.class);
        quarterRepo = mock(StockQuarterlyFinancialRepository.class);
        rest = mock(RestTemplate.class);
        Clock clock = Clock.fixed(LocalDate.of(2026, 9, 30).atTime(21, 30).atZone(KST).toInstant(), KST);
        service = new DartControllingFinancialService(repo, companyRepo, dart, financialRepo, quarterRepo, rest, new ObjectMapper(), clock);
        ReflectionTestUtils.setField(service, "dartApiKey", "test-key");
        service.callIntervalMillis = 0;

        when(quarterRepo.findAllSince(any())).thenAnswer(inv -> {
            List<StockQuarterlyFinancial> out = new ArrayList<>(quarters);
            for (String code : universeCodes) {
                if (noKisQuarters.contains(code)) continue;
                for (ReportKey key : ControllingEarnings.candidates(LocalDate.of(2026, 9, 30), DartQuarterly.REPORTS_TO_KEEP)) {
                    out.add(StockQuarterlyFinancial.builder().stockCode(code).fiscalPeriod(DartQuarterly.fiscalPeriod(key))
                            .periodEnd(DartQuarterly.periodEnd(key)).revenue(java.math.BigDecimal.TEN)
                            .source(StockQuarterlyFinancial.SOURCE_KIS).build());
                }
            }
            return out;
        });
        when(quarterRepo.save(any(StockQuarterlyFinancial.class))).thenAnswer(inv -> {
            StockQuarterlyFinancial q = inv.getArgument(0);
            if (!quarters.contains(q)) quarters.add(q);
            return q;
        });
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(saved));
        when(companyRepo.findAll()).thenAnswer(inv -> new ArrayList<>(companies));
        when(companyRepo.save(any(DartCompany.class))).thenAnswer(inv -> {
            DartCompany c = inv.getArgument(0);
            if (!companies.contains(c)) companies.add(c);
            return c;
        });
        when(repo.save(any(DartControllingFinancial.class))).thenAnswer(inv -> {
            DartControllingFinancial row = inv.getArgument(0);
            if (!saved.contains(row)) saved.add(row);
            return row;
        });
        when(rest.getForObject(any(URI.class), eq(String.class))).thenAnswer(inv -> {
            URI uri = inv.getArgument(0);
            Map<String, List<String>> q = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
            assertThat(q.get("crtfc_key")).containsExactly("test-key");
            if (uri.getPath().endsWith("/company.json")) {
                String key = "company/" + q.get("corp_code").get(0);
                calls.add(key);
                String body = responses.get(key);
                if ("THROW".equals(body)) throw new ResourceAccessException("timeout");
                return body != null ? body : DECEMBER_COMPANY;
            }
            String key = q.get("corp_code").get(0) + "/" + q.get("bsns_year").get(0) + "/"
                    + q.get("reprt_code").get(0) + "/" + q.get("fs_div").get(0);
            calls.add(key);
            String body = responses.get(key);
            if ("THROW".equals(body)) throw new ResourceAccessException("timeout");
            return body != null ? body : NO_DATA;
        });
    }

    private static final String NO_DATA = "{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다.\"}";
    /** 기업개황 기본 응답 — 12월 결산. */
    private static final String DECEMBER_COMPANY = "{\"status\":\"000\",\"acc_mt\":\"12\"}";

    private static String ok(String rcept, String sj, String ctrlId, String cum, String prevCum, String equityId, String equity) {
        return "{\"status\":\"000\",\"message\":\"정상\",\"list\":["
                + "{\"rcept_no\":\"" + rcept + "\",\"sj_div\":\"" + sj + "\",\"account_id\":\"" + ctrlId + "\","
                + "\"thstrm_amount\":\"" + cum + "\",\"thstrm_add_amount\":\"" + cum + "\",\"frmtrm_amount\":\"" + prevCum + "\","
                + "\"frmtrm_add_amount\":\"" + prevCum + "\"},"
                + "{\"rcept_no\":\"" + rcept + "\",\"sj_div\":\"BS\",\"account_id\":\"" + equityId + "\",\"thstrm_amount\":\"" + equity + "\"}]}";
    }

    private static String cfs(String rcept, String cum, String prevCum, String equity) {
        return ok(rcept, "CIS", "ifrs-full_ProfitLossAttributableToOwnersOfParent", cum, prevCum,
                "ifrs-full_EquityAttributableToOwnersOfParent", equity);
    }

    private static String ofs(String rcept, String cum, String prevCum, String equity) {
        return ok(rcept, "IS", "ifrs-full_ProfitLoss", cum, prevCum, "ifrs-full_Equity", equity);
    }

    private void universe(String... codes) {
        universeCodes = List.of(codes);
        when(financialRepo.findAllStockCodes()).thenReturn(List.of(codes));
    }

    /** 별도재무제표 회사의 보고서 응답 — 금액은 원, 손익은 CIS(작은 회사는 포괄손익계산서 하나로 낸다), 분·반기 금액은 누적 칸. */
    private static String statement(String rcept, String revenue, String operating, String net, String equity, String assets, String debt) {
        String income = "\"thstrm_amount\":\"%1$s\",\"thstrm_add_amount\":\"%1$s\",\"frmtrm_amount\":\"0\",\"frmtrm_add_amount\":\"0\"";
        return "{\"status\":\"000\",\"message\":\"정상\",\"list\":["
                + "{\"rcept_no\":\"" + rcept + "\",\"sj_div\":\"CIS\",\"account_id\":\"ifrs-full_Revenue\",\"account_nm\":\"매출액\"," + income.formatted(revenue) + "},"
                + "{\"rcept_no\":\"" + rcept + "\",\"sj_div\":\"CIS\",\"account_id\":\"dart_OperatingIncomeLoss\",\"account_nm\":\"영업이익\"," + income.formatted(operating) + "},"
                + "{\"rcept_no\":\"" + rcept + "\",\"sj_div\":\"CIS\",\"account_id\":\"ifrs-full_ProfitLoss\",\"account_nm\":\"당기순이익\"," + income.formatted(net) + "},"
                + "{\"rcept_no\":\"" + rcept + "\",\"sj_div\":\"BS\",\"account_id\":\"ifrs-full_Equity\",\"thstrm_amount\":\"" + equity + "\"},"
                + "{\"rcept_no\":\"" + rcept + "\",\"sj_div\":\"BS\",\"account_id\":\"ifrs-full_Assets\",\"thstrm_amount\":\"" + assets + "\"},"
                + "{\"rcept_no\":\"" + rcept + "\",\"sj_div\":\"BS\",\"account_id\":\"ifrs-full_Liabilities\",\"thstrm_amount\":\"" + debt + "\"}]}";
    }

    /** 2024 반기 ~ 2026 반기 보고서 9건 — 억원 단위 누적(원으로 바꿔 넣는다). */
    private void nineReports(String corp) {
        String[][] rows = {
                {"2026", "11012", "22000", "7000", "5000"}, {"2026", "11013", "10000", "3000", "2000"},
                {"2025", "11011", "40000", "12000", "9000"}, {"2025", "11014", "30000", "9000", "7000"},
                {"2025", "11012", "20000", "6000", "4500"}, {"2025", "11013", "9000", "2500", "2000"},
                {"2024", "11011", "36000", "10000", "8000"}, {"2024", "11014", "27000", "7500", "6000"},
                {"2024", "11012", "18000", "5000", "4000"}};
        for (String[] r : rows) {
            responses.put(corp + "/" + r[0] + "/" + r[1] + "/OFS", statement(r[0] + "0814000001",
                    r[2] + "00000000", r[3] + "00000000", r[4] + "00000000",
                    "5000000000000", "6000000000000", "1000000000000"));
        }
    }

    private StockQuarterlyFinancial quarter(String period) {
        return quarters.stream().filter(q -> q.getFiscalPeriod().equals(period)).findFirst().orElse(null);
    }

    @Test
    @DisplayName("별도재무제표 회사(KIS 분기 없음) — 보고서 9건을 받아 개별 분기를 DART 행으로 저장한다(최근 4분기 매출 42,000억)")
    void separateFilerQuartersFilled() {
        universe("058470");
        corp("058470", "00123456");
        noKisQuarters.add("058470");
        nineReports("00123456");

        DartControllingFinancialService.Summary s = service.collect();

        assertThat(s.fillStocks()).isEqualTo(1);
        assertThat(calls.stream().filter(c -> c.endsWith("/OFS"))).hasSize(9);
        assertThat(calls.stream().filter(c -> c.endsWith("/CFS"))).as("연결은 첫 보고서에서 한 번만 묻는다").hasSize(1);
        assertThat(savedRow(2026, "11012").isIncomeParsed()).isTrue();
        assertThat(savedRow(2026, "11012").getRevenue()).isEqualByComparingTo("22000");
        assertThat(savedRow(2026, "11012").getTotalLiabilities()).isEqualByComparingTo("10000");

        assertThat(quarter("202606").getRevenue()).isEqualByComparingTo("12000");     // 22,000 − 10,000
        assertThat(quarter("202603").getOperatingProfit()).isEqualByComparingTo("3000");
        assertThat(quarter("202512").getNetIncome()).isEqualByComparingTo("2000");    // 9,000 − 7,000
        assertThat(quarter("202409").getRevenue()).isEqualByComparingTo("9000");
        assertThat(quarter("202406")).as("앞 누적(2024 1분기)이 없어 2분기를 모른다").isNull();
        assertThat(quarters).allMatch(q -> StockQuarterlyFinancial.SOURCE_DART.equals(q.getSource()) && !q.isCumulative());
        assertThat(quarters).extracting(StockQuarterlyFinancial::getStockCode).containsOnly("058470");
    }

    @Test
    @DisplayName("이미 채운 종목 — 다음 회차는 받을 것도 쓸 것도 없다(같은 값을 다시 쓰지 않는다)")
    void secondRunIsQuiet() {
        universe("058470");
        corp("058470", "00123456");
        noKisQuarters.add("058470");
        nineReports("00123456");
        service.collect();
        int callsBefore = calls.size();
        java.time.LocalDateTime firstWrite = quarter("202606").getCollectedAt();

        DartControllingFinancialService.Summary s = service.collect();

        assertThat(calls).hasSize(callsBefore);
        assertThat(s.quarterRows()).isZero();
        assertThat(quarter("202606").getCollectedAt()).isEqualTo(firstWrite);
    }

    @Test
    @DisplayName("KIS 빈 행(0·0·0)은 같은 자리에서 DART 행이 된다 — 유일 키가 기간이라 새 행을 넣으면 저장이 깨진다")
    void blankKisRowBecomesDartRow() {
        universe("058470");
        corp("058470", "00123456");
        noKisQuarters.add("058470");
        nineReports("00123456");
        StockQuarterlyFinancial blank = StockQuarterlyFinancial.builder().stockCode("058470").fiscalPeriod("202606")
                .periodEnd(LocalDate.of(2026, 6, 30)).cumulative(true).revenue(java.math.BigDecimal.ZERO)
                .operatingProfit(java.math.BigDecimal.ZERO).netIncome(java.math.BigDecimal.ZERO)
                .source(StockQuarterlyFinancial.SOURCE_KIS).build();
        quarters.add(blank);

        service.collect();

        assertThat(quarters.stream().filter(q -> q.getFiscalPeriod().equals("202606"))).hasSize(1).containsExactly(blank);
        assertThat(blank.isDart()).isTrue();
        assertThat(blank.isCumulative()).isFalse();
        assertThat(blank.getRevenue()).isEqualByComparingTo("12000");
    }

    @Test
    @DisplayName("V69 전에 받은 보고서는 대상 종목만 다시 받는다 — 매출·영업이익이 비어 있어서")
    void oldRowsAreRefetchedForTargets() {
        universe("058470");
        corp("058470", "00123456");
        noKisQuarters.add("058470");
        nineReports("00123456");
        storedCompany("058470", "12");
        DartControllingFinancial old = stored("058470", 2026, "11012", DartControllingFinancial.STATUS_OK, LocalDate.of(2026, 9, 1));
        old.setFsDiv("OFS");
        saved.add(old);

        service.collect();

        assertThat(calls).contains("00123456/2026/11012/OFS");
        assertThat(old.isIncomeParsed()).isTrue();
        assertThat(old.getRevenue()).isEqualByComparingTo("22000");
    }

    @Test
    @DisplayName("없는 보고서를 만나면 멈춘다 — 상장 전 보고서를 하나씩 헛호출하지 않는다")
    void stopsAtMissingReport() {
        universe("058470");
        corp("058470", "00123456");
        noKisQuarters.add("058470");
        nineReports("00123456");
        responses.remove("00123456/2025/11014/OFS");

        service.collect();

        assertThat(calls).contains("00123456/2025/11014/CFS", "00123456/2025/11014/OFS");
        assertThat(calls).noneMatch(c -> c.contains("/2025/11012/") || c.contains("/2024/"));
        assertThat(quarter("202606").getRevenue()).as("끊기기 전 분기는 저장한다").isEqualByComparingTo("12000");
    }

    @Test
    @DisplayName("12월 결산이 아니면 채우지 않는다 — 보고서를 달력 분기로 옮기는 규칙이 12월 기준이다")
    void nonDecemberNotFilled() {
        universe("058470");
        corp("058470", "00123456");
        noKisQuarters.add("058470");
        nineReports("00123456");
        responses.put("company/00123456", "{\"status\":\"000\",\"acc_mt\":\"03\"}");

        DartControllingFinancialService.Summary s = service.collect();

        assertThat(s.fillStocks()).isZero();
        assertThat(quarters).isEmpty();
    }

    @Test
    @DisplayName("재현(진코스텍): 단위를 잘못 공시한 보고서는 금액을 저장하지 않고 건너뛴다 — 다른 보고서는 받고, 그 보고서로 만든 분기는 지운다")
    void badScaleReportIsSkipped() {
        universe("250030");
        corp("250030", "00123456");
        noKisQuarters.add("250030");
        nineReports("00123456");
        // 시가총액 363억 — 2026 반기 자본이 26조로 온다
        when(financialRepo.findLatestMarketCaps()).thenReturn(java.util.Collections.singletonList(
                new Object[]{"250030", new java.math.BigDecimal("363")}));
        responses.put("00123456/2026/11012/OFS", statement("20260814000001", "30959868000000000", "3460394000000000", "2941897000000000",
                "26421203000000000", "60392734000000000", "1000000000000"));
        // 다른 보고서는 정상 규모 — 자본 200억
        for (String k : List.of("2026/11013", "2025/11011", "2025/11014", "2025/11012", "2025/11013", "2024/11011", "2024/11014", "2024/11012")) {
            responses.put("00123456/" + k + "/OFS", responses.get("00123456/" + k + "/OFS").replace("\"5000000000000\"", "\"20000000000\""));
        }
        StockQuarterlyFinancial wrong = StockQuarterlyFinancial.builder().stockCode("250030").fiscalPeriod("202606")
                .periodEnd(LocalDate.of(2026, 6, 30)).revenue(new java.math.BigDecimal("309463"))
                .source(StockQuarterlyFinancial.SOURCE_DART).build();
        quarters.add(wrong);

        DartControllingFinancialService.Summary s = service.collect();

        DartControllingFinancial bad = savedRow(2026, "11012");
        assertThat(bad.getStatus()).isEqualTo(DartControllingFinancial.STATUS_BAD_SCALE);
        assertThat(bad.getCtrlNetIncome()).isNull();
        assertThat(bad.getRevenue()).isNull();
        assertThat(bad.getFsDiv()).isEqualTo("OFS");
        assertThat(savedRow(2024, "11012").isIncomeParsed()).as("건너뛰고 옛 보고서까지 받는다").isTrue();
        assertThat(quarter("202603").getRevenue()).isEqualByComparingTo("10000");
        verify(quarterRepo).delete(wrong);
        assertThat(s.ok()).isEqualTo(8);
    }

    @Test
    @DisplayName("단위가 틀린 보고서는 일주일 뒤 다시 본다 — 회사가 정정하면 잡는다. 외화 재무제표는 다시 묻지 않는다")
    void badScaleIsRetried() {
        DartControllingFinancial bad = stored("250030", 2026, "11012", DartControllingFinancial.STATUS_BAD_SCALE, LocalDate.of(2026, 9, 20));
        DartControllingFinancial foreign = stored("241560", 2026, "11012", DartControllingFinancial.STATUS_NON_KRW, LocalDate.of(2026, 9, 1));

        assertThat(DartControllingFinancialService.retryDue(bad, LocalDate.of(2026, 9, 26))).isFalse();
        assertThat(DartControllingFinancialService.retryDue(bad, LocalDate.of(2026, 9, 27))).isTrue();
        assertThat(DartControllingFinancialService.retryDue(foreign, LocalDate.of(2026, 9, 27))).isFalse();
    }

    @Test
    @DisplayName("KIS 분기가 다 있는 회사는 채우지 않는다 — 호출은 종전처럼 두 건")
    void completeKisNotFilled() {
        universe("023590");
        corp("023590", "00176914");
        responses.put("00176914/2026/11012/CFS", cfs("20260814003900", "492382928525", "248744902085", "3883281985289"));
        responses.put("00176914/2025/11011/CFS", cfs("20260318001606", "505182368263", "355835455442", "3446435267021"));

        DartControllingFinancialService.Summary s = service.collect();

        assertThat(s.fillStocks()).isZero();
        assertThat(calls).hasSize(3);
    }

    @Test
    @DisplayName("부팅 따라잡기 — 보고서 표가 차 있어도 DART 분기 행이 하나도 없으면(V69 직후) 한 번 돈다")
    void catchUpRunsWhenNoDartRows() {
        universe("058470");
        corp("058470", "00123456");
        noKisQuarters.add("058470");
        nineReports("00123456");
        when(repo.count()).thenReturn(100L);
        when(companyRepo.count()).thenReturn(100L);
        when(dart.isCorpCodeLoaded()).thenReturn(true);
        when(quarterRepo.countBySource(StockQuarterlyFinancial.SOURCE_DART)).thenReturn(0L, 8L);
        when(repo.countByRevenuePrevIsNotNull()).thenReturn(8L);

        service.catchUpOnStartup();
        assertThat(quarters).isNotEmpty();

        int callsBefore = calls.size();
        service.catchUpOnStartup();
        assertThat(calls).hasSize(callsBefore);
    }

    @Test
    @DisplayName("부팅 따라잡기 — 비교 칸이 하나도 없으면(V71 직후) 한 번 돈다, 받은 뒤엔 안 돈다")
    void catchUpRunsAfterV71() {
        universe("058470");
        corp("058470", "00123456");
        noKisQuarters.add("058470");
        nineReports("00123456");
        when(repo.count()).thenReturn(100L);
        when(companyRepo.count()).thenReturn(100L);
        when(dart.isCorpCodeLoaded()).thenReturn(true);
        when(quarterRepo.countBySource(StockQuarterlyFinancial.SOURCE_DART)).thenReturn(8L);
        when(repo.countByRevenuePrevIsNotNull()).thenReturn(0L, 8L);

        service.catchUpOnStartup();
        assertThat(calls).isNotEmpty();

        int callsBefore = calls.size();
        service.catchUpOnStartup();
        assertThat(calls).hasSize(callsBefore);
    }

    private void corp(String stock, String corp) {
        when(dart.getCorpCodeByStockCode(stock)).thenReturn(corp);
    }

    private DartControllingFinancial stored(String stock, int year, String reprt, String status, LocalDate collected) {
        DartControllingFinancial row = DartControllingFinancial.builder().stockCode(stock).corpCode("C" + stock)
                .bsnsYear(year).reprtCode(reprt).status(status)
                .fsDiv(DartControllingFinancial.STATUS_OK.equals(status) ? "CFS" : null)
                .filedOn(DartControllingFinancial.STATUS_OK.equals(status) ? LocalDate.of(2026, 8, 14) : null)
                .collectedAt(collected.atStartOfDay()).build();
        saved.add(row);
        return row;
    }

    private void storedCompany(String stock, String month) {
        companies.add(DartCompany.builder().stockCode(stock).corpCode("C" + stock).fiscalMonth(month)
                .collectedAt(LocalDateTime.of(2026, 9, 1, 21, 30)).build());
    }

    private DartControllingFinancial savedRow(int year, String reprt) {
        return saved.stream().filter(r -> r.getBsnsYear() == year && r.getReprtCode().equals(reprt)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("달러 재무제표(두산밥캣) — 금액을 저장하지 않는다. 억 달러를 억원으로 읽으면 PER 12,312·PBR 929 가 된다(네이버 9.52·0.60)")
    void foreignCurrencyStatementsAreNotStored() {
        universe("241560");
        corp("241560", "01234567");
        responses.put("01234567/2026/11012/CFS", cfs("20260814000001", "22300000000", "16500000000", "510000000000")
                .replace("\"sj_div\"", "\"currency\":\"USD\",\"sj_div\""));

        service.collect();

        DartControllingFinancial row = savedRow(2026, "11012");
        assertThat(row.getStatus()).isEqualTo(DartControllingFinancial.STATUS_NON_KRW);
        assertThat(row.getFsDiv()).as("어느 재무제표였는지는 남긴다").isEqualTo("CFS");
        assertThat(row.getCtrlNetIncome()).isNull();
        assertThat(row.getCtrlEquity()).isNull();
        assertThat(saved).noneMatch(r -> DartControllingFinancial.STATUS_OK.equals(r.getStatus()));

        int callsAfterFirstRun = calls.size();
        service.collect();
        assertThat(calls.stream().skip(callsAfterFirstRun).filter(c -> c.contains("/2026/11012/")))
                .as("같은 보고서를 다시 묻지 않는다").isEmpty();
    }

    @Test
    @DisplayName("처음 보는 종목 — 2026 반기 + 2025 사업보고서 두 건만 받는다")
    void freshStock() {
        universe("023590");
        corp("023590", "00176914");
        responses.put("00176914/2026/11012/CFS", cfs("20260814003900", "492382928525", "248744902085", "3883281985289"));
        responses.put("00176914/2025/11011/CFS", cfs("20260318001606", "505182368263", "355835455442", "3446435267021"));

        DartControllingFinancialService.Summary s = service.collect();

        assertThat(calls).containsExactly("company/00176914", "00176914/2026/11012/CFS", "00176914/2025/11011/CFS");
        assertThat(companies).extracting(DartCompany::getFiscalMonth).containsExactly("12");
        assertThat(savedRow(2026, "11012").getCtrlNetIncome()).isEqualByComparingTo("4923.83");
        assertThat(savedRow(2026, "11012").getCtrlEquity()).isEqualByComparingTo("38832.82");
        assertThat(savedRow(2026, "11012").getFiledOn()).isEqualTo(LocalDate.of(2026, 8, 14));
        assertThat(savedRow(2025, "11011").getCtrlNetIncome()).isEqualByComparingTo("5051.82");
        assertThat(s.ok()).isEqualTo(2);
        assertThat(s.abortReason()).isNull();
        // 저장된 두 건으로 TTM 이 나온다
        assertThat(ControllingEarnings.ttm(saved.stream().map(DartControllingFinancial::toReport).toList(),
                "12", LocalDate.of(2026, 9, 30)).netIncome()).isEqualByComparingTo("7488.20");
    }

    @Test
    @DisplayName("연결 자료가 없으면(013) 별도로 — 받은 뒤엔 짝 보고서도 별도부터 묻는다")
    void separateOnlyCompany() {
        universe("007370");
        corp("007370", "00150536");
        responses.put("00150536/2026/11012/OFS", ofs("20260813000100", "4500000000", "3000000000", "90000000000"));
        responses.put("00150536/2025/11011/OFS", ofs("20260320000200", "8000000000", "7000000000", "86000000000"));

        service.collect();

        assertThat(calls).containsExactly("company/00150536",
                "00150536/2026/11012/CFS", "00150536/2026/11012/OFS", "00150536/2025/11011/OFS");
        assertThat(savedRow(2026, "11012").getFsDiv()).isEqualTo("OFS");
        assertThat(savedRow(2026, "11012").getCtrlNetIncome()).as("별도는 당기순이익이 곧 지배주주").isEqualByComparingTo("45.00");
    }

    @Test
    @DisplayName("반기보고서가 아직 없으면 NO_DATA 로 적고 1분기로 — 짝은 2025 사업보고서")
    void notFiledYetFallsBackToPreviousQuarter() {
        universe("111111");
        corp("111111", "C111111");
        responses.put("C111111/2026/11013/CFS", cfs("20260515000001", "21060000000", "10210000000", "500000000000"));
        responses.put("C111111/2025/11011/CFS", cfs("20260331000002", "60000000000", "50000000000", "480000000000"));

        DartControllingFinancialService.Summary s = service.collect();

        assertThat(calls).containsExactly("company/C111111", "C111111/2026/11012/CFS", "C111111/2026/11012/OFS",
                "C111111/2026/11013/CFS", "C111111/2025/11011/CFS");
        assertThat(savedRow(2026, "11012").getStatus()).isEqualTo(DartControllingFinancial.STATUS_NO_DATA);
        assertThat(savedRow(2026, "11013").getStatus()).isEqualTo(DartControllingFinancial.STATUS_OK);
        assertThat(s.noData()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 받은 보고서는 다시 받지 않는다 — 정기보고서는 한 번 나오면 그대로다")
    void storedReportsAreNotRefetched() {
        universe("023590");
        corp("023590", "00176914");
        stored("023590", 2026, "11012", DartControllingFinancial.STATUS_OK, LocalDate.of(2026, 8, 20));
        stored("023590", 2025, "11011", DartControllingFinancial.STATUS_OK, LocalDate.of(2026, 4, 1));
        storedCompany("023590", "12");

        DartControllingFinancialService.Summary s = service.collect();

        assertThat(calls).isEmpty();
        assertThat(s.calls()).isZero();
    }

    @Test
    @DisplayName("NO_DATA 는 7일 뒤에 다시 — 그 사이엔 이전 분기로 TTM 을 만든다")
    void noDataRetryInterval() {
        universe("111111");
        corp("111111", "C111111");
        DartControllingFinancial h1 = stored("111111", 2026, "11012", DartControllingFinancial.STATUS_NO_DATA, LocalDate.of(2026, 9, 27));
        stored("111111", 2026, "11013", DartControllingFinancial.STATUS_OK, LocalDate.of(2026, 5, 20));
        stored("111111", 2025, "11011", DartControllingFinancial.STATUS_OK, LocalDate.of(2026, 4, 1));
        storedCompany("111111", "12");

        service.collect();
        assertThat(calls).as("3일 전 NO_DATA — 아직 안 묻는다").isEmpty();

        h1.setCollectedAt(LocalDateTime.of(2026, 9, 23, 21, 30));
        responses.put("C111111/2026/11012/CFS", cfs("20260925000003", "40000000000", "20000000000", "510000000000"));
        service.collect();
        assertThat(calls).as("7일 지남 — 다시 묻고, 받으면 OK 로 바뀐다").containsExactly("C111111/2026/11012/CFS");
        assertThat(h1.getStatus()).isEqualTo(DartControllingFinancial.STATUS_OK);
        assertThat(h1.getCtrlNetIncome()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("한도 초과(020)면 회차를 멈춘다 — 나머지 종목을 헛호출하지 않는다")
    void rateLimitAborts() {
        universe("023590", "005930");
        corp("023590", "00176914");
        corp("005930", "00126380");
        responses.put("00176914/2026/11012/CFS", "{\"status\":\"020\",\"message\":\"요청 제한을 초과하였습니다.\"}");

        DartControllingFinancialService.Summary s = service.collect();

        assertThat(calls).containsExactly("company/00176914", "00176914/2026/11012/CFS");
        assertThat(s.abortReason()).contains("020");
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("네트워크 실패는 '데이터 없음'으로 적지 않는다 — 다음 종목은 계속")
    void networkFailureIsNotNoData() {
        universe("023590", "005930");
        corp("023590", "00176914");
        corp("005930", "00126380");
        responses.put("00176914/2026/11012/CFS", "THROW");
        responses.put("00126380/2026/11012/CFS", cfs("20260814000001", "118370700000000", "50000000000000", "565064700000000"));
        responses.put("00126380/2025/11011/CFS", cfs("20260310000001", "150000000000000", "90000000000000", "520000000000000"));

        DartControllingFinancialService.Summary s = service.collect();

        assertThat(saved).extracting(DartControllingFinancial::getStockCode).containsOnly("005930");
        assertThat(s.failed()).isEqualTo(1);
        assertThat(s.ok()).isEqualTo(2);
    }

    @Test
    @DisplayName("결산월은 한 번 받아 둔다 — 3월 결산(동원모빌리티)도 그대로 적고, 순이익 판단은 계산 쪽이 한다")
    void fiscalMonthStored() {
        universe("018500");
        corp("018500", "00118008");
        responses.put("company/00118008", "{\"status\":\"000\",\"acc_mt\":\"03\"}");

        service.collect();
        service.collect();

        assertThat(companies).extracting(DartCompany::getFiscalMonth).containsExactly("03");
        assertThat(calls.stream().filter(c -> c.startsWith("company/"))).as("두 번째 회차엔 다시 묻지 않는다").hasSize(1);
    }

    @Test
    @DisplayName("기업개황이 한도 초과(020)면 회차를 멈춘다 — 보고서도 부르지 않는다")
    void companyRateLimitAborts() {
        universe("023590", "005930");
        corp("023590", "00176914");
        corp("005930", "00126380");
        responses.put("company/00176914", "{\"status\":\"020\",\"message\":\"요청 제한을 초과하였습니다.\"}");

        DartControllingFinancialService.Summary s = service.collect();

        assertThat(calls).containsExactly("company/00176914");
        assertThat(s.abortReason()).contains("020");
        assertThat(companies).isEmpty();
    }

    @Test
    @DisplayName("DART 기업코드가 없는 종목(우선주 등)은 건너뛴다")
    void noCorpCodeSkipped() {
        universe("005935");
        DartControllingFinancialService.Summary s = service.collect();
        assertThat(calls).isEmpty();
        assertThat(s.skippedNoCorp()).isEqualTo(1);
    }

    @Test
    @DisplayName("키가 없으면 아무것도 부르지 않는다")
    void noKey() {
        ReflectionTestUtils.setField(service, "dartApiKey", "");
        universe("023590");
        corp("023590", "00176914");
        DartControllingFinancialService.Summary s = service.collect();
        assertThat(calls).isEmpty();
        assertThat(s.abortReason()).isEqualTo("NO_KEY");
    }

    @Test
    @DisplayName("같은 회사가 목록에 두 번 있어도 한 번만")
    void duplicateCodesOnce() {
        universe("023590", "023590");
        corp("023590", "00176914");
        responses.put("00176914/2026/11012/CFS", cfs("20260814003900", "492382928525", "248744902085", "3883281985289"));
        responses.put("00176914/2025/11011/CFS", cfs("20260318001606", "505182368263", "355835455442", "3446435267021"));
        service.collect();
        assertThat(calls).hasSize(3);
        assertThat(saved).hasSize(2);
        assertThat(companies).hasSize(1);
    }
}
