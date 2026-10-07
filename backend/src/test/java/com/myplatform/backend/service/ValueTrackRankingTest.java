package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockMasterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 저평가 트랙 순위 — 이익의 질로 거르고, 동점이면 PER·PBR 낮은 순(2026-10-07, 사용자 결정 '순위 정비').
 *
 * <p>재현(10/7 운영 재계산, 부채비율 복구 후): 점수가 구간 합(만점 20)이라 만점이 133종목이었고, 점수만 비교해 동점이
 * 입력 순서(종목코드 해시 순서)로 남아 상위 10 이 사실상 무작위였다. 그 133 중 41 은 순이익이 영업이익의 2배를 넘는
 * 종목(SG&G 순이익 954억 vs 영업이익 52억 → PER 0.6)이라, 동점 순서를 PER 로 정하면 오히려 그 왜곡이 1등이 된다 —
 * 마법의 공식·PEG 와 같은 이익의 질 규칙({@link EarningsQuality})으로 먼저 뺀다. 수치는 전부 10/7 운영 KIS 일별 행 그대로.
 */
class ValueTrackRankingTest {

    private RecommendationService service;
    private StockFinancialDataRepository financials;

    @BeforeEach
    void setUp() {
        service = mock(RecommendationService.class, CALLS_REAL_METHODS);
        financials = mock(StockFinancialDataRepository.class);
        StockStatusService status = mock(StockStatusService.class);
        when(status.isActive(anyString())).thenReturn(true);
        RiskManagementService risk = mock(RiskManagementService.class);
        when(risk.quickDangerCheck(anyString(), anyString())).thenReturn(false);
        StockMasterRepository master = mock(StockMasterRepository.class);
        when(master.findAll()).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "financialDataRepository", financials);
        ReflectionTestUtils.setField(service, "stockStatusService", status);
        ReflectionTestUtils.setField(service, "riskManagementService", risk);
        ReflectionTestUtils.setField(service, "stockMasterRepository", master);
    }

    /** 10/7 운영 KIS 일별 행 — 부채비율은 부채총계÷자본총계(이날은 저장되지 않아 계산값). 금액 억원. */
    private static StockFinancialData row(String code, String name, String pbr, String roe, String debt,
                                          String op, String ni, String equity, String revenue, String per) {
        StockFinancialData f = new StockFinancialData();
        f.setStockCode(code);
        f.setStockName(name);
        f.setReportDate(LocalDate.now());
        f.setPbr(new BigDecimal(pbr));
        f.setRoe(new BigDecimal(roe));
        f.setDebtRatio(new BigDecimal(debt));
        f.setOperatingProfit(new BigDecimal(op));
        f.setNetIncome(new BigDecimal(ni));
        f.setTotalEquity(new BigDecimal(equity));
        f.setRevenue(new BigDecimal(revenue));
        f.setPer(new BigDecimal(per));
        return f;
    }

    private static List<StockFinancialData> rows() {
        return new ArrayList<>(List.of(
                // 이익의 질 왜곡 — 순이익이 영업이익의 2배 초과(점수는 만점 20)
                row("040610", "SG&G", "0.11", "18.75", "17.17", "52.00", "954.00", "5085.00", "490.00", "0.60"),
                row("001230", "동국홀딩스", "0.17", "5.95", "45.85", "382.00", "1174.00", "20820.00", "22613.00", "2.80"),
                // 정상 — 전부 만점 20
                row("052790", "액토즈소프트", "0.18", "17.90", "23.96", "379.00", "531.00", "2963.00", "742.00", "1.00"),
                row("225590", "패션플랫폼", "0.18", "13.34", "29.37", "132.00", "113.00", "841.00", "1138.00", "1.30"),
                row("115570", "스타플렉스", "0.28", "20.30", "27.18", "92.00", "157.00", "769.00", "995.00", "1.40"),
                row("003650", "미창석유공업", "0.37", "21.38", "18.52", "456.00", "632.00", "4125.00", "4162.00", "1.70"),
                row("079960", "동양이엔피", "0.27", "12.05", "15.01", "334.00", "565.00", "4730.00", "5204.00", "2.30"),
                row("094970", "제이엠티", "0.30", "12.84", "41.20", "183.00", "240.00", "2000.00", "2441.00", "2.30"),
                row("069730", "DSR제강", "0.39", "16.52", "41.99", "285.00", "335.00", "2029.00", "2334.00", "2.30"),
                row("265560", "영화테크", "0.63", "27.22", "35.92", "167.00", "291.00", "1069.00", "1068.00", "2.30"),
                row("067830", "세이브존I&C", "0.18", "6.82", "19.09", "146.00", "99.00", "4840.00", "1347.00", "2.70"),
                row("063760", "이엘피", "0.31", "11.47", "22.80", "63.00", "74.00", "649.00", "357.00", "2.70"),
                row("123700", "에스제이엠", "0.21", "7.22", "21.66", "107.00", "164.00", "2581.00", "2051.00", "2.80"),
                row("054800", "아이디스홀딩스", "0.37", "13.00", "38.97", "1162.00", "1069.00", "9112.00", "9992.00", "2.90")));
    }

    private List<String> top10(List<StockFinancialData> input) {
        when(financials.findRecentPerStock(anyInt())).thenReturn(input);
        List<RecommendationService.RecommendationDto> result = ReflectionTestUtils.invokeMethod(service, "calculateValueTop10");
        return result.stream().map(RecommendationService.RecommendationDto::getStockCode).toList();
    }

    @Test
    @DisplayName("재현: 만점 동점은 PER 낮은 순 → PBR 낮은 순 — 입력 순서와 무관, 이익의 질 왜곡(SG&G·동국홀딩스)은 빠진다")
    void tiesBreakByPerThenPbrAndDistortedExcluded() {
        List<String> expected = List.of(
                "052790",                               // PER 1.0
                "225590",                               // 1.3
                "115570",                               // 1.4
                "003650",                               // 1.7
                "079960", "094970", "069730", "265560", // 2.3 — PBR 0.27·0.30·0.39·0.63
                "067830", "063760");                    // 2.7 — PBR 0.18·0.31 (2.8·2.9 는 11·12위)

        List<StockFinancialData> shuffled = rows();
        Collections.reverse(shuffled);
        assertThat(top10(rows())).containsExactlyElementsOf(expected);
        assertThat(top10(shuffled)).containsExactlyElementsOf(expected);
    }
}
