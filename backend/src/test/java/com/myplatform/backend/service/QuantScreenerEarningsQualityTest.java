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
