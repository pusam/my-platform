package com.myplatform.backend.service;

import com.myplatform.backend.dto.EarningSurpriseDto;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 퀀트 스크리너는 거래정지/상폐 게이트를 탄다 — 2026-09-07 전까지 안 탔다.
 *
 * <p>실사고: 이오플로우(294090, 거래정지)가 동결가 재무 스냅샷(PER 1.0·ROE 40.5·영업이익률 3,995%)으로
 * 마법의공식 #1 이 되어 08:30 아침 알림 텔레그램에 "추천"으로 발송됐다. 재무 필터로는 못 거른다 —
 * 거래정지 종목도 KIS 가 동결가를 계속 줘서 스냅샷이 매일 쌓이고, 그 값이 오히려 극단적으로 좋아 보인다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuantScreenerSuspensionGateTest {

    @Mock private StockFinancialDataRepository stockFinancialDataRepository;
    @Mock private TelegramNotificationService telegramNotificationService;
    @Mock private KoreaInvestmentService koreaInvestmentService;
    @Mock private StockPriceService stockPriceService;
    @Mock private StockStatusService stockStatusService;
    @Mock private EarningSurpriseService earningSurpriseService;

    @InjectMocks private QuantScreenerService service;

    private static StockFinancialData row(String code, String name, String per, String roe, String opMargin) {
        return StockFinancialData.builder()
                .stockCode(code)
                .stockName(name)
                .reportDate(LocalDate.of(2026, 9, 5))
                .per(new BigDecimal(per))
                .roe(new BigDecimal(roe))
                .operatingMargin(new BigDecimal(opMargin))
                .marketCap(new BigDecimal("600000"))
                .build();
    }

    @Test
    @DisplayName("거래정지 종목은 마법의공식 결과에서 빠진다 — 동결가 지표가 1위여도")
    void suspendedStockExcludedFromMagicFormula() {
        // 이오플로우 실측 그대로: 동결가 지표가 전 종목 1위 — 게이트 없으면 반드시 #1
        StockFinancialData eoflow = row("294090", "이오플로우", "1.00", "40.52", "3995.00");
        StockFinancialData normal1 = row("005930", "삼성전자", "10.00", "12.00", "15.00");
        StockFinancialData normal2 = row("000660", "SK하이닉스", "8.00", "20.00", "30.00");
        when(stockFinancialDataRepository.findForMagicFormula(any()))
                .thenReturn(List.of(eoflow, normal1, normal2));
        when(stockStatusService.isActive(anyString()))
                .thenAnswer(inv -> !"294090".equals(inv.getArgument(0, String.class)));

        List<ScreenerResultDto> results = service.getMagicFormulaStocks(5, null);

        assertThat(results).extracting(ScreenerResultDto::getStockCode)
                .doesNotContain("294090")
                .contains("005930", "000660");
    }

    @Test
    @DisplayName("게이트가 전부 통과시키면(정지 없음) 결과 구성은 종전과 동일")
    void allActivePassesThrough() {
        when(stockFinancialDataRepository.findForMagicFormula(any()))
                .thenReturn(List.of(row("005930", "삼성전자", "10.00", "12.00", "15.00"),
                        row("000660", "SK하이닉스", "8.00", "20.00", "30.00")));
        when(stockStatusService.isActive(anyString())).thenReturn(true);

        List<ScreenerResultDto> results = service.getMagicFormulaStocks(5, null);

        assertThat(results).hasSize(2);
    }

    private static EarningSurpriseDto turnaround(String code, String name, String latestNet) {
        return EarningSurpriseDto.builder()
                .stockCode(code).stockName(name)
                .surpriseType(EarningSurpriseDto.SurpriseType.TURNAROUND)
                .latestNetIncome(new BigDecimal(latestNet)).previousNetIncome(new BigDecimal("-40"))
                .latestReportDate(LocalDate.of(2026, 6, 30)).previousReportDate(LocalDate.of(2026, 3, 31))
                .build();
    }

    private static StockFinancialData dailyRow(String code, String name, String marketCap) {
        return StockFinancialData.builder()
                .stockCode(code).stockName(name)
                .reportDate(LocalDate.of(2026, 10, 6))
                .marketCap(new BigDecimal(marketCap))
                .build();
    }

    @Test
    @DisplayName("재현: 거래정지·상폐 종목은 턴어라운드 결과에서도 빠진다 — 일별 행만 걸러서 실적 판정 목록으로 그대로 들어왔다")
    void suspendedStockExcludedFromTurnaround() {
        // 2026-10-06 운영 실측: 상장폐지된 동양생명(082640 — 8/28 뒤로 봉이 없다)·현대홈쇼핑(057050)이 분기 실적 판정 목록을
        // 타고 결과에 남았다. 게이트는 일별 행에만 걸려 있어 걸러진 종목이 '일별 행 없음 = 시총 모름(포함)'으로 되살아났고,
        // 회차마다 시세 보충 조회(KIS 실패 → 네이버 409 → 네이버 서킷 60초 차단)를 냈다. 이 목록 상위가 AI 턴어라운드
        // 전략을 거쳐 종합추천 AI 시드·테마 가산이 된다.
        when(earningSurpriseService.detectEarningSurprises()).thenReturn(List.of(
                turnaround("082640", "동양생명", "500"),
                turnaround("030530", "원익홀딩스", "120")));
        when(stockFinancialDataRepository.findAllRecentData(any())).thenReturn(List.of(
                dailyRow("082640", "동양생명", "0"),
                dailyRow("030530", "원익홀딩스", "22553")));
        when(stockStatusService.isActive(anyString()))
                .thenAnswer(inv -> !"082640".equals(inv.getArgument(0, String.class)));

        List<ScreenerResultDto> results = service.getTurnaroundStocks(10);

        assertThat(results).extracting(ScreenerResultDto::getStockCode).containsExactly("030530");
        verify(stockPriceService, never()).getStockPrice("082640");
    }
}
