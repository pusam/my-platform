package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myplatform.backend.dto.WeeklySignalAccuracyDto;
import com.myplatform.backend.entity.SignalOutcome;
import com.myplatform.backend.entity.SignalWeeklyAccuracy;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.SignalWeeklyAccuracyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 주간 예측력 측정 서비스 — 주 경계(Clock)·board 격리 위임·UPSERT·prior 플래그 제외 검증.
 * 집계 정확성은 {@link WeeklyAccuracyAggregatorTest} 담당(순수 함수). 여기선 오케스트레이션만.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SignalWeeklyReportServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private SignalOutcomeRepository outcomeRepository;
    @Mock private SignalWeeklyAccuracyRepository weeklyRepository;
    @Mock private SchedulerLockService schedulerLockService;
    @Mock private ObjectProvider<TelegramNotificationService> telegramProvider;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private SignalWeeklyReportService service(LocalDate today) {
        Clock clock = Clock.fixed(today.atStartOfDay(KST).toInstant(), KST);
        return new SignalWeeklyReportService(outcomeRepository, weeklyRepository,
                schedulerLockService, telegramProvider, objectMapper, clock);
    }

    @BeforeEach
    void setup() {
        lenient().when(telegramProvider.getIfAvailable()).thenReturn(null); // 텔레그램 미설정
        lenient().when(outcomeRepository.findEvaluatedBetween(any(), any())).thenReturn(new ArrayList<>());
        lenient().when(outcomeRepository.findEvaluatedSince(any())).thenReturn(new ArrayList<>());
        lenient().when(weeklyRepository.findByWeekStart(any())).thenReturn(java.util.Optional.empty());
        lenient().when(weeklyRepository.findTop12ByOrderByWeekStartDesc()).thenReturn(new ArrayList<>());
    }

    @Test
    @DisplayName("일요일 실행 → weekEnd=당일 일요일, weekStart=6일 전 월요일")
    void weekBoundary_sunday() {
        // 2026-07-05 = 일요일
        LocalDate sunday = LocalDate.of(2026, 7, 5);
        assertThat(sunday.getDayOfWeek().getValue()).isEqualTo(7);

        WeeklySignalAccuracyDto dto = service(sunday).generateWeeklyReport("system");

        assertThat(dto.getWeekEnd()).isEqualTo(sunday);
        assertThat(dto.getWeekStart()).isEqualTo(LocalDate.of(2026, 6, 29)); // 월요일
        verify(outcomeRepository).findEvaluatedBetween(LocalDate.of(2026, 6, 29), sunday);
    }

    @Test
    @DisplayName("수요일 실행(수동) → weekEnd=직전 일요일, weekStart=그 월요일")
    void weekBoundary_midweek() {
        // 2026-07-08 = 수요일 → 직전 일요일 = 07-05
        LocalDate wed = LocalDate.of(2026, 7, 8);
        assertThat(wed.getDayOfWeek().getValue()).isEqualTo(3);

        WeeklySignalAccuracyDto dto = service(wed).generateWeeklyReport("manual");

        assertThat(dto.getWeekEnd()).isEqualTo(LocalDate.of(2026, 7, 5));
        assertThat(dto.getWeekStart()).isEqualTo(LocalDate.of(2026, 6, 29));
    }

    @Test
    @DisplayName("board 격리 위임 — STRONG_BUY/BUY 만 집계 입력(AI_*/SURGE 제외)")
    void boardIsolation_delegated() {
        LocalDate sunday = LocalDate.of(2026, 7, 5);
        List<SignalOutcome> weeklyRaw = List.of(
                sig("BUY", 12), sig("STRONG_BUY", 12), sig("AI_BUY", 12), sig("SURGE_HOT", 12));
        when(outcomeRepository.findEvaluatedBetween(any(), any())).thenReturn(weeklyRaw);

        WeeklySignalAccuracyDto dto = service(sunday).generateWeeklyReport("system");

        // 4건 중 board 2건만 weeklyN 에 반영(filterBoardSignals 위임 확인)
        assertThat(dto.getWeeklyN()).isEqualTo(2);
    }

    @Test
    @DisplayName("UPSERT — 스냅샷 저장 시 week/weeklyN/cumulativeN/generatedBy 세팅")
    void persist_upsert() {
        LocalDate sunday = LocalDate.of(2026, 7, 5);
        when(outcomeRepository.findEvaluatedBetween(any(), any()))
                .thenReturn(List.of(sig("BUY", 12), sig("STRONG_BUY", 12)));

        service(sunday).generateWeeklyReport("manual");

        ArgumentCaptor<SignalWeeklyAccuracy> cap = ArgumentCaptor.forClass(SignalWeeklyAccuracy.class);
        verify(weeklyRepository).save(cap.capture());
        SignalWeeklyAccuracy saved = cap.getValue();
        assertThat(saved.getWeekStart()).isEqualTo(LocalDate.of(2026, 6, 29));
        assertThat(saved.getWeekEnd()).isEqualTo(sunday);
        assertThat(saved.getWeeklyN()).isEqualTo(2);
        assertThat(saved.getGeneratedBy()).isEqualTo("manual");
        assertThat(saved.getReportJson()).isNotBlank();
    }

    @Test
    @DisplayName("prior 스트릭 플래그 — 재생성 시 같은 weekStart 스냅샷은 제외(중복 카운트 방지)")
    void priorFlags_excludeCurrentWeek() {
        LocalDate sunday = LocalDate.of(2026, 7, 5);
        LocalDate weekStart = LocalDate.of(2026, 6, 29);

        // 누적: 강세수급 10건·alpha 음수 → supplyInverted=true
        when(outcomeRepository.findEvaluatedSince(any())).thenReturn(strongSupply(10, false, "-2.0"));

        // 이전 스냅샷 2개: 하나는 이번 주 자신(제외돼야), 하나는 지난 주(true)
        SignalWeeklyAccuracy self = snap(weekStart, true);
        SignalWeeklyAccuracy prev = snap(weekStart.minusWeeks(1), true);
        when(weeklyRepository.findTop12ByOrderByWeekStartDesc()).thenReturn(List.of(self, prev));

        WeeklySignalAccuracyDto dto = service(sunday).generateWeeklyReport("system");

        // 자신 제외 → prior=[true](지난 주 1개) + 이번 주 true = 스트릭 2 → 경고
        assertThat(dto.getWarnings()).anySatisfy(w -> assertThat(w).contains("수급 역상관 지속 2주째"));
    }

    // ---- helpers ----

    private SignalOutcome sig(String type, Integer supply) {
        return SignalOutcome.builder()
                .signalType(type).stockCode("005930").signalDate(LocalDate.of(2026, 7, 1))
                .signalScore(60).priceAtSignal(new BigDecimal("10000"))
                .supplyDemandAtSignal(supply).pctChange3d(new BigDecimal("1.00"))
                .alpha3d(new BigDecimal("1.0")).hit(true).build();
    }

    private List<SignalOutcome> strongSupply(int n, boolean hit, String alpha) {
        List<SignalOutcome> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(SignalOutcome.builder()
                    .signalType("BUY").stockCode("005930").signalDate(LocalDate.of(2026, 6, 20))
                    .signalScore(60).priceAtSignal(new BigDecimal("10000"))
                    .supplyDemandAtSignal(16).pctChange3d(new BigDecimal("1.00"))
                    .alpha3d(new BigDecimal(alpha)).hit(hit).build());
        }
        return rows;
    }

    private SignalWeeklyAccuracy snap(LocalDate weekStart, boolean supplyInverted) {
        return SignalWeeklyAccuracy.builder()
                .weekStart(weekStart).weekEnd(weekStart.plusDays(6))
                .weeklyN(20).cumulativeN(50).supplyInverted(supplyInverted)
                .generatedBy("system").build();
    }
}
