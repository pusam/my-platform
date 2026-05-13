package com.myplatform.backend.service;

import com.myplatform.backend.dto.SignalAccuracyDto;
import com.myplatform.backend.dto.SignalAccuracyDto.SignalStat;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.SignalOutcome;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 시그널 적중률 추적.
 *
 * 1) {@link #record} — 시그널 생성 시 호출 (RecommendationService 등이 통합).
 *    동일 (시그널/종목/날짜) 중복 INSERT 방지 — 같은 날 여러 번 발생해도 첫 시점만 기록.
 * 2) {@link #evaluatePendingSignals} — 매일 batchScheduler 가 호출. 3일 이상 지난 unevaluated
 *    항목의 현재 가격을 조회해 변동률 + hit 채움. hit 기준: +3% 이상.
 * 3) {@link #getAccuracy} — 최근 N일 시그널 타입별 적중률 / 평균 변동률 통계.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SignalOutcomeService {

    private final SignalOutcomeRepository repository;
    private final StockPriceService stockPriceService;

    private static final int EVALUATION_DELAY_DAYS = 3;
    private static final BigDecimal HIT_THRESHOLD_PCT = new BigDecimal("3.00");

    /**
     * 시그널 발생 기록. 같은 (type/stockCode/날짜) 중복은 무시 — 첫 발생 시점만 보존.
     */
    @Transactional
    public void record(String signalType, String stockCode, String stockName,
                       Integer signalScore, BigDecimal priceAtSignal) {
        if (signalType == null || stockCode == null || priceAtSignal == null
                || priceAtSignal.signum() <= 0) {
            return;
        }
        LocalDate today = LocalDate.now();
        if (!repository.findExisting(signalType, stockCode, today).isEmpty()) {
            return;
        }
        try {
            repository.save(SignalOutcome.builder()
                    .signalType(signalType)
                    .stockCode(stockCode)
                    .stockName(stockName)
                    .signalDate(today)
                    .signalScore(signalScore)
                    .priceAtSignal(priceAtSignal)
                    .build());
        } catch (Exception e) {
            log.debug("[SignalOutcome] record 실패 ({}/{}): {}", signalType, stockCode, e.getMessage());
        }
    }

    /**
     * 매일 19:30 — 3일 전 unevaluated 시그널 평가.
     * batchScheduler 로 트레이딩 부담 0.
     */
    @Scheduled(scheduler = "batchScheduler", cron = "0 30 19 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void evaluatePendingSignals() {
        LocalDate cutoff = LocalDate.now().minusDays(EVALUATION_DELAY_DAYS);
        List<SignalOutcome> pending = repository.findPendingEvaluation(cutoff);
        if (pending.isEmpty()) return;

        log.info("[SignalOutcome] 평가 대상 {}건 (signalDate ≤ {})", pending.size(), cutoff);
        int evaluated = 0;
        for (SignalOutcome outcome : pending) {
            try {
                StockPriceDto current = stockPriceService.getStockPrice(outcome.getStockCode());
                if (current == null || current.getCurrentPrice() == null
                        || current.getCurrentPrice().signum() <= 0) {
                    continue;
                }
                BigDecimal priceNow = current.getCurrentPrice();
                BigDecimal pct = priceNow.subtract(outcome.getPriceAtSignal())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(outcome.getPriceAtSignal(), 4, RoundingMode.HALF_UP);
                boolean hit = pct.compareTo(HIT_THRESHOLD_PCT) >= 0;

                outcome.setPriceAfter3d(priceNow);
                outcome.setPctChange3d(pct);
                outcome.setHit(hit);
                outcome.setEvaluatedAt(LocalDateTime.now());
                repository.save(outcome);
                evaluated++;
            } catch (Exception e) {
                log.debug("[SignalOutcome] 평가 실패 id={}: {}", outcome.getId(), e.getMessage());
            }
        }
        log.info("[SignalOutcome] 평가 완료 {}/{}", evaluated, pending.size());
    }

    /** 시그널별 적중률 통계 — 최근 days 일 기준. */
    public SignalAccuracyDto getAccuracy(int days) {
        LocalDate from = LocalDate.now().minusDays(Math.max(1, days));
        List<Object[]> rows = repository.aggregateStats(from);
        List<SignalStat> stats = new ArrayList<>();
        for (Object[] row : rows) {
            String type = (String) row[0];
            long total = ((Number) row[1]).longValue();
            long hits = row[2] == null ? 0L : ((Number) row[2]).longValue();
            BigDecimal avg = row[3] == null ? BigDecimal.ZERO : new BigDecimal(row[3].toString());
            BigDecimal hitRate = total == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(hits)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            stats.add(SignalStat.builder()
                    .signalType(type)
                    .totalSignals(total)
                    .hitCount(hits)
                    .hitRate(hitRate)
                    .avgPctChange(avg.setScale(2, RoundingMode.HALF_UP))
                    .build());
        }
        return SignalAccuracyDto.builder()
                .daysWindow(days)
                .stats(stats)
                .build();
    }
}
