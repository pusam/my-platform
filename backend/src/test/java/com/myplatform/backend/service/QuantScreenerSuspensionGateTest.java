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
}
