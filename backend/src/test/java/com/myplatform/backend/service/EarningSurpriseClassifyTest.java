package com.myplatform.backend.service;

import com.myplatform.backend.dto.EarningSurpriseDto;
import com.myplatform.backend.dto.EarningSurpriseDto.SurpriseType;
import com.myplatform.backend.entity.StockFinancialData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 어닝 서프라이즈 분류 ({@link EarningSurpriseService#analyzeQuarters}) — 2026-07-28 버그 수정 회귀.
 *
 * <p>버그 1(적자 축소=POSITIVE): prev=-1000억 → latest=-100억 은 (latest-prev)/|prev| = +90% 라
 * <b>여전히 적자인데 POSITIVE(실적개선+90%)</b>로 분류 → composite earnings 16~20점(최강 입력) 오염.
 * 수정: POSITIVE 는 latest &gt; 0 (흑자) 필수. 적자 지속·축소는 서프라이즈 아님(null).
 *
 * <p>버그 2(연간/결측 분기 혼합 비교): reportDate 간격이 분기(≤120일)를 넘는 비교(연간 365일,
 * 결측 분기 건너뜀)는 "전분기 대비" 가 아니며 변화율이 뻥튀기 — 비교 자체를 스킵.
 */
class EarningSurpriseClassifyTest {

    private final EarningSurpriseService service = new EarningSurpriseService(
            mock(com.myplatform.backend.repository.StockFinancialDataRepository.class),
            mock(TelegramNotificationService.class));

    private static StockFinancialData quarter(LocalDate date, Integer op, Integer net) {
        return StockFinancialData.builder()
                .stockCode("005930").stockName("테스트").market("KOSPI")
                .reportDate(date)
                .operatingProfit(op != null ? BigDecimal.valueOf(op) : null)
                .netIncome(net != null ? BigDecimal.valueOf(net) : null)
                .build();
    }

    private static final LocalDate Q1 = LocalDate.of(2026, 3, 31);
    private static final LocalDate Q4 = LocalDate.of(2025, 12, 31);

    @Test
    @DisplayName("적자 축소(-1000억 → -100억)는 POSITIVE 가 아니다 — 여전히 적자")
    void lossNarrowingIsNotPositive() {
        EarningSurpriseDto dto = service.analyzeQuarters(
                quarter(Q1, -100, -100), quarter(Q4, -1000, -1000));
        // 기존 버그: +90% → POSITIVE(earnings 16). 수정 후: 서프라이즈 아님.
        assertThat(dto).isNull();
    }

    @Test
    @DisplayName("적자 → 0/양수 경계: latest=0 도 POSITIVE 아님")
    void zeroProfitIsNotPositive() {
        EarningSurpriseDto dto = service.analyzeQuarters(
                quarter(Q1, 0, 0), quarter(Q4, -500, -500));
        assertThat(dto).isNull();
    }

    @Test
    @DisplayName("진짜 흑자전환(-100억 → +50억)은 TURNAROUND 유지")
    void turnaroundStillDetected() {
        EarningSurpriseDto dto = service.analyzeQuarters(
                quarter(Q1, 50, 50), quarter(Q4, -100, -100));
        assertThat(dto).isNotNull();
        assertThat(dto.getSurpriseType()).isEqualTo(SurpriseType.TURNAROUND);
    }

    @Test
    @DisplayName("흑자 증가(+100억 → +150억, +50%)는 POSITIVE 유지")
    void genuinePositive() {
        EarningSurpriseDto dto = service.analyzeQuarters(
                quarter(Q1, 150, 150), quarter(Q4, 100, 100));
        assertThat(dto).isNotNull();
        assertThat(dto.getSurpriseType()).isEqualTo(SurpriseType.POSITIVE);
    }

    @Test
    @DisplayName("흑자 → 적자 전락은 NEGATIVE 유지")
    void positiveToLossIsNegative() {
        EarningSurpriseDto dto = service.analyzeQuarters(
                quarter(Q1, -50, -50), quarter(Q4, 100, 100));
        assertThat(dto).isNotNull();
        assertThat(dto.getSurpriseType()).isEqualTo(SurpriseType.NEGATIVE);
    }

    @Test
    @DisplayName("순이익 폴백 경로도 적자 축소는 POSITIVE 아님")
    void netIncomeFallbackLossNarrowing() {
        EarningSurpriseDto dto = service.analyzeQuarters(
                quarter(Q1, null, -100), quarter(Q4, null, -1000));
        assertThat(dto).isNull();
    }

    @Test
    @DisplayName("연간 행(365일 간격) 비교는 스킵 — '전분기 대비' 아님")
    void annualGapIsSkipped() {
        EarningSurpriseDto dto = service.analyzeQuarters(
                quarter(LocalDate.of(2025, 12, 31), 400, 400),
                quarter(LocalDate.of(2024, 12, 31), 100, 100));
        // 기존: +300% POSITIVE (연간 vs 연간 or 연간 vs 분기 혼합 뻥튀기). 수정 후: 스킵.
        assertThat(dto).isNull();
    }

    @Test
    @DisplayName("인접 분기(92일)는 정상 비교")
    void adjacentQuarterOk() {
        EarningSurpriseDto dto = service.analyzeQuarters(
                quarter(Q1, 130, 130), quarter(Q4, 100, 100));
        assertThat(dto).isNotNull();
        assertThat(dto.getSurpriseType()).isEqualTo(SurpriseType.POSITIVE);
    }
}
