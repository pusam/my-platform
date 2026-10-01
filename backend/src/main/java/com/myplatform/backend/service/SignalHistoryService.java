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
    /** 현재 산식 표본 시작일(단일 출처). 미주입이면 미정 — 요약은 비고 화면은 '검증 중'. */
    private final org.springframework.beans.factory.ObjectProvider<SignalSampleBoundary> sampleBoundaryProvider;

    public SignalHistoryDto getHistory(String stockCode) {
        List<SignalOutcome> rows = signalOutcomeRepository
                .findByStockCodeAndSignalDateGreaterThanEqualOrderBySignalDateDesc(
                        stockCode, LocalDate.now().minusDays(WINDOW_DAYS));
        SignalSampleBoundary b = sampleBoundaryProvider == null ? null : sampleBoundaryProvider.getIfAvailable();
        return assemble(stockCode, rows, b == null ? SignalSampleBoundary.Boundary.UNSET : b.current());
    }

    /**
     * 이력 행 → DTO 조립. <b>순수 함수(테스트 대상)</b>.
     *
     * <ul>
     *   <li>추천 신호(STRONG_BUY/BUY)만 — 무작위 대조군(CONTROL_RANDOM)·급등 감지(SURGE_*)·AI/복합 행은 이 종목의
     *       추천 성적이 아니다(2026-10-01: 90일 평가 3,723행 중 추천 157 · 대조군 35 · 급등 3,510 이 한 요약에 섞였다).</li>
     *   <li>같은 종목·날짜 승격(BUY→STRONG_BUY)은 최초 기록 1건 — 측정 규칙과 같다.</li>
     *   <li>결과는 교정 D+3 값. 교정 평가 전이면 평가 대기, OK 가 아니면 "평가 제외(사유)" — 미평가를 미스로 세지 않는다.</li>
     *   <li>요약은 현재 산식 표본(경계 이후)의 평가 완료 행만. 경계 이전 행은 목록에만 "이전 산식"으로 남는다.</li>
     * </ul>
     */
    static SignalHistoryDto assemble(String stockCode, List<SignalOutcome> rows, SignalSampleBoundary.Boundary boundary) {
        SignalSampleBoundary.Boundary b = boundary == null ? SignalSampleBoundary.Boundary.UNSET : boundary;
        List<SignalOutcome> board = rows == null ? List.of() : SignalOutcomeService.dedupPerStockDay(
                SignalOutcomeService.filterBoardSignals(rows.stream().filter(java.util.Objects::nonNull).toList()));

        List<SignalHistoryDto.Item> items = new ArrayList<>();
        int evaluated = 0, hits = 0, pending = 0;
        BigDecimal alphaSum = BigDecimal.ZERO;
        int alphaCount = 0;

        for (SignalOutcome s : board.stream()
                .sorted(java.util.Comparator.comparing(SignalOutcome::getSignalDate,
                        java.util.Comparator.nullsLast(java.util.Comparator.<LocalDate>naturalOrder())).reversed())
                .toList()) {
            boolean inSample = b.isSet() && s.getSignalDate() != null && !s.getSignalDate().isBefore(b.since());
            String status = s.getD3Status();
            boolean ok = SignalD3Evaluator.Status.OK.name().equals(status) && s.getD3PctChange() != null;
            boolean isPending = status == null;
            if (inSample && ok) {
                evaluated++;
                if (Boolean.TRUE.equals(s.getD3Hit())) hits++;
                if (s.getD3Alpha() != null) {
                    alphaSum = alphaSum.add(s.getD3Alpha());
                    alphaCount++;
                }
            } else if (inSample && isPending) {
                pending++;
            }
            items.add(SignalHistoryDto.Item.builder()
                    .signalDate(s.getSignalDate())
                    .signalType(s.getSignalType())
                    .signalScore(s.getSignalScore())
                    .hit(ok ? s.getD3Hit() : null)
                    .pending(isPending)
                    .alpha3d(ok ? s.getD3Alpha() : null)
                    .pctChange3d(ok ? s.getD3PctChange() : null)
                    .excludedReason(ok || isPending ? null : status)
                    .inCurrentSample(inSample)
                    .build());
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
                        .sampleSince(b.since())
                        .sampleStatus(b.status().name())
                        .build())
                .items(items)
                .build();
    }
}
