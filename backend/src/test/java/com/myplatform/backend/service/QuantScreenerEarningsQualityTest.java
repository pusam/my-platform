package com.myplatform.backend.service;

import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 마법의 공식·PEG 후보에서 이익의 질이 무너진 종목을 뺀다 — AI 스윙·가치의 후보 풀이 이 둘이다(2026-09-30).
 *
 * <p>2026-09-29 22:54 AI 스윙 스냅샷 5종목이 전부 100점: 이오플로우(정지·영업이익률 3,995%)·베뉴지(순이익이
 * 영업이익의 24배)·삼부토건(정지·7배)·진양제약·기도산업. 점수 산식(ROE 20%·영업이익률 15%·PER 5 에서 만점)은
 * 그대로 두고, 왜곡된 입력이 후보가 되지 않게 한다. 값은 운영 실측(억원, TTM).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuantScreenerEarningsQualityTest {

    @Mock private StockFinancialDataRepository stockFinancialDataRepository;
    @Mock private TelegramNotificationService telegramNotificationService;
    @Mock private KoreaInvestmentService koreaInvestmentService;
    @Mock private StockPriceService stockPriceService;
    @Mock private StockStatusService stockStatusService;

    @InjectMocks private QuantScreenerService service;

    private static BigDecimal dec(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static StockFinancialData magic(String code, String name, String per, String roe, String opMargin,
                                            String revenue, String operatingProfit, String netIncome) {
        return StockFinancialData.builder()
                .stockCode(code).stockName(name)
                .reportDate(LocalDate.of(2026, 9, 29))
                .per(dec(per)).roe(dec(roe)).operatingMargin(dec(opMargin))
                .revenue(dec(revenue)).operatingProfit(dec(operatingProfit)).netIncome(dec(netIncome))
                .marketCap(new BigDecimal("600"))
                .build();
    }

    private static StockFinancialData peg(String code, String name, String per, String roe, String peg,
                                          String growth, String marketCap,
                                          String revenue, String operatingProfit, String netIncome) {
        return StockFinancialData.builder()
                .stockCode(code).stockName(name)
                .reportDate(LocalDate.of(2026, 9, 29))
                .per(dec(per)).roe(dec(roe)).peg(dec(peg)).epsGrowth(dec(growth))
                .marketCap(dec(marketCap))
                .revenue(dec(revenue)).operatingProfit(dec(operatingProfit)).netIncome(dec(netIncome))
                .build();
    }

    @Test
    @DisplayName("마법의 공식 — 영업외 이익(베뉴지)·매출 초과 영업이익(이오플로우)은 빠지고, 판정 불가(진양제약)는 남는다")
    void magicFormulaExcludesDistortedEarnings() {
        when(stockStatusService.isActive(anyString())).thenReturn(true);   // 게이트와 분리해 이 규칙만 본다
        when(stockFinancialDataRepository.findForMagicFormula(any())).thenReturn(List.of(
                magic("019010", "베뉴지", "0.70", "51.65", "28.97", "504", "146", "3564"),
                magic("294090", "이오플로우", "1.00", "40.52", "3995.00", "20", "799", "603"),
                magic("007370", "진양제약", "1.70", "28.47", "34.86", null, null, null),
                magic("052790", "액토즈소프트", "1.00", "17.92", "51.08", "742", "379", "531"),
                magic("019180", "티에이치엔", "1.20", "27.59", "6.98", "11302", "789", "750")));

        List<ScreenerResultDto> results = service.getMagicFormulaStocks(10, null);

        assertThat(results).extracting(ScreenerResultDto::getStockCode)
                .doesNotContain("019010", "294090")
                .containsExactlyInAnyOrder("007370", "052790", "019180");
        assertThat(results).extracting(ScreenerResultDto::getMagicFormulaRank)
                .as("왜곡 종목을 뺀 뒤 순위를 매긴다 — 빈 번호가 없다")
                .containsExactlyInAnyOrder(1, 2, 3);
    }

    /** 10/7 운영 KIS 일별 행 그대로 — 시가총액까지(PER 이 쓴 순이익 = 시총 ÷ PER). */
    private static StockFinancialData magicWithCap(String code, String name, String per, String roe, String revenue,
                                                   String operatingProfit, String netIncome, String marketCap) {
        StockFinancialData f = magic(code, name, per, roe, null, revenue, operatingProfit, netIncome);
        f.setMarketCap(dec(marketCap));
        return f;
    }

    @Test
    @DisplayName("재현: PER 이 쓴 순이익(시총 ÷ PER)으로 판정한다 — 디에이피(영업손실인데 PER 0.4)·세이브존I&C(KIS 99억 vs PER 순이익 359억)는 빠진다")
    void judgesTheNetIncomeThePerUses() {
        when(stockStatusService.isActive(anyString())).thenReturn(true);
        when(stockFinancialDataRepository.findForMagicFormula(any())).thenReturn(List.of(
                // 영업손실 −578억·KIS 연결 순이익 −648억('적자 = 판정 대상 아님'으로 통과) — PER 0.4 는 DART 지배주주 순이익 +1,022억
                magicWithCap("066900", "디에이피", "0.40", "121.97", "5432", "-578", "-648", "409"),
                // KIS 연결 순이익 99억이면 영업이익 146억 안 — PER 2.7 은 지배주주 순이익 약 359억(영업이익의 2.5배)
                magicWithCap("067830", "세이브존I&C", "2.70", "6.82", "1347", "146", "99", "969"),
                magicWithCap("019180", "티에이치엔", "1.20", "27.59", "11302", "789", "750", "896")));

        List<ScreenerResultDto> results = service.getMagicFormulaStocks(10, null);

        assertThat(results).extracting(ScreenerResultDto::getStockCode).containsExactly("019180");
    }

    @Test
    @DisplayName("재현: 순이익이 전년보다 2배 넘게 는 종목은 마법의 공식에서 빠진다 — 액토즈소프트 +540%(10/7 AI 스윙 2위), 증가율 모르는 쇼박스는 남는다")
    void magicFormulaExcludesEarningsSpike() {
        when(stockStatusService.isActive(anyString())).thenReturn(true);
        StockFinancialData actoz = magicWithCap("052790", "액토즈소프트", "1.00", "17.90", "742", "379", "531", "524");
        actoz.setProfitGrowth(new BigDecimal("539.76"));
        StockFinancialData showbox = magicWithCap("086980", "쇼박스", "3.00", "25.96", "1158", "411", "351", "1068");
        when(stockFinancialDataRepository.findForMagicFormula(any())).thenReturn(List.of(actoz, showbox));

        assertThat(service.getMagicFormulaStocks(10, null)).extracting(ScreenerResultDto::getStockCode).containsExactly("086980");
    }

    @Test
    @DisplayName("재현: PEG 도 같은 규칙 — 10/7 AI 가치 1·2위(동원모빌리티 +151%·화승코퍼레이션 +115%, PEG 0.01)는 빠지고 +100% 이하는 남는다")
    void lowPegExcludesEarningsSpike() {
        when(stockStatusService.isActive(anyString())).thenReturn(true);
        when(stockFinancialDataRepository.findLowPegStocks(any(), any())).thenReturn(List.of(
                peg("018500", "동원모빌리티", "1.30", "24.33", "0.01", "150.79", "635", "6852", "363", "474"),
                peg("013520", "화승코퍼레이션", "1.70", "24.63", "0.01", "115.06", "1289", "16808", "746", "1071"),
                peg("019180", "티에이치엔", "1.20", "27.59", "0.01", "100.00", "896", "11302", "789", "750")));

        List<ScreenerResultDto> results = service.getLowPegStocks(new BigDecimal("1.0"), new BigDecimal("10"), 10);

        assertThat(results).extracting(ScreenerResultDto::getStockCode).containsExactly("019180");
    }

    @Test
    @DisplayName("PEG — 순이익이 영업이익의 2.8배(국보디자인)·52배(유성티엔에스)면 PEG 0.01 이어도 빠진다")
    void lowPegExcludesDistortedEarnings() {
        when(stockStatusService.isActive(anyString())).thenReturn(true);
        when(stockFinancialDataRepository.findLowPegStocks(any(), any())).thenReturn(List.of(
                peg("066620", "국보디자인", "1.70", "22.59", "0.01", "197.31", "1849", "4255", "400", "1106"),
                peg("024800", "유성티엔에스", "1.60", "15.95", "0.02", "88.21", "1564", "550", "19", "990"),
                peg("019180", "티에이치엔", "1.20", "27.59", "0.01", "100.00", "896", "11302", "789", "750")));

        List<ScreenerResultDto> results = service.getLowPegStocks(new BigDecimal("1.0"), new BigDecimal("10"), 10);

        assertThat(results).extracting(ScreenerResultDto::getStockCode)
                .containsExactly("019180");
    }

    @Test
    @DisplayName("PEG 2차 경로(성장률로 PEG 계산)도 거래정지 게이트를 탄다 — 1차가 비면 게이트 없이 새던 구멍")
    void lowPegSecondPathGoesThroughTheHaltGate() {
        when(stockFinancialDataRepository.findLowPegStocks(any(), any())).thenReturn(List.of());
        when(stockFinancialDataRepository.findStocksWithGrowthData()).thenReturn(List.of(
                peg("294090", "이오플로우", "5.00", "12.00", null, "20.00", "600", "1000", "100", "80"),
                peg("019180", "티에이치엔", "5.00", "12.00", null, "20.00", "896", "1000", "100", "80")));
        when(stockStatusService.isActive(anyString()))
                .thenAnswer(inv -> !"294090".equals(inv.getArgument(0, String.class)));

        List<ScreenerResultDto> results = service.getLowPegStocks(new BigDecimal("1.0"), new BigDecimal("10"), 10);

        assertThat(results).extracting(ScreenerResultDto::getStockCode)
                .containsExactly("019180");
    }
}
