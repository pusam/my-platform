package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.WeeklySignalAccuracyDto;
import com.myplatform.backend.dto.WeeklySignalAccuracyDto.CategoryTrend;
import com.myplatform.backend.entity.SignalOutcome;
import com.myplatform.backend.entity.SignalWeeklyAccuracy;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.SignalWeeklyAccuracyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 시그널 예측력 주간 측정 배치 (P1-6 측정 상설화).
 *
 * <p>"2-4주 뒤 수동 재측정" 계획을 주간 자동 배치로 상설화 — 어떤 신호가 실제 수익을 내는지 측정하는
 * 상시 피드백 루프. 매주 일요일 저녁 (카테고리 × regime × 밴드) 적중률/평균 alpha/표본수를 집계해
 * {@link SignalWeeklyAccuracy} 스냅샷(주 1행)으로 영속화하고, 모닝브리핑 채널로 요약 발송한다.
 *
 * <p><b>산식 무관</b>: 종합점수/가중치는 건드리지 않는다 — 측정 전용. 집계 로직은
 * {@link WeeklyAccuracyAggregator}(순수 함수·테스트)에 분리.
 *
 * <p><b>락</b>: {@link SchedulerLockService}(fail-open) 적용 — 봇 크론 아님(중복 실행돼도 같은 주 UPSERT).
 */
@Service
@Slf4j
public class SignalWeeklyReportService {

    /** 누적 기준을 phase-38 컷오프까지 확장하기 위한 충분히 큰 days (resolveAccuracyFrom 이 컷오프로 클램프). */
    private static final int CUMULATIVE_LOOKBACK_DAYS = 3650;
    private static final String WEEKLY_LOCK = "signal.weekly-report";

    private final SignalOutcomeRepository outcomeRepository;
    private final SignalWeeklyAccuracyRepository weeklyRepository;
    private final SchedulerLockService schedulerLockService;
    private final ObjectProvider<TelegramNotificationService> telegramProvider;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SignalWeeklyReportService(SignalOutcomeRepository outcomeRepository,
                                     SignalWeeklyAccuracyRepository weeklyRepository,
                                     SchedulerLockService schedulerLockService,
                                     ObjectProvider<TelegramNotificationService> telegramProvider,
                                     ObjectMapper objectMapper,
                                     Clock clock) {
        this.outcomeRepository = outcomeRepository;
        this.weeklyRepository = weeklyRepository;
        this.schedulerLockService = schedulerLockService;
        this.telegramProvider = telegramProvider;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 주간 시그널 예측력 측정 — 매주 일요일 18:00 (KST).
     *
     * <p>기존 §5 잡과 무충돌: 19:30 시그널 평가는 MON-FRI, 20:05 야간 발굴도 별 시각. 일요일 저녁은 비어 있음.
     * fail-open 락(봇 크론 아님) — 더블런 시 같은 주 UPSERT 라 무해.
     */
    @Scheduled(scheduler = "batchScheduler", cron = "0 0 18 * * SUN", zone = "Asia/Seoul")
    public void weeklyReportCron() {
        if (!schedulerLockService.tryLock(WEEKLY_LOCK, Duration.ofMinutes(20))) {
            log.debug("[주간측정] 다른 인스턴스에서 실행 중 — 스킵");
            return;
        }
        try {
            generateWeeklyReport("system");
        } catch (Exception e) {
            log.error("[주간측정] 주간 예측력 리포트 생성 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 직전 완료 주(월~일, 일요일이면 오늘 포함) 예측력 측정 → 스냅샷 UPSERT + 텔레그램. 수동 트리거도 이 경로.
     */
    @Transactional
    public WeeklySignalAccuracyDto generateWeeklyReport(String triggeredBy) {
        LocalDate today = LocalDate.now(clock);
        LocalDate weekEnd = today.getDayOfWeek() == DayOfWeek.SUNDAY
                ? today
                : today.with(DayOfWeek.SUNDAY).minusWeeks(1);
        LocalDate weekStart = weekEnd.minusDays(6);

        // 이번 주 = signalDate 가 [weekStart, weekEnd] & 평가완료 & board(STRONG_BUY/BUY) 격리.
        List<SignalOutcome> weeklyRows = SignalOutcomeService.filterBoardSignals(
                outcomeRepository.findEvaluatedBetween(weekStart, weekEnd));

        // 누적 = phase-38 컷오프 이후 board 시그널 전체(현재 산식 점수만). getAccuracyByBand 와 동일 기준.
        LocalDate cumulativeFrom = SignalOutcomeService.resolveAccuracyFrom(CUMULATIVE_LOOKBACK_DAYS, today);
        List<SignalOutcome> cumulativeRows = SignalOutcomeService.filterBoardSignals(
                outcomeRepository.findEvaluatedSince(cumulativeFrom));

        // 직전 스냅샷들의 supplyInverted 플래그 (이번 주 자신 제외, 최신 먼저) — 스트릭 계산 입력.
        List<Boolean> priorSupplyInverted = weeklyRepository.findTop12ByOrderByWeekStartDesc().stream()
                .filter(s -> !s.getWeekStart().equals(weekStart))
                .map(SignalWeeklyAccuracy::isSupplyInverted)
                .collect(Collectors.toList());

        WeeklySignalAccuracyDto dto = WeeklyAccuracyAggregator.assembleReport(
                weekStart, weekEnd, weeklyRows, cumulativeRows, priorSupplyInverted);
        dto.setGeneratedAt(LocalDateTime.now(clock));
        boolean supplyInverted = WeeklyAccuracyAggregator.isSupplyInverted(cumulativeRows);

        persistSnapshot(weekStart, weekEnd, dto, supplyInverted, triggeredBy);
        sendTelegramSummary(dto, priorSupplyInverted, supplyInverted);

        log.info("[주간측정] {} ~ {} 완료 (주간 n={}, 누적 n={}, 경고 {}건)",
                weekStart, weekEnd, dto.getWeeklyN(), dto.getCumulativeN(), dto.getWarnings().size());
        return dto;
    }

    private void persistSnapshot(LocalDate weekStart, LocalDate weekEnd,
                                 WeeklySignalAccuracyDto dto, boolean supplyInverted, String triggeredBy) {
        SignalWeeklyAccuracy entity = weeklyRepository.findByWeekStart(weekStart)
                .orElseGet(SignalWeeklyAccuracy::new);
        entity.setWeekStart(weekStart);
        entity.setWeekEnd(weekEnd);
        entity.setWeeklyN(dto.getWeeklyN());
        entity.setCumulativeN(dto.getCumulativeN());
        entity.setSupplyInverted(supplyInverted);
        entity.setReportJson(toJsonQuiet(dto));
        entity.setGeneratedBy(triggeredBy == null ? "system" : triggeredBy);
        entity.setGeneratedAt(dto.getGeneratedAt());
        weeklyRepository.save(entity);
    }

    /** 최신 주간 리포트 (파싱된 DTO). 없거나 파싱 실패면 empty. */
    public Optional<WeeklySignalAccuracyDto> getLatestReport() {
        return weeklyRepository.findFirstByOrderByWeekStartDesc()
                .map(this::parseQuiet)
                .filter(java.util.Objects::nonNull)
                .map(Optional::of)
                .orElseGet(Optional::empty);
    }

    /** 최근 N주 히스토리 (헤드라인 스냅샷 엔티티). 추세 조회용. */
    public List<SignalWeeklyAccuracy> getHistory() {
        return weeklyRepository.findTop12ByOrderByWeekStartDesc();
    }

    // ================================================================
    //  텔레그램 요약 (모닝브리핑 채널)
    // ================================================================

    private void sendTelegramSummary(WeeklySignalAccuracyDto dto, List<Boolean> priorSupplyInverted,
                                     boolean supplyInverted) {
        TelegramNotificationService telegram = telegramProvider.getIfAvailable();
        if (telegram == null || !telegram.isEnabled()) return;
        try {
            telegram.sendBriefing(buildTelegramSummary(dto, priorSupplyInverted, supplyInverted));
        } catch (Exception e) {
            log.warn("[주간측정] 텔레그램 요약 발송 실패: {}", e.getMessage());
        }
    }

    /** 텔레그램 본문 — 카테고리별 적중률/전주 대비/경고. 표본부족 셀은 "(표본부족)" 명시. */
    private String buildTelegramSummary(WeeklySignalAccuracyDto dto, List<Boolean> priorSupplyInverted,
                                        boolean supplyInverted) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>주간 시그널 예측력 측정</b> (").append(dto.getWeekStart())
                .append(" ~ ").append(dto.getWeekEnd()).append(")\n");
        sb.append(String.format("• 표본: 이번 주 %d건 / 누적 %d건%n", dto.getWeeklyN(), dto.getCumulativeN()));

        sb.append("\n<b>카테고리별 적중률 (누적, 강세 표본)</b>\n");
        for (CategoryTrend t : dto.getCategoryTrends()) {
            String hit = t.getCumulativeHitRate() == null ? "-" : t.getCumulativeHitRate().toPlainString() + "%";
            String alpha = t.getCumulativeAvgAlpha() == null ? "-" : t.getCumulativeAvgAlpha().toPlainString() + "%";
            sb.append(String.format("• %s: 적중 %s / alpha %s (n=%d)%s%n",
                    t.getLabel(), hit, alpha, t.getCumulativeN(),
                    t.isCumulativeInsufficient() ? " ⚠표본부족" : ""));
            // 전주 대비(양쪽 표본 충분할 때만 델타 노출)
            if (t.getHitRateDelta() != null) {
                sb.append(String.format("   └ 이번 주 대비 누적: 적중 %+.2f%%p / alpha %s%%p%s%n",
                        t.getHitRateDelta(),
                        t.getAvgAlphaDelta() == null ? "-" : t.getAvgAlphaDelta().toPlainString(),
                        t.isWorsening() ? " 📉악화" : ""));
            }
        }

        if (!dto.getWarnings().isEmpty()) {
            sb.append("\n⚠️ <b>경고</b>\n");
            for (String w : dto.getWarnings()) sb.append("• ").append(w).append('\n');
        }

        sb.append("\n<i>측정 전용 — 종합점수 산식 무변경. /api/signal-outcomes/weekly-report</i>");
        return sb.toString();
    }

    // ================================================================
    //  JSON helper
    // ================================================================

    private String toJsonQuiet(WeeklySignalAccuracyDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            log.warn("[주간측정] reportJson 직렬화 실패: {}", e.getMessage());
            return null;
        }
    }

    private WeeklySignalAccuracyDto parseQuiet(SignalWeeklyAccuracy entity) {
        if (entity.getReportJson() == null) return null;
        try {
            return objectMapper.readValue(entity.getReportJson(), WeeklySignalAccuracyDto.class);
        } catch (Exception e) {
            log.warn("[주간측정] reportJson 파싱 실패 (week={}): {}", entity.getWeekStart(), e.getMessage());
            return null;
        }
    }
}
