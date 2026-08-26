package com.myplatform.backend.service;

import com.myplatform.backend.dto.EarningSurpriseDto;
import com.myplatform.backend.dto.EarningSurpriseDto.SurpriseType;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.entity.StockQuarterlyFinancial;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockQuarterlyFinancialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 분기 원본 경로 ({@link EarningSurpriseService#detectFromQuarterly}) — AUDIT 2026-08-21 R1.
 *
 * <p><b>이 테스트가 지키는 것</b>: R1 은 "일별 스냅샷 2행(오늘/어제)을 전분기 대비로 읽어
 * 변화율 ≈ 0 → earnings 死"였다. 새 경로가 같은 함정에 빠지지 않는지, 그리고 고치려다
 * <b>더 나쁜 결함</b>(누적값을 개별 분기로 오인한 유령 서프라이즈)을 만들지 않는지 고정한다.
 */
class EarningSurpriseQuarterlySourceTest {

    private StockQuarterlyFinancialRepository quarterlyRepo;
    private EarningSurpriseService service;

    private static final String CODE = "005930";

    @BeforeEach
    void setUp() {
        quarterlyRepo = mock(StockQuarterlyFinancialRepository.class);
        StockMasterService master = mock(StockMasterService.class);
        when(master.getNameOrDefault(anyString(), anyString())).thenReturn("삼성전자");
        when(master.getMarket(anyString())).thenReturn("KOSPI");

        service = new EarningSurpriseService(
                mock(StockFinancialDataRepository.class), quarterlyRepo, master,
                mock(TelegramNotificationService.class));
    }

    /** periodEnd 를 오늘 기준 상대 분기로 만들어 테스트가 날짜와 함께 썩지 않게 한다. */
    private static StockQuarterlyFinancial q(int quartersAgo, String rev, String op, String net,
                                             boolean cumulative) {
        YearMonth ym = quarterEndBefore(LocalDate.now()).minusMonths(3L * quartersAgo);
        return StockQuarterlyFinancial.builder()
                .stockCode(CODE)
                .fiscalPeriod(String.format("%04d%02d", ym.getYear(), ym.getMonthValue()))
                .periodEnd(ym.atEndOfMonth())
                .cumulative(cumulative)
                .revenue(rev == null ? null : new BigDecimal(rev))
                .operatingProfit(op == null ? null : new BigDecimal(op))
                .netIncome(net == null ? null : new BigDecimal(net))
                .source("KIS_INCOME_STMT")
                .collectedAt(LocalDateTime.now())
                .build();
    }

    /** 오늘 이전의 가장 가까운 분기말(3/6/9/12월). */
    private static YearMonth quarterEndBefore(LocalDate today) {
        YearMonth ym = YearMonth.from(today).minusMonths(1);
        while (ym.getMonthValue() % 3 != 0) ym = ym.minusMonths(1);
        return ym;
    }

    @Test
    @DisplayName("인접 2분기 영업이익 +20% 이상이면 POSITIVE — 레거시 경로에선 나올 수 없던 판정")
    void detectsPositiveFromAdjacentQuarters() {
        when(quarterlyRepo.findAllSince(any())).thenReturn(List.of(
                q(1, "10000", "1000", "800", false),
                q(0, "12000", "1500", "1200", false)));

        List<EarningSurpriseDto> out = service.detectFromQuarterly();

        assertThat(out).hasSize(1);
        EarningSurpriseDto dto = out.get(0);
        assertThat(dto.getSurpriseType()).isEqualTo(SurpriseType.POSITIVE);
        assertThat(dto.getOperatingProfitChangeRate()).isEqualByComparingTo("50.0");
        assertThat(dto.getStockName()).isEqualTo("삼성전자");
        // 신선도 가드가 의미를 갖는다 — 레거시는 reportDate 가 늘 '오늘'이라 항상 통과했다
        assertThat(dto.getLatestReportDate()).isBefore(LocalDate.now());
    }

    @Test
    @DisplayName("R1 재발 방지 — 하루 차이 두 행은 '전분기 대비'가 아니라 비교 자체를 안 한다")
    void refusesOneDayApartRows() {
        LocalDate today = LocalDate.now();
        StockQuarterlyFinancial yesterday = q(0, "10000", "1000", "800", false);
        yesterday.setPeriodEnd(today.minusDays(1));
        yesterday.setFiscalPeriod("209901");
        StockQuarterlyFinancial now = q(0, "10000", "1300", "800", false);
        now.setPeriodEnd(today);
        now.setFiscalPeriod("209902");

        when(quarterlyRepo.findAllSince(any())).thenReturn(List.of(yesterday, now));

        assertThat(service.detectFromQuarterly()).isEmpty();
    }

    @Test
    @DisplayName("분기가 건너뛰어져 있으면(중간 결측) 비교하지 않는다 — 6개월치를 한 분기로 부풀리지 않게")
    void refusesSkippedQuarter() {
        when(quarterlyRepo.findAllSince(any())).thenReturn(List.of(
                q(2, "10000", "1000", "800", false),
                q(0, "24000", "3000", "2400", false)));   // 1분기 전이 결측

        assertThat(service.detectFromQuarterly()).isEmpty();
    }

    @Test
    @DisplayName("최신 분기가 200일보다 오래면 제외 — 수집 끊긴 종목의 옛 실적이 매일 붙지 않게")
    void refusesStaleQuarters() {
        // 5·6분기 전 = 15개월 이상 전 → 인접 쌍은 만들어지지만 노후로 탈락
        when(quarterlyRepo.findAllSince(any())).thenReturn(List.of(
                q(6, "10000", "1000", "800", false),
                q(5, "12000", "1500", "1200", false)));

        assertThat(service.detectFromQuarterly()).isEmpty();
    }

    @Test
    @DisplayName("누적(YTD) 원본은 개별 분기로 환산한 뒤 판정 — 누적 그대로 비교하면 유령 급증")
    void decumulatesBeforeComparing() {
        // 누적: 1분기 10000 / 반기 21000 / 3분기 33000 → 개별 Q2 11000, Q3 12000 (+9.1%)
        // 영업이익 누적 1000 / 2100 / 3600 → 개별 Q2 1100, Q3 1500 (+36.4% = POSITIVE)
        when(quarterlyRepo.findAllSince(any())).thenReturn(List.of(
                q(2, "10000", "1000", "800", true),
                q(1, "21000", "2100", "1700", true),
                q(0, "33000", "3600", "2900", true)));

        List<EarningSurpriseDto> out = service.detectFromQuarterly();

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getSurpriseType()).isEqualTo(SurpriseType.POSITIVE);
        // 누적 그대로 비교했다면 3600/2100 = +71.4% 였을 것 — 개별 환산은 +36.4%
        assertThat(out.get(0).getOperatingProfitChangeRate()).isEqualByComparingTo("36.4");
        assertThat(out.get(0).getLatestOperatingProfit()).isEqualByComparingTo("1500");
    }

    @Test
    @DisplayName("분기 데이터가 아예 없으면 빈 목록 — '서프라이즈 0건'과 구분되게 경고 로그가 남는다")
    void emptySourceReturnsEmpty() {
        when(quarterlyRepo.findAllSince(any())).thenReturn(List.of());
        assertThat(service.detectFromQuarterly()).isEmpty();
    }

    @Test
    @DisplayName("기본 설정은 레거시 경로 — 켜는 시점은 사람이 정한다(측정 표본 경계)")
    void defaultsToLegacySource() {
        when(quarterlyRepo.findAllSince(any())).thenReturn(List.of(
                q(1, "10000", "1000", "800", false),
                q(0, "12000", "1500", "1200", false)));
        // 플래그 미주입 = false → detectEarningSurprises 는 레거시 저장소를 본다
        StockFinancialDataRepository legacy = mock(StockFinancialDataRepository.class);
        when(legacy.findLatestTwoQuartersPerStock()).thenReturn(List.<StockFinancialData>of());
        StockMasterService master = mock(StockMasterService.class);
        EarningSurpriseService s = new EarningSurpriseService(
                legacy, quarterlyRepo, master, mock(TelegramNotificationService.class));

        assertThat(s.detectEarningSurprises()).isEmpty();
        org.mockito.Mockito.verify(legacy).findLatestTwoQuartersPerStock();
        org.mockito.Mockito.verify(quarterlyRepo, org.mockito.Mockito.never()).findAllSince(any());
    }
}
