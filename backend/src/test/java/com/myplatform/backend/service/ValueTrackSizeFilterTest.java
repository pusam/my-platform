package com.myplatform.backend.service;

import com.myplatform.backend.controller.RecommendationController;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockMasterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 저평가 목록의 시가총액 하한 보기(2026-11-19, 사용자 "저평가 종목·국내 주도주를 원한다").
 *
 * <p>재현(11/18 운영 자료): 만점(20) 조건을 채우는 약 165종목 중 146개가 시가총액 3천억 원 미만(1천억 미만 99), 1~10조 3개,
 * 10조 이상 0 이고 동점은 PER 낮은 순이라 상위 10 이 전부 작은 회사였다(오성첨단소재 682억·SG&G 441억·마니커에프앤지 316억 …).
 * 큰 회사 가운데 싼 종목은 목록에서 볼 길이 없었다. 하한을 고르면 점수·순서는 그대로 두고 대상만 줄인다.
 */
class ValueTrackSizeFilterTest {

    private RecommendationService service;
    private StockFinancialDataRepository financials;

    @BeforeEach
    void setUp() {
        service = mock(RecommendationService.class, CALLS_REAL_METHODS);
        financials = mock(StockFinancialDataRepository.class);
        StockStatusService status = mock(StockStatusService.class);
        when(status.isActive(anyString())).thenReturn(true);
        when(status.shareCountRatio(anyString())).thenReturn(BigDecimal.ONE);
        RiskManagementService risk = mock(RiskManagementService.class);
        when(risk.quickDangerCheck(anyString(), anyString())).thenReturn(false);
        StockMasterRepository master = mock(StockMasterRepository.class);
        when(master.findAll()).thenReturn(List.of());
        StockPriceService prices = mock(StockPriceService.class);
        when(prices.getStockPricesFromCacheOnly(anyList())).thenReturn(Map.of());
        ReflectionTestUtils.setField(service, "financialDataRepository", financials);
        ReflectionTestUtils.setField(service, "stockStatusService", status);
        ReflectionTestUtils.setField(service, "riskManagementService", risk);
        ReflectionTestUtils.setField(service, "stockMasterRepository", master);
        ReflectionTestUtils.setField(service, "stockPriceService", prices);
        // 목으로 만든 서비스는 필드 초기화를 건너뛴다 — 하한별 캐시를 직접 넣는다
        ReflectionTestUtils.setField(service, "valueSizedCache", new java.util.concurrent.ConcurrentHashMap<>());
        when(financials.findRecentPerStock(anyInt())).thenReturn(rows());
    }

    /** 재무 일별 행 — 영업이익·순이익을 시가총액÷PER 에 맞춰 이익의 질 판정에 걸리지 않게 둔다. 금액 억원. */
    private static StockFinancialData row(String code, String name, String cap, String per, String pbr, String roe, String debt) {
        StockFinancialData f = new StockFinancialData();
        f.setStockCode(code);
        f.setStockName(name);
        f.setReportDate(LocalDate.now());
        BigDecimal perV = new BigDecimal(per);
        f.setPer(perV);
        f.setPbr(new BigDecimal(pbr));
        f.setRoe(new BigDecimal(roe));
        f.setDebtRatio(new BigDecimal(debt));
        BigDecimal income = new BigDecimal("100");
        if (cap != null) {
            f.setMarketCap(new BigDecimal(cap));
            income = new BigDecimal(cap).divide(perV, 2, java.math.RoundingMode.HALF_UP);
        }
        f.setNetIncome(income);
        f.setOperatingProfit(income);
        f.setRevenue(income.multiply(BigDecimal.TEN));
        f.setTotalEquity(new BigDecimal("1000"));
        return f;
    }

    private static List<StockFinancialData> rows() {
        return List.of(
                // 작은 회사 — 만점(20), PER 이 가장 낮아 전체 목록의 맨 위
                row("900001", "소형A", "682", "0.5", "0.20", "20.0", "20"),
                row("900002", "소형B", "441", "0.6", "0.30", "20.0", "20"),
                row("900003", "소형C", "2999.99", "0.9", "0.30", "20.0", "20"),
                // 시가총액을 모르는 종목 — 만점. 전체 목록엔 있고 하한을 건 목록엔 없다
                row("900004", "시총모름", null, "0.7", "0.30", "20.0", "20"),
                // 중형 — 만점
                row("300001", "중형A", "3000", "4.0", "0.50", "12.0", "40"),
                row("300002", "중형B", "8000", "3.0", "0.60", "12.0", "40"),
                // 대형 — 만점 하나, 점수 낮은 것 하나(PBR 1.2 → 4점, ROE÷PBR 10 → 4점, 부채 3점, 흑자 3점 = 14)
                row("100001", "대형A", "150000", "6.0", "0.65", "11.0", "45"),
                row("100002", "대형B", "5000000", "9.0", "1.20", "12.0", "80"));
    }

    private List<RecommendationService.RecommendationDto> calc(String minCap) {
        return ReflectionTestUtils.invokeMethod(service, "calculateValueTop10For", minCap == null ? null : new BigDecimal(minCap));
    }

    private static List<String> codes(List<RecommendationService.RecommendationDto> items) {
        return items.stream().map(RecommendationService.RecommendationDto::getStockCode).toList();
    }

    @Test
    @DisplayName("재현: 하한이 없으면 만점 동점이 PER 낮은 순이라 작은 회사가 위를 채운다 — 종전 목록 그대로")
    void fullListStartsWithSmallCaps() {
        List<RecommendationService.RecommendationDto> all = calc(null);

        assertThat(codes(all)).containsExactly("900001", "900002", "900004", "900003", "300002", "300001", "100001", "100002");
        List<RecommendationService.RecommendationDto> legacy = ReflectionTestUtils.invokeMethod(service, "calculateValueTop10");
        assertThat(codes(legacy)).isEqualTo(codes(all));
    }

    @Test
    @DisplayName("3천억 이상 — 대상만 줄고 점수·순서 규칙은 같다(만점 동점은 PER 낮은 순). 경계값 3,000억은 들어가고 2,999.99억은 빠진다")
    void midCapViewKeepsScoreAndOrderRules() {
        List<RecommendationService.RecommendationDto> mid = calc("3000");

        assertThat(codes(mid)).containsExactly("300002", "300001", "100001", "100002");
        assertThat(mid.get(0).getValueStability()).isEqualTo(20);
        assertThat(mid.get(3).getValueStability()).isEqualTo(14);
    }

    @Test
    @DisplayName("1조 이상 — 큰 회사만. 시가총액을 모르는 종목은 하한을 건 목록에 넣지 않는다")
    void largeCapViewExcludesUnknownSize() {
        assertThat(codes(calc("10000"))).containsExactly("100001", "100002");
        assertThat(codes(calc("3000"))).doesNotContain("900004");
        assertThat(codes(calc(null))).contains("900004");
    }

    @Test
    @DisplayName("카드에 시가총액(억 원)을 싣는다 — 모르면 null(0 아님)")
    void cardCarriesMarketCap() {
        List<RecommendationService.RecommendationDto> all = calc(null);

        assertThat(all.get(0).getValueMarketCapEok()).isEqualByComparingTo("682");
        assertThat(all.stream().filter(d -> d.getStockCode().equals("900004")).findFirst().orElseThrow()
                .getValueMarketCapEok()).isNull();
        assertThat(all.stream().filter(d -> d.getStockCode().equals("900003")).findFirst().orElseThrow()
                .getValueMarketCapEok()).isEqualByComparingTo("3000");   // 2,999.99 → 표시는 억 단위 반올림
    }

    @Test
    @DisplayName("하한을 건 목록은 전체 목록 캐시를 건드리지 않는다 — 종합판단 보드·재료 워밍이 읽는 목록은 그대로다")
    void sizedViewDoesNotTouchTheFullListCache() {
        RecommendationService.Top5Response large = service.getValueTop10BySize(10000);

        assertThat(large.isDataAvailable()).isTrue();
        assertThat(codes(large.getItems())).containsExactly("100001", "100002");
        assertThat(ReflectionTestUtils.getField(service, "cachedValueTop10")).isNull();

        RecommendationService.Top5Response full = service.getValueTop10BySize(0);   // 0 = 종전 목록
        assertThat(codes(full.getItems())).startsWith("900001", "900002");
        assertThat(codes(service.getValueTop10().getItems())).isEqualTo(codes(full.getItems()));
        // 하한 목록은 30분 캐시 — 다시 불러도 같은 목록
        assertThat(codes(service.getValueTop10BySize(10000).getItems())).containsExactly("100001", "100002");
    }

    @Test
    @DisplayName("정해 둔 선택지(0·3,000·10,000억) 밖의 하한은 받지 않는다 — 모르는 값에 다른 목록을 조용히 주지 않는다")
    void onlyKnownChoices() {
        assertThat(RecommendationService.VALUE_MIN_CAP_CHOICES_EOK).containsExactly(0, 3000, 10000);
        assertThatThrownBy(() -> service.getValueTop10BySize(5000)).isInstanceOf(IllegalArgumentException.class);

        RecommendationService mocked = mock(RecommendationService.class);
        RecommendationController controller = new RecommendationController(mocked, mock(JudgmentBoardService.class));
        assertThat(controller.getValueTop10(5000).getStatusCode().value()).isEqualTo(400);
        verify(mocked, never()).getValueTop10BySize(anyInt());
    }

    @Test
    @DisplayName("응답에 고른 하한과 선택지를 싣는다")
    @SuppressWarnings("unchecked")
    void responseCarriesChoice() {
        RecommendationService mocked = mock(RecommendationService.class);
        when(mocked.getValueTop10BySize(3000)).thenReturn(
                new RecommendationService.Top5Response(List.of(), "10:00 기준", true, Map.of()));

        Map<String, Object> body = (Map<String, Object>) new RecommendationController(mocked, mock(JudgmentBoardService.class))
                .getValueTop10(3000).getBody();

        assertThat(body).containsEntry("minCapEok", 3000).containsEntry("dataAvailable", true)
                .containsEntry("minCapChoicesEok", List.of(0, 3000, 10000));
    }
}
