package com.myplatform.backend.service;

import com.myplatform.backend.entity.OvernightUsSnapshot;
import com.myplatform.backend.repository.OvernightUsSnapshotRepository;
import com.myplatform.backend.service.GlobalFuturesService.FuturesQuote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 간밤 미국장 tilt(작업3) 순수 함수 + V40 일일 스냅샷 테스트.
 * 분류 임계값은 임시값(스냅샷 축적 후 캘리브레이션, P3-5) — 분류 테스트는 현재 규칙의 회귀 가드.
 */
@ExtendWith(MockitoExtension.class)
class OvernightUsMarketServiceTest {

    // ==================== classifyOvernight (순수) ====================

    @Test
    @DisplayName("3지수 강세 + VIX 안정 → BULL")
    void bull() {
        assertThat(OvernightUsMarketService.classifyOvernight(0.8, 1.2, 2.0, 14.0)).isEqualTo("BULL");
    }

    @Test
    @DisplayName("VIX >= 30 → 공포 강제 BEAR (지수 강세여도)")
    void vixPanicForcesBear() {
        assertThat(OvernightUsMarketService.classifyOvernight(1.0, 1.5, 2.0, 31.0)).isEqualTo("BEAR");
    }

    @Test
    @DisplayName("3지수 평균 <= -0.6% → BEAR")
    void avgDownBear() {
        assertThat(OvernightUsMarketService.classifyOvernight(-0.7, -0.8, -0.5, 18.0)).isEqualTo("BEAR");
    }

    @Test
    @DisplayName("SOX <= -2% (평균은 -0.6 미만) → 반도체 급락 BEAR (단독 트리거)")
    void soxCrashBear() {
        // 평균 = (0.5+0.5-2.5)/3 = -0.5 (BEAR 평균조건 미충족) 이지만 SOX -2.5 단독으로 BEAR
        assertThat(OvernightUsMarketService.classifyOvernight(0.5, 0.5, -2.5, 16.0)).isEqualTo("BEAR");
    }

    @Test
    @DisplayName("VIX 25~30 → 공포경계 BEAR")
    void vixElevatedBear() {
        assertThat(OvernightUsMarketService.classifyOvernight(0.2, 0.1, 0.0, 26.0)).isEqualTo("BEAR");
    }

    @Test
    @DisplayName("강세지만 VIX 20~25 → BULL 아님(NEUTRAL)")
    void strongButElevatedVixNeutral() {
        assertThat(OvernightUsMarketService.classifyOvernight(1.0, 1.0, 1.0, 22.0)).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("경계값: 평균 +0.6 + VIX 19 → BULL / +0.59 → NEUTRAL")
    void boundary() {
        assertThat(OvernightUsMarketService.classifyOvernight(0.6, 0.6, 0.6, 19.0)).isEqualTo("BULL");
        assertThat(OvernightUsMarketService.classifyOvernight(0.59, 0.59, 0.59, 19.0)).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("VIX null(미수집)이어도 등락률만으로 분류 — marketState/VIX 비의존")
    void vixNullStillClassifies() {
        assertThat(OvernightUsMarketService.classifyOvernight(0.9, 1.1, 1.5, null)).isEqualTo("BULL");
        assertThat(OvernightUsMarketService.classifyOvernight(-1.0, -1.0, -1.0, null)).isEqualTo("BEAR");
    }

    @Test
    @DisplayName("전부 null → NEUTRAL (데이터 없음 안전 기본값)")
    void allNullNeutral() {
        assertThat(OvernightUsMarketService.classifyOvernight(null, null, null, null)).isEqualTo("NEUTRAL");
    }

    // ==================== toInputs / buildDrivers (순수, V40 스냅샷 재현용) ====================

    private static FuturesQuote quote(Double rate, Double price, String tradingTime, boolean success) {
        return FuturesQuote.builder()
                .changeRate(rate == null ? null : BigDecimal.valueOf(rate))
                .currentPrice(price == null ? null : BigDecimal.valueOf(price))
                .tradingTime(tradingTime)
                .success(success)
                .build();
    }

    @Test
    @DisplayName("toInputs: 성공 시세 → 등락률/레벨 추출, asOf 는 NQ 우선 tradingTime")
    void toInputsNormal() {
        OvernightUsMarketService.OvernightInputs in = OvernightUsMarketService.toInputs(
                quote(0.5, 6000.0, "07/06 05:00 KST", true),
                quote(0.8, 21000.0, "07/06 05:59 KST", true),
                quote(-2.5, 5200.0, null, true),
                quote(null, 18.5, null, true));
        assertThat(in.esRate()).isEqualTo(0.5);
        assertThat(in.nqRate()).isEqualTo(0.8);
        assertThat(in.soxRate()).isEqualTo(-2.5);
        assertThat(in.vixLevel()).isEqualTo(18.5);
        assertThat(in.soxLevel()).isEqualTo(5200.0);
        assertThat(in.tradingTime()).isEqualTo("07/06 05:59 KST");   // NQ 우선
    }

    @Test
    @DisplayName("toInputs: 실패/null 시세는 축 null(§4c) — 가짜값 생성 없음")
    void toInputsMissing() {
        OvernightUsMarketService.OvernightInputs in = OvernightUsMarketService.toInputs(
                null,                                       // ES 미조회
                quote(0.8, 21000.0, null, false),           // NQ 실패 응답
                quote(null, null, null, true),              // SOX 필드 결측
                null);
        assertThat(in.esRate()).isNull();
        assertThat(in.nqRate()).isNull();
        assertThat(in.soxRate()).isNull();
        assertThat(in.vixLevel()).isNull();
        assertThat(in.soxLevel()).isNull();
        assertThat(in.tradingTime()).isNull();
    }

    @Test
    @DisplayName("buildDrivers: 가용 축만 표시 + VIX 구간 라벨")
    void drivers() {
        OvernightUsMarketService.OvernightInputs in =
                new OvernightUsMarketService.OvernightInputs(0.75, null, -2.5, 26.0, 5200.0, null);
        List<String> d = OvernightUsMarketService.buildDrivers(in);
        assertThat(d).containsExactly("S&P500 +0.75%", "SOX -2.50%", "VIX 26.0(공포)");
    }

    // ==================== snapshotToday (V40 UPSERT — 표시와 동일 compute 경로) ====================

    @Mock private GlobalFuturesService futures;
    @Mock private OvernightUsSnapshotRepository snapshotRepo;
    @Mock private ObjectProvider<MarketRegimeClient> regimeProvider;
    @Mock private MarketRegimeClient regimeClient;

    @Test
    @DisplayName("snapshotToday: 판정 입력 4종 + tilt + regime v1 동시 스냅 저장 (신규 행)")
    void snapshotInsertsNewRow() {
        when(futures.getFuturesQuote("ES")).thenReturn(quote(0.8, 6000.0, "07/06 05:00 KST", true));
        when(futures.getFuturesQuote("NQ")).thenReturn(quote(1.2, 21000.0, "07/06 05:59 KST", true));
        when(futures.getFuturesQuote("SOX")).thenReturn(quote(2.0, 5200.0, null, true));
        when(futures.getFuturesQuote("VIX")).thenReturn(quote(null, 14.0, null, true));
        when(regimeProvider.getIfAvailable()).thenReturn(regimeClient);
        when(regimeClient.getCurrentRegimeQuiet()).thenReturn("SIDEWAYS");
        when(snapshotRepo.findBySnapshotDate(any())).thenReturn(Optional.empty());

        new OvernightUsMarketService(futures, snapshotRepo, regimeProvider).snapshotToday();

        ArgumentCaptor<OvernightUsSnapshot> captor = ArgumentCaptor.forClass(OvernightUsSnapshot.class);
        verify(snapshotRepo).save(captor.capture());
        OvernightUsSnapshot saved = captor.getValue();
        assertThat(saved.getSnapshotDate()).isEqualTo(LocalDate.now());
        assertThat(saved.getTilt()).isEqualTo("BULL");   // 표시와 같은 classifyOvernight 결과
        assertThat(saved.getEsRate()).isEqualByComparingTo("0.80");
        assertThat(saved.getNqRate()).isEqualByComparingTo("1.20");
        assertThat(saved.getSoxRate()).isEqualByComparingTo("2.00");
        assertThat(saved.getVixLevel()).isEqualByComparingTo("14.00");
        assertThat(saved.getRegimeV1()).isEqualTo("SIDEWAYS");
        assertThat(saved.getDrivers()).contains("S&P500 +0.80%").contains("VIX 14.0(안정)");
    }

    @Test
    @DisplayName("snapshotToday: 같은 날 재실행이면 기존 행 UPSERT(id 유지) + 미수집 축은 null 저장(§4c)")
    void snapshotUpsertsExistingRowAndKeepsNulls() {
        when(futures.getFuturesQuote("ES")).thenReturn(null);   // Yahoo 미가용
        when(futures.getFuturesQuote("NQ")).thenReturn(quote(-1.0, 20000.0, null, true));
        when(futures.getFuturesQuote("SOX")).thenReturn(quote(-1.0, 5100.0, null, true));
        when(futures.getFuturesQuote("VIX")).thenReturn(quote(null, 27.0, null, true));
        when(regimeProvider.getIfAvailable()).thenReturn(null);   // regime 미가용 → null(미수집)
        OvernightUsSnapshot existing = OvernightUsSnapshot.builder()
                .id(7L).snapshotDate(LocalDate.now()).tilt("NEUTRAL").build();
        when(snapshotRepo.findBySnapshotDate(any())).thenReturn(Optional.of(existing));

        new OvernightUsMarketService(futures, snapshotRepo, regimeProvider).snapshotToday();

        ArgumentCaptor<OvernightUsSnapshot> captor = ArgumentCaptor.forClass(OvernightUsSnapshot.class);
        verify(snapshotRepo).save(captor.capture());
        OvernightUsSnapshot saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(7L);                 // 기존 행 재사용(일 1행)
        assertThat(saved.getTilt()).isEqualTo("BEAR");           // VIX 27 → 공포경계
        assertThat(saved.getEsRate()).isNull();                  // 미수집 위장 금지
        assertThat(saved.getRegimeV1()).isNull();
    }
}
