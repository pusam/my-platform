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
                schedulerLockService, telegramProvider, objectMapper, clock, null);
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
    @DisplayName("일요일 실행 → weekEnd=직전 완료 주 일요일 (오늘 끝나는 주 아님 — 수~금이 아직 미평가라 월·화만 담기던 P1-C)")
    void weekBoundary_sunday() {
        // 2026-07-05 = 일요일 → 대상 주 = 06-22(월)~06-28(일). 그 주 금요일 시그널까지 수요일(7/1)
        // 평가 완료라 온전한 주다. 오늘 끝나는 주(6/29~7/5)를 잡으면 월·화 시그널만 평가돼 있다.
        LocalDate sunday = LocalDate.of(2026, 7, 5);
        assertThat(sunday.getDayOfWeek().getValue()).isEqualTo(7);

        WeeklySignalAccuracyDto dto = service(sunday).generateWeeklyReport("system");

        assertThat(dto.getWeekEnd()).isEqualTo(LocalDate.of(2026, 6, 28));
        assertThat(dto.getWeekStart()).isEqualTo(LocalDate.of(2026, 6, 22)); // 월요일
        verify(outcomeRepository).findEvaluatedBetween(LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 28));
    }

    @Test
    @DisplayName("수요일 수동 실행 → 직전 일요일 종료 주는 아직 미평가(금요일 시그널이 수요일 저녁 평가) → 한 주 더 뒤로 (A-9)")
    void weekBoundary_midweekGoesBackOneMore() {
        // 2026-07-08 = 수요일. 6/29~7/5 주의 금요일(7/3) 시그널은 7/8(수) 19:30 평가 — 아직 미완이므로
        // 그 주를 집계하면 "월·화 표본만 담긴 스냅샷"이 UPSERT 로 영속화된다(P1-C 의 수동 경로 재현).
        LocalDate wed = LocalDate.of(2026, 7, 8);
        assertThat(wed.getDayOfWeek().getValue()).isEqualTo(3);

        WeeklySignalAccuracyDto dto = service(wed).generateWeeklyReport("manual");

        assertThat(dto.getWeekEnd()).isEqualTo(LocalDate.of(2026, 6, 28));
        assertThat(dto.getWeekStart()).isEqualTo(LocalDate.of(2026, 6, 22));
    }

    @Test
    @DisplayName("목요일 수동 실행 → 직전 일요일 종료 주가 온전(수요일 저녁 평가 완료) → 그 주 유지")
    void weekBoundary_thursdayKeepsLastWeek() {
        LocalDate thu = LocalDate.of(2026, 7, 9);
        assertThat(thu.getDayOfWeek().getValue()).isEqualTo(4);

        WeeklySignalAccuracyDto dto = service(thu).generateWeeklyReport("manual");

        assertThat(dto.getWeekEnd()).isEqualTo(LocalDate.of(2026, 7, 5));
        assertThat(dto.getWeekStart()).isEqualTo(LocalDate.of(2026, 6, 29));
    }

    @Test
    @DisplayName("prior 스트릭 — 대상 주보다 나중(미래) 주 스냅샷은 제외 (A-10)")
    void priorFlags_excludeFutureWeeks() {
        // 일요일 크론(대상 주 6/22)보다 나중 주(6/29) 스냅샷이 수동 트리거로 이미 존재하는 상황
        LocalDate sunday = LocalDate.of(2026, 7, 5);
        SignalWeeklyAccuracy future = snap(LocalDate.of(2026, 6, 29), true);   // 미래 주 — 제외돼야
        SignalWeeklyAccuracy past = snap(LocalDate.of(2026, 6, 15), false);    // 과거 주 — 포함
        when(weeklyRepository.findTop12ByOrderByWeekStartDesc()).thenReturn(List.of(future, past));
        when(outcomeRepository.findEvaluatedSince(any())).thenReturn(strongSupply(10, false, "-2.0"));

        WeeklySignalAccuracyDto dto = service(sunday).generateWeeklyReport("system");

        // prior 가 [past=false]뿐이면 스트릭은 이번 주 1주째 — future(true)가 섞이면 2주째 경고가 떠버린다
        assertThat(dto.getWarnings()).noneSatisfy(w -> assertThat(w).contains("2주째"));
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
    @DisplayName("dedup 위임 — 같은 종목·같은 날 BUY→STRONG_BUY 승격 2행은 1건으로 계상 (P2-F)")
    void sameStockDayUpgrade_countedOnce() {
        LocalDate sunday = LocalDate.of(2026, 7, 5);
        SignalOutcome buy = sig("BUY", 12);
        SignalOutcome upgraded = sig("STRONG_BUY", 12);
        upgraded.setStockCode(buy.getStockCode());   // 같은 종목·같은 날 — 장중 등급 승격 시나리오
        buy.setCreatedAt(java.time.LocalDateTime.of(2026, 6, 24, 11, 30));
        upgraded.setCreatedAt(java.time.LocalDateTime.of(2026, 6, 24, 20, 5));
        when(outcomeRepository.findEvaluatedBetween(any(), any())).thenReturn(List.of(buy, upgraded));

        WeeklySignalAccuracyDto dto = service(sunday).generateWeeklyReport("system");

        assertThat(dto.getWeeklyN()).isEqualTo(1);   // 2행이 아니라 1건(마지막 기록)
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
        assertThat(saved.getWeekStart()).isEqualTo(LocalDate.of(2026, 6, 22));
        assertThat(saved.getWeekEnd()).isEqualTo(LocalDate.of(2026, 6, 28));
        assertThat(saved.getWeeklyN()).isEqualTo(2);
        assertThat(saved.getGeneratedBy()).isEqualTo("manual");
        assertThat(saved.getReportJson()).isNotBlank();
    }

    @Test
    @DisplayName("prior 스트릭 플래그 — 재생성 시 같은 weekStart 스냅샷은 제외(중복 카운트 방지)")
    void priorFlags_excludeCurrentWeek() {
        LocalDate sunday = LocalDate.of(2026, 7, 5);
        LocalDate weekStart = LocalDate.of(2026, 6, 22);

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

    /** 타입별 서로 다른 종목코드 — 같은 종목·같은 날 중복은 dedupPerStockDay 로 1건이 되므로(P2-F)
     *  "서로 다른 시그널 N건" 픽스처는 종목을 갈라야 한다(중복 시나리오는 전용 테스트에서 명시적으로). */
    private SignalOutcome sig(String type, Integer supply) {
        return SignalOutcome.builder()
                .signalType(type).stockCode("T-" + type).signalDate(LocalDate.of(2026, 6, 24))
                .signalScore(60).priceAtSignal(new BigDecimal("10000"))
                .supplyDemandAtSignal(supply).pctChange3d(new BigDecimal("1.00"))
                .alpha3d(new BigDecimal("1.0")).hit(true).build();
    }

    private List<SignalOutcome> strongSupply(int n, boolean hit, String alpha) {
        List<SignalOutcome> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(SignalOutcome.builder()
                    .signalType("BUY").stockCode(String.format("S%05d", i)).signalDate(LocalDate.of(2026, 6, 20))
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
