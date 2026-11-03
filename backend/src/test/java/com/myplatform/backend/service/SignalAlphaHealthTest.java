package com.myplatform.backend.service;

import com.myplatform.backend.entity.SignalOutcome;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.StockCatalystRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 강력매수 alpha 경보(리스크 채널)는 교정 D+3 값 · 현재 산식 표본으로 잰다(2026-10-19, 리뷰 에이전트 지적).
 *
 * <p>재현: 경보가 레거시 {@code alpha3d}(평가 배치가 돈 시점의 가격 — 정의가 흔들려 '방향은 맞다'조차 보장 못 하는 참고치)를
 * 표본 경계도 없이 평균내 "추천 산식이 시장 대비 underperform 중"이라고 보냈다. 게이트·화면(교정 D+3 기준)과 부호가 반대일 수 있었다.
 */
class SignalAlphaHealthTest {

    private SignalOutcomeRepository repo;
    private TelegramNotificationService telegram;
    private SignalSampleBoundary boundary;
    private SignalOutcomeService svc;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private static SignalOutcome strongBuy(String code, String legacyAlpha, String d3Alpha) {
        LocalDate d = LocalDate.now().minusDays(2);
        SignalOutcome s = SignalOutcome.builder()
                .signalType("STRONG_BUY").stockCode(code).stockName(code).signalDate(d)
                .priceAtSignal(new BigDecimal("10000")).signalScore(80)
                .alpha3d(new BigDecimal(legacyAlpha)).evaluatedAt(d.plusDays(3).atTime(19, 30))
                .d3Status("OK").d3PctChange(new BigDecimal("1.00")).d3Alpha(new BigDecimal(d3Alpha))
                .d3EvaluatedAt(d.plusDays(3).atTime(19, 45))
                .build();
        s.setCreatedAt(d.atTime(11, 30));
        return s;
    }

    @BeforeEach
    void setUp() {
        repo = mock(SignalOutcomeRepository.class);
        telegram = mock(TelegramNotificationService.class);
        boundary = mock(SignalSampleBoundary.class);
        when(boundary.current()).thenReturn(new SignalSampleBoundary.Boundary(
                LocalDate.now().minusDays(30), SignalSampleBoundary.Status.PROVISIONAL));
        svc = new SignalOutcomeService(repo, mock(StockPriceService.class), mock(StockCatalystRepository.class),
                provider(), provider(), provider(), provider(), provider(), provider(),
                providerOf(telegram), provider(), provider(), provider(), provider(), providerOf(boundary));
    }

    @Test
    @DisplayName("재현: 레거시 alpha 가 +여도 교정 D+3 alpha 평균이 −면 경보 — 게이트·화면과 같은 정의")
    void alertUsesCorrectedAlpha() {
        when(repo.averageAlphaSince(any(), any())).thenReturn(new BigDecimal("3.00"));   // 레거시 — 좋아 보인다
        when(repo.findD3OkSince(any(), anyString())).thenReturn(List.of(
                strongBuy("005930", "4.00", "-2.00"), strongBuy("000660", "2.00", "-1.00")));

        svc.checkStrongBuyAlphaHealth();

        verify(telegram).sendRisk(contains("-1.50"));
    }

    @Test
    @DisplayName("재현: 레거시 alpha 가 −여도 교정 값이 +면 경보하지 않는다")
    void noAlertWhenCorrectedPositive() {
        when(repo.averageAlphaSince(any(), any())).thenReturn(new BigDecimal("-3.00"));
        when(repo.findD3OkSince(any(), anyString())).thenReturn(List.of(strongBuy("005930", "-4.00", "1.20")));

        svc.checkStrongBuyAlphaHealth();

        verify(telegram, never()).sendRisk(anyString());
    }

    @Test
    @DisplayName("표본 시작일이 미정이면 경보하지 않는다 — 현재 산식 표본이 없다(화면은 '검증 중')")
    void noAlertWhenBoundaryUnset() {
        when(boundary.current()).thenReturn(SignalSampleBoundary.Boundary.UNSET);
        when(repo.averageAlphaSince(any(), any())).thenReturn(new BigDecimal("-3.00"));

        svc.checkStrongBuyAlphaHealth();

        verify(telegram, never()).sendRisk(anyString());
    }
}
