package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dartfinancial.DartCompanyRepository;
import com.myplatform.backend.dartfinancial.DartControllingFinancialRepository;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockQuarterlyFinancialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 같은 날 두 번째 재무 수집(15:38)이 손익계산서·재무상태표를 못 받았을 때(2026-10-19, 리뷰 에이전트 지적).
 *
 * <p>재현: 08:30 회차가 받은 손익 TTM(순이익·매출·영업이익)을 15:38 회차가 조회 실패 한 번에 null 로 덮었고, PER·EPS·ROE 도
 * KIS 현재가 API 의 연간 값으로 돌아갔다(per_basis CONSOL → KIS). 그 종목은 다음 수집까지 마법의 공식(영업이익률 &gt; 0 — AI 스윙·
 * 모닝브리핑 TOP 3)에서 빠졌고, PEG 의 이익의 질 판정은 매출·영업이익이 없어 '모름'으로 통과했다. 재무상태표는 칸 자체는
 * 지우지 않았지만(부채비율과 같은 규칙) BPS·PBR·ROE 를 다시 만들 때 쓰지 않아 PBR 이 KIS 값으로 돌아갔다.
 * 이제 이번 회차 값이 없으면 같은 날 앞 회차 값으로 계산한다 — 다른 날 행은 쓰지 않는다.
 */
class FinancialSameDayCarryTest {

    private StockFinancialDataRepository dailyRepo;
    private StockFinancialDataCollector collector;
    private StockFinancialData row;

    @BeforeEach
    void setUp() throws Exception {
        dailyRepo = mock(StockFinancialDataRepository.class);
        DartControllingFinancialRepository dartRepo = mock(DartControllingFinancialRepository.class);
        KoreaInvestmentService kis = mock(KoreaInvestmentService.class);
        collector = spy(new StockFinancialDataCollector(dailyRepo, mock(StockQuarterlyFinancialRepository.class), kis,
                mock(RestTemplate.class), new ObjectMapper(), mock(StockMasterService.class), dartRepo,
                mock(DartCompanyRepository.class)));
        when(dartRepo.findByStockCodeAndStatus(anyString(), anyString())).thenReturn(List.of());
        when(kis.getAccessToken()).thenReturn("token");
        // 현재가 API — KIS 연간 EPS·BPS·PER·PBR(지배주주 최근 결산)
        when(kis.getStockPrice("078930")).thenReturn(new ObjectMapper().readTree(
                "{\"rt_cd\":\"0\",\"output\":{\"hts_kor_isnm\":\"GS\",\"stck_prpr\":\"105000\",\"hts_avls\":\"97819\","
                        + "\"per\":\"4.20\",\"pbr\":\"0.60\",\"eps\":\"25000\",\"bps\":\"175000\",\"lstn_stcn\":\"92915378\"}}"));
    }

    /** 08:30 회차가 저장해 둔 같은 날 행. */
    private void morningRow() {
        row = new StockFinancialData();
        row.setNetIncome(new BigDecimal("30000"));
        row.setRevenue(new BigDecimal("500000"));
        row.setOperatingProfit(new BigDecimal("40000"));
        row.setOperatingMargin(new BigDecimal("8.00"));
        row.setTotalEquity(new BigDecimal("210252"));
        row.setPerBasis("CONSOL");
        when(dailyRepo.findByStockCodeAndReportDate(anyString(), any())).thenReturn(Optional.of(row));
    }

    private void ratios(boolean income, boolean balance) {
        Map<String, BigDecimal> r = new HashMap<>();
        if (income) {
            r.put("netIncome", new BigDecimal("30000"));
            r.put("revenue", new BigDecimal("500000"));
            r.put("operatingProfit", new BigDecimal("40000"));
            r.put("operatingMargin", new BigDecimal("8.00"));
        }
        if (balance) {
            r.put("totalEquity", new BigDecimal("210252"));
            r.put("totalDebt", new BigDecimal("168161"));
        }
        doReturn(r).when(collector).getFinancialRatios(anyString(), anyString());
    }

    @Test
    @DisplayName("재현: 15:38 에 손익계산서를 못 받으면 08:30 의 TTM 을 지우지 않고 그것으로 PER·ROE 를 만든다 — KIS 연간 값으로 되돌리지 않는다")
    void incomeFailureKeepsSameDayTtm() {
        morningRow();
        ratios(false, true);

        assertThat(collector.collectStockFinancialDataSimple("078930")).isTrue();

        assertThat(row.getNetIncome()).isEqualByComparingTo("30000");
        assertThat(row.getRevenue()).isEqualByComparingTo("500000");
        assertThat(row.getOperatingProfit()).isEqualByComparingTo("40000");
        assertThat(row.getOperatingMargin()).isEqualByComparingTo("8.00");
        assertThat(row.getPerBasis()).isEqualTo("CONSOL");
        // EPS = 30,000억 ÷ 92,915,378주 = 32,287원 → PER = 105,000 ÷ 32,287 = 3.3 (KIS 연간 4.20 이 아니다)
        assertThat(row.getEps()).isEqualByComparingTo("32287");
        assertThat(row.getPer()).isEqualByComparingTo("3.3");
        // ROE = 30,000 ÷ 210,252 = 14.27%
        assertThat(row.getRoe()).isEqualByComparingTo("14.27");
    }

    @Test
    @DisplayName("재현: 15:38 에 재무상태표를 못 받으면 같은 날 자본총계로 BPS·PBR 을 만든다 — KIS PBR 0.60 으로 돌아가지 않는다")
    void balanceFailureKeepsSameDayEquity() {
        morningRow();
        ratios(true, false);

        assertThat(collector.collectStockFinancialDataSimple("078930")).isTrue();

        // BPS = 210,252억 ÷ 92,915,378주 = 226,283원 → PBR = 105,000 ÷ 226,283 = 0.46
        assertThat(row.getBps()).isEqualByComparingTo("226283");
        assertThat(row.getPbr()).isEqualByComparingTo("0.46");
        assertThat(row.getRoe()).isEqualByComparingTo("14.27");
    }

    @Test
    @DisplayName("이번 회차가 받은 값이 있으면 그 값을 쓴다 — 앞 회차 값은 못 받았을 때만")
    void freshValuesWin() {
        morningRow();
        Map<String, BigDecimal> r = new HashMap<>();
        r.put("netIncome", new BigDecimal("33000"));
        r.put("revenue", new BigDecimal("520000"));
        r.put("operatingProfit", new BigDecimal("41600"));
        r.put("operatingMargin", new BigDecimal("8.00"));
        r.put("totalEquity", new BigDecimal("220000"));
        doReturn(r).when(collector).getFinancialRatios(anyString(), anyString());

        assertThat(collector.collectStockFinancialDataSimple("078930")).isTrue();

        assertThat(row.getNetIncome()).isEqualByComparingTo("33000");
        assertThat(row.getTotalEquity()).isEqualByComparingTo("220000");
    }

    @Test
    @DisplayName("같은 날 행이 없으면(그날 첫 회차) 다른 날 값을 끌어오지 않는다 — 못 받은 것은 모른다")
    void noSameDayRowNoCarry() {
        StockFinancialData fresh = new StockFinancialData();
        when(dailyRepo.findByStockCodeAndReportDate(anyString(), any())).thenReturn(Optional.empty());
        when(dailyRepo.save(any())).thenAnswer(inv -> {
            StockFinancialData saved = inv.getArgument(0);
            fresh.setNetIncome(saved.getNetIncome());
            fresh.setPerBasis(saved.getPerBasis());
            fresh.setPer(saved.getPer());
            return saved;
        });
        ratios(false, false);

        assertThat(collector.collectStockFinancialDataSimple("078930")).isTrue();

        assertThat(fresh.getNetIncome()).isNull();
        assertThat(fresh.getPerBasis()).isEqualTo("KIS");
        assertThat(fresh.getPer()).isEqualByComparingTo("4.20");
    }
}
