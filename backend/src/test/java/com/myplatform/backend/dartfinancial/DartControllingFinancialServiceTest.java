package com.myplatform.backend.dartfinancial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.repository.StockFinancialDataRepository;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DART 지배주주 수집 — {@link DartControllingFinancialService}(2026-09-30). 오늘 = 2026-09-30 → 최신 후보는 2026 반기
 * (기한 8/14), 짝은 2025 사업보고서. 응답은 실제 모양({@code status}·{@code list}), 호출은 기록해 센다.
 */
class DartControllingFinancialServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private DartControllingFinancialRepository repo;
    private DartService dart;
    private StockFinancialDataRepository financialRepo;
    private RestTemplate rest;
    private DartControllingFinancialService service;

    /** 저장된 행(가짜 저장소) · 응답표(키 = corp/year/reprt/fs) · 호출 기록. */
    private final List<DartControllingFinancial> saved = new ArrayList<>();
    private final Map<String, String> responses = new HashMap<>();
    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = mock(DartControllingFinancialRepository.class);
        dart = mock(DartService.class);
        financialRepo = mock(StockFinancialDataRepository.class);
        rest = mock(RestTemplate.class);
        Clock clock = Clock.fixed(LocalDate.of(2026, 9, 30).atTime(21, 30).atZone(KST).toInstant(), KST);
        service = new DartControllingFinancialService(repo, dart, financialRepo, rest, new ObjectMapper(), clock);
        ReflectionTestUtils.setField(service, "dartApiKey", "test-key");
        service.callIntervalMillis = 0;

        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(saved));
        when(repo.save(any(DartControllingFinancial.class))).thenAnswer(inv -> {
            DartControllingFinancial row = inv.getArgument(0);
            if (!saved.contains(row)) saved.add(row);
            return row;
        });
        when(rest.getForObject(any(URI.class), eq(String.class))).thenAnswer(inv -> {
            URI uri = inv.getArgument(0);
            Map<String, List<String>> q = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
            String key = q.get("corp_code").get(0) + "/" + q.get("bsns_year").get(0) + "/"
                    + q.get("reprt_code").get(0) + "/" + q.get("fs_div").get(0);
            assertThat(q.get("crtfc_key")).containsExactly("test-key");
            calls.add(key);
            String body = responses.get(key);
            if ("THROW".equals(body)) throw new ResourceAccessException("timeout");
            return body != null ? body : NO_DATA;
        });
    }

    private static final String NO_DATA = "{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다.\"}";

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
        when(financialRepo.findAllStockCodes()).thenReturn(List.of(codes));
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

    private DartControllingFinancial savedRow(int year, String reprt) {
        return saved.stream().filter(r -> r.getBsnsYear() == year && r.getReprtCode().equals(reprt)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("처음 보는 종목 — 2026 반기 + 2025 사업보고서 두 건만 받는다")
    void freshStock() {
        universe("023590");
        corp("023590", "00176914");
        responses.put("00176914/2026/11012/CFS", cfs("20260814003900", "492382928525", "248744902085", "3883281985289"));
        responses.put("00176914/2025/11011/CFS", cfs("20260318001606", "505182368263", "355835455442", "3446435267021"));

        DartControllingFinancialService.Summary s = service.collect();

        assertThat(calls).containsExactly("00176914/2026/11012/CFS", "00176914/2025/11011/CFS");
        assertThat(savedRow(2026, "11012").getCtrlNetIncome()).isEqualByComparingTo("4923.83");
        assertThat(savedRow(2026, "11012").getCtrlEquity()).isEqualByComparingTo("38832.82");
        assertThat(savedRow(2026, "11012").getFiledOn()).isEqualTo(LocalDate.of(2026, 8, 14));
        assertThat(savedRow(2025, "11011").getCtrlNetIncome()).isEqualByComparingTo("5051.82");
        assertThat(s.ok()).isEqualTo(2);
        assertThat(s.abortReason()).isNull();
        // 저장된 두 건으로 TTM 이 나온다
        assertThat(ControllingEarnings.ttm(saved.stream().map(DartControllingFinancial::toReport).toList(),
                LocalDate.of(2026, 9, 30)).netIncome()).isEqualByComparingTo("7488.20");
    }

    @Test
    @DisplayName("연결 자료가 없으면(013) 별도로 — 받은 뒤엔 짝 보고서도 별도부터 묻는다")
    void separateOnlyCompany() {
        universe("007370");
        corp("007370", "00150536");
        responses.put("00150536/2026/11012/OFS", ofs("20260813000100", "4500000000", "3000000000", "90000000000"));
        responses.put("00150536/2025/11011/OFS", ofs("20260320000200", "8000000000", "7000000000", "86000000000"));

        service.collect();

        assertThat(calls).containsExactly("00150536/2026/11012/CFS", "00150536/2026/11012/OFS", "00150536/2025/11011/OFS");
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

        assertThat(calls).containsExactly("C111111/2026/11012/CFS", "C111111/2026/11012/OFS",
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

        assertThat(calls).containsExactly("00176914/2026/11012/CFS");
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
        assertThat(calls).hasSize(2);
        assertThat(saved).hasSize(2);
    }
}
