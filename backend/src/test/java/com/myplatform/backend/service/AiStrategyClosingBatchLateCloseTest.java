package com.myplatform.backend.service;

import com.myplatform.backend.repository.AiStrategySnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * AI 전략 '장 마감 확정 배치'(15:40)는 장이 늦게 닫히는 날 돌지 않는다(2026-11-10 수능일 리허설).
 *
 * <p>재현: 이 배치는 전략별 최신 스냅샷의 현재가·등락률을 '최종 종가'로 덮어쓴다 — 그 가격이 추천 트랙레코드
 * ({@code BacktestService})의 추천가가 된다. 2026-11-19(정규장 10:00~16:30)의 15:40 은 장 마감 한 시간 전이라 장중 가격이 종가라는
 * 이름으로 저장된다. 그날 뒤로 다시 보정하는 회차는 없으므로 건너뛰면 스냅샷은 만든 시각의 가격으로 남는다(종가인 척하지 않는다).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiStrategyClosingBatchLateCloseTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private AiStrategySnapshotRepository snapshotRepository;
    @Spy private MarketCalendarService marketCalendarService = new MarketCalendarService();
    @Mock private QuantScreenerService quantScreenerService;
    @Mock private StockPriceService stockPriceService;
    @Mock private GeminiService geminiService;
    @Mock private InvestorTradeService investorTradeService;
    @Mock private EarningSurpriseService earningSurpriseService;
    @Mock private StockStatusService stockStatusService;

    @InjectMocks private AiStrategySnapshotService service;

    private void at(LocalDate day, LocalTime time) {
        service.clock = Clock.fixed(LocalDateTime.of(day, time).atZone(KST).toInstant(), KST);
    }

    @Test
    @DisplayName("재현: 수능일 15:40 은 장중이다 — 장중 가격을 '최종 종가'로 스냅샷에 덮어쓰지 않는다")
    void lateCloseDaySkipsClosingBatch() {
        at(LocalDate.of(2026, 11, 19), LocalTime.of(15, 40));

        service.updateClosingPrices();

        verifyNoInteractions(snapshotRepository, stockPriceService);
    }

    @Test
    @DisplayName("평소 거래일 15:40 은 종전대로 최신 스냅샷을 보정한다")
    void ordinaryDayStillRuns() {
        at(LocalDate.of(2026, 11, 18), LocalTime.of(15, 40));

        service.updateClosingPrices();

        verify(snapshotRepository, atLeastOnce()).findLatestByStrategyType(any());
    }
}
