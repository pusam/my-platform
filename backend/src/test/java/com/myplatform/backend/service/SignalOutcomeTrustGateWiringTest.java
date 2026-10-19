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
                provider(), provider(), provider(), provider(), provider(), provider());

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

    @Test
    @DisplayName("trustGateBetween — 표본 경계 이전(이전 산식) 행만 구간 쿼리로 접는다, 시작은 컷오프로 클램프, 끝은 제외")
    void legacyReferenceUsesBetweenQuery() {
        SignalOutcomeRepository repo = mock(SignalOutcomeRepository.class);
        when(repo.findD3OkBetween(any(), any(), anyString())).thenReturn(List.of(
                ok("BUY", "005930"), ok(ControlGroupService.CONTROL_SIGNAL_TYPE, "C1")));
        SignalOutcomeService svc = new SignalOutcomeService(repo, mock(StockPriceService.class),
                mock(StockCatalystRepository.class),
                provider(), provider(), provider(), provider(), provider(), provider(),
                provider(), provider(), provider(), provider(), provider(), provider());

        TrustGateRules.Verdict v = svc.trustGateBetween(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 10, 2));

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(repo).findD3OkBetween(from.capture(), to.capture(), anyString());
        assertThat(from.getValue()).isEqualTo(SignalOutcomeService.PHASE38_CUTOFF);
        assertThat(to.getValue()).isEqualTo(LocalDate.of(2026, 10, 2));
        verify(repo, never()).findD3OkSince(any(), anyString());
        assertThat(v.rows()).isEqualTo(1);
        assertThat(v.costAdjustedReturn()).isEqualByComparingTo("-2.18");
    }

    private static SignalOutcome okOn(String type, String code, LocalDate date) {
        SignalOutcome s = ok(type, code);
        s.setSignalDate(date);
        s.setCreatedAt(date.atTime(11, 30));
        return s;
    }

    @Test
    @DisplayName("trustGateBetween — 이전 산식 참고치도 9/1 이전 대조군은 섞지 않는다(대조군 표본 경계, 과대 방향)")
    void legacyReferenceExcludesControlsBeforeControlBoundary() {
        LocalDate aug = LocalDate.of(2026, 8, 20), sep = LocalDate.of(2026, 9, 10);
        SignalOutcomeRepository repo = mock(SignalOutcomeRepository.class);
        when(repo.findD3OkBetween(any(), any(), anyString())).thenReturn(List.of(
                okOn("BUY", "A", aug), okOn(ControlGroupService.CONTROL_SIGNAL_TYPE, "C1", aug),
                okOn("BUY", "B", sep), okOn(ControlGroupService.CONTROL_SIGNAL_TYPE, "C2", sep)));
        SignalOutcomeService svc = new SignalOutcomeService(repo, mock(StockPriceService.class),
                mock(StockCatalystRepository.class),
                provider(), provider(), provider(), provider(), provider(), provider(),
                provider(), provider(), provider(), provider(), provider(), provider());

        TrustGateRules.Verdict v = svc.trustGateBetween(LocalDate.of(2026, 6, 25), LocalDate.of(2026, 10, 2));

        assertThat(v.controlRows()).as("8/20 대조군은 9/1 경계 이전 — 짝으로 쓰지 않는다").isEqualTo(1);
        assertThat(v.rows()).isEqualTo(1);
        assertThat(v.excludedDays()).as("짝이 없어진 8/20 은 숨기지 않고 제외일로 센다").isEqualTo(1);
    }

    @Test
    @DisplayName("trustGateBetween — 경계가 컷오프 이전이면 구간이 비어 쿼리 없이 빈 판정")
    void emptyRangeDoesNotQuery() {
        SignalOutcomeRepository repo = mock(SignalOutcomeRepository.class);
        SignalOutcomeService svc = new SignalOutcomeService(repo, mock(StockPriceService.class),
                mock(StockCatalystRepository.class),
                provider(), provider(), provider(), provider(), provider(), provider(),
                provider(), provider(), provider(), provider(), provider(), provider());

        TrustGateRules.Verdict v = svc.trustGateBetween(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 6, 1));

        verify(repo, never()).findD3OkBetween(any(), any(), anyString());
        assertThat(v.rows()).isZero();
        assertThat(v.state()).isEqualTo(TrustGateRules.State.COLLECTING);
    }

    private static SignalOutcome notOk(String type, String code, String status) {
        SignalOutcome s = ok(type, code);
        s.setD3Status(status);
        s.setD3PctChange(null);
        s.setD3MaePct(null);
        return s;
    }

    @Test
    @DisplayName("재현(2026-10-19): 평가에서 빠진 시그널(거래정지·봉 없음)을 게이트 설명에 센다 — 최악일 수 있는 결과가 조용히 사라지지 않게")
    void notEvaluatedSignalsAreCounted() {
        SignalOutcomeRepository repo = mock(SignalOutcomeRepository.class);
        when(repo.findD3OkSince(any(), anyString())).thenReturn(List.of(
                ok("BUY", "005930"), ok(ControlGroupService.CONTROL_SIGNAL_TYPE, "C1")));
        when(repo.findD3NotOkSince(any(), anyString())).thenReturn(List.of(
                notOk("BUY", "294090", "HALTED_IN_WINDOW"),
                notOk("STRONG_BUY", "000660", "MISSING_BARS"),
                notOk(ControlGroupService.CONTROL_SIGNAL_TYPE, "C2", "MISSING_BARS")));   // 대조군은 시그널 표본이 아니다
        SignalOutcomeService svc = new SignalOutcomeService(repo, mock(StockPriceService.class),
                mock(StockCatalystRepository.class),
                provider(), provider(), provider(), provider(), provider(), provider(),
                provider(), provider(), provider(), provider(), provider(), provider());

        TrustGateRules.Verdict v = svc.trustGate(LocalDate.of(2026, 9, 1));

        assertThat(v.detail()).contains("평가에서 빠진 시그널 2건");
        assertThat(v.detail()).contains("창 안 거래정지 1").contains("봉 없음 1");
    }

    @Test
    @DisplayName("평가 제외 집계 — 보드 시그널만, 종목·날짜당 1건, 그 종목·날짜에 OK 행이 있으면 빠진 것이 아니다")
    void notEvaluatedBreakdownHygiene() {
        var okRows = List.of(ok("BUY", "005930"));
        var notOkRows = List.of(
                notOk("STRONG_BUY", "005930", "MISSING_BARS"),             // 같은 종목·날짜에 OK 행이 있다 — 승격 2행
                notOk("BUY", "000660", "MISSING_BARS"),
                notOk("STRONG_BUY", "000660", "MISSING_BARS"),             // 승격 2행 → 1건
                notOk("BUY", "294090", "HALTED_IN_WINDOW"),
                notOk(ControlGroupService.CONTROL_SIGNAL_TYPE, "C9", "NO_INDEX"));  // 대조군 — 세지 않는다

        assertThat(SignalOutcomeService.notEvaluatedBreakdown(notOkRows, okRows))
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("MISSING_BARS", 1, "HALTED_IN_WINDOW", 1));
        assertThat(SignalOutcomeService.notEvaluatedBreakdown(List.of(), okRows)).isEmpty();
    }
}
