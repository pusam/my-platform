package com.myplatform.backend.service;

import com.myplatform.backend.dto.SignalHistoryDto;
import com.myplatform.backend.entity.SignalOutcome;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 종목별 신호 이력 실적 — 기존 {@code signal_outcome}(19:30 배치가 3거래일 후 평가) <b>재사용·read-only</b>.
 * 신규 수집/산식 변경 없음(조립·표시 전용). 집계는 순수 함수 {@link #assemble}(테스트 대상)로 분리.
 */
@Service
@RequiredArgsConstructor
public class SignalHistoryService {

    /** 이력 윈도우(일) — 결론카드 MFE/MAE(90일)와 동일 창. */
    public static final int WINDOW_DAYS = 90;

    private final SignalOutcomeRepository signalOutcomeRepository;

    public SignalHistoryDto getHistory(String stockCode) {
        List<SignalOutcome> rows = signalOutcomeRepository
                .findByStockCodeAndSignalDateGreaterThanEqualOrderBySignalDateDesc(
                        stockCode, LocalDate.now().minusDays(WINDOW_DAYS));
        return assemble(stockCode, rows);
    }

    /**
     * 이력 행 → DTO 조립. <b>순수 함수(테스트 대상)</b>.
     * 요약은 평가 완료 행만 집계, 평가 전 행은 pending 으로 분리(§4c — 미평가≠미스).
     * avgAlpha 는 alpha 있는 평가 행 평균(표본 없으면 null — 위장 금지).
     */
    static SignalHistoryDto assemble(String stockCode, List<SignalOutcome> rows) {
        List<SignalHistoryDto.Item> items = new ArrayList<>();
        int evaluated = 0, hits = 0, pending = 0;
        BigDecimal alphaSum = BigDecimal.ZERO;
        int alphaCount = 0;

        if (rows != null) {
            for (SignalOutcome s : rows) {
                if (s == null) continue;
                boolean isPending = s.getEvaluatedAt() == null;
                if (isPending) {
                    pending++;
                } else {
                    evaluated++;
                    if (Boolean.TRUE.equals(s.getHit())) hits++;
                    if (s.getAlpha3d() != null) {
                        alphaSum = alphaSum.add(s.getAlpha3d());
                        alphaCount++;
                    }
                }
                items.add(SignalHistoryDto.Item.builder()
                        .signalDate(s.getSignalDate())
                        .signalType(s.getSignalType())
                        .signalScore(s.getSignalScore())
                        .hit(isPending ? null : s.getHit())
                        .pending(isPending)
                        .alpha3d(s.getAlpha3d())
                        .pctChange3d(s.getPctChange3d())
                        .build());
            }
        }

        BigDecimal avgAlpha = alphaCount == 0 ? null
                : alphaSum.divide(BigDecimal.valueOf(alphaCount), 2, RoundingMode.HALF_UP);

        return SignalHistoryDto.builder()
                .stockCode(stockCode)
                .windowDays(WINDOW_DAYS)
                .summary(SignalHistoryDto.Summary.builder()
                        .evaluatedCount(evaluated)
                        .hitCount(hits)
                        .avgAlpha(avgAlpha)
                        .pendingCount(pending)
                        .build())
                .items(items)
                .build();
    }
}
