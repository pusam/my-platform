package com.myplatform.backend.service;

import com.myplatform.backend.controlroom.TrustGateRules;
import com.myplatform.backend.entity.SignalOutcome;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.StockCatalystRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 게이트(⑦)의 <b>행 선택</b>이 교정 평가 쿼리로 바뀌었는지(2026-09-21 전환) — 집계 순수 함수와 별개로,
 * {@code trustGate(from)} 이 {@code findEvaluatedSince}(레거시 evaluated_at 기준)를 더는 부르지 않는다.
 */
class SignalOutcomeTrustGateWiringTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }

    private static SignalOutcome ok(String type, String code) {
        SignalOutcome s = SignalOutcome.builder()
                .signalType(type).stockCode(code).stockName(code).signalDate(LocalDate.of(2026, 9, 1))
                .priceAtSignal(new BigDecimal("10000")).signalScore(60)
                .d3Status("OK").d3PctChange(new BigDecimal("-2.00")).d3MaePct(new BigDecimal("-4.00"))
                .d3EvaluatedAt(LocalDate.of(2026, 9, 4).atTime(19, 45))
                .build();
        s.setCreatedAt(LocalDate.of(2026, 9, 1).atTime(11, 30));
        return s;
    }

    @Test
    @DisplayName("trustGate 는 findD3OkSince(교정 OK 행)를 읽고 findEvaluatedSince 는 부르지 않는다 — 시작일은 phase-38 컷오프로 클램프")
    void readsCorrectedRowsOnly() {
        SignalOutcomeRepository repo = mock(SignalOutcomeRepository.class);
        when(repo.findD3OkSince(any(), anyString())).thenReturn(List.of(
                ok("BUY", "005930"), ok(ControlGroupService.CONTROL_SIGNAL_TYPE, "C1")));
        SignalOutcomeService svc = new SignalOutcomeService(repo, mock(StockPriceService.class),
                mock(StockCatalystRepository.class),
                provider(), provider(), provider(), provider(), provider(), provider(),
                provider(), provider(), provider(), provider(), provider());

        TrustGateRules.Verdict v = svc.trustGate(LocalDate.of(2020, 1, 1));

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(repo).findD3OkSince(from.capture(), status.capture());
        assertThat(from.getValue()).isEqualTo(SignalOutcomeService.PHASE38_CUTOFF);
        assertThat(status.getValue()).isEqualTo(SignalD3Evaluator.Status.OK.name());
        verify(repo, never()).findEvaluatedSince(any());

        assertThat(v.rows()).isEqualTo(1);
        assertThat(v.controlRows()).isEqualTo(1);
        assertThat(v.costAdjustedReturn()).isEqualByComparingTo("-2.18");
    }
}
