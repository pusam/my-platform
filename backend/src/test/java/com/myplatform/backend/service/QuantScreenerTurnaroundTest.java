package com.myplatform.backend.service;

import com.myplatform.backend.dto.EarningSurpriseDto;
import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.entity.StockFinancialData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 턴어라운드 스크리너 — 판정은 실적 서프라이즈(분기 원본) 단일 출처(2026-10-03 화면 점검).
 *
 * <p>예전엔 종목별 최신 두 행을 '분기'로 비교했는데 8/27 이후 그 두 행은 이틀 연속 일별 행(TTM)이었다 —
 * 하루 차이를 "이전 2026.4Q → 현재 2026.4Q"로 표기하고, 결과가 없으면 TTM 전년 대비로 조용히 바꿨다.
 * 이 목록 상위 3개는 AI 턴어라운드 전략을 거쳐 종합추천 AI 시드·테마 가산이 된다.
 */
class QuantScreenerTurnaroundTest {

    private static EarningSurpriseDto surprise(String code, EarningSurpriseDto.SurpriseType type,
                                               String latestNet, String prevNet, String netRate) {
        return EarningSurpriseDto.builder()
                .stockCode(code).stockName("종목" + code).surpriseType(type)
                .latestNetIncome(latestNet == null ? null : new BigDecimal(latestNet))
                .previousNetIncome(prevNet == null ? null : new BigDecimal(prevNet))
                .netIncomeChangeRate(netRate == null ? null : new BigDecimal(netRate))
                .latestReportDate(LocalDate.of(2026, 6, 30)).previousReportDate(LocalDate.of(2026, 3, 31))
                .build();
    }

    private static StockFinancialData daily(String code, String marketCap) {
        StockFinancialData d = new StockFinancialData();
        d.setStockCode(code);
        d.setStockName("일별" + code);
        d.setMarketCap(new BigDecimal(marketCap));
        d.setReportDate(LocalDate.of(2026, 10, 2));
        return d;
    }

    @Test
    @DisplayName("재현: 이름표는 판정에 쓴 분기 — 일별 행의 날짜(2026.4Q→2026.4Q)가 아니다")
    void periodsAreRealQuarters() {
        List<ScreenerResultDto> out = QuantScreenerService.turnaroundFromSurprises(
                List.of(surprise("000001", EarningSurpriseDto.SurpriseType.TURNAROUND, "120", "-40", null)),
                Map.of("000001", daily("000001", "3000")));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getTurnaroundType()).isEqualTo("LOSS_TO_PROFIT");
        assertThat(out.get(0).getPreviousPeriod()).isEqualTo("2026.1Q");
        assertThat(out.get(0).getCurrentPeriod()).isEqualTo("2026.2Q");
        assertThat(out.get(0).getCurrentNetIncome()).isEqualByComparingTo("120");
    }

    @Test
    @DisplayName("서프라이즈 중 분기 순이익 +50% 이상만 PROFIT_GROWTH, 흑자전환이 먼저")
    void growthThresholdAndOrder() {
        List<ScreenerResultDto> out = QuantScreenerService.turnaroundFromSurprises(List.of(
                surprise("000002", EarningSurpriseDto.SurpriseType.POSITIVE, "80", "40", "100"),
                surprise("000003", EarningSurpriseDto.SurpriseType.POSITIVE, "60", "50", "20"),     // +20% — 탈락
                surprise("000004", EarningSurpriseDto.SurpriseType.TURNAROUND, "35", "-10", null),
                surprise("000005", EarningSurpriseDto.SurpriseType.NEGATIVE, "10", "50", "-80")), Map.of());

        assertThat(out).extracting(ScreenerResultDto::getStockCode).containsExactly("000004", "000002");
    }

    @Test
    @DisplayName("당 분기 순이익 30억 미만·모름은 잡주로 빼고, 시총 500억 미만도 뺀다(시총 모름은 포함 — 종전 규칙)")
    void smallOrUnknownExcluded() {
        List<ScreenerResultDto> out = QuantScreenerService.turnaroundFromSurprises(List.of(
                surprise("000006", EarningSurpriseDto.SurpriseType.TURNAROUND, "20", "-10", null),
                surprise("000007", EarningSurpriseDto.SurpriseType.TURNAROUND, null, "-10", null),
                surprise("000008", EarningSurpriseDto.SurpriseType.TURNAROUND, "50", "-10", null),
                surprise("000009", EarningSurpriseDto.SurpriseType.TURNAROUND, "50", "-10", null)),
                Map.of("000008", daily("000008", "300")));

        assertThat(out).extracting(ScreenerResultDto::getStockCode).containsExactly("000009");
    }
}
