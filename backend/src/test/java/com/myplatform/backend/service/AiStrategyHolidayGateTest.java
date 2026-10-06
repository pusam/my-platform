package com.myplatform.backend.service;

import com.myplatform.backend.repository.AiStrategySnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 전략 스냅샷 크론은 휴장일에 돌지 않는다(2026-10-06).
 *
 * <p>재현: 크론 요일 필드(MON-FRI)는 공휴일을 모른다 — 대체공휴일 10/5 에 스캘핑(30분)·중장기(1시간) 로테이션이 그대로 돌아
 * <b>34회</b> 스냅샷을 만들었다(내용은 직전 거래일 10/2 시세, 날짜만 휴장일). 그때마다 Gemini 를 불렀는데, 무료 일일 한도는
 * 태평양 자정(한국 16시)에 초기화되므로 16시 이후 휴장일 회차(10/5 테마 붙은 회차 11번)는 <b>다음 거래일(10/6)</b> 몫을 쓴다.
 * '오늘' 날짜로 쓰는 MON-FRI 크론은 휴장일 게이트를 거친다(CLAUDE.md §4c 휴장일 달력).
 *
 * <p>장 마감 보정(15:40)은 시각 검사가 없어 벽시계와 무관하게 재현된다. 스캘핑·중장기 크론은 자체 시각 검사(08~20시)가 있어
 * 그 밖의 시각에 돌리면 수정 전에도 통과한다 — 게이트를 시각 검사보다 앞에 둔다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiStrategyHolidayGateTest {

    @Mock private AiStrategySnapshotRepository snapshotRepository;
    @Mock private MarketCalendarService marketCalendarService;
    @Mock private QuantScreenerService quantScreenerService;
    @Mock private StockPriceService stockPriceService;
    @Mock private GeminiService geminiService;
    @Mock private InvestorTradeService investorTradeService;
    @Mock private EarningSurpriseService earningSurpriseService;
    @Mock private StockStatusService stockStatusService;

    @InjectMocks private AiStrategySnapshotService service;

    private void holiday(boolean closed) {
        when(marketCalendarService.isMarketClosed()).thenReturn(closed);
        when(marketCalendarService.isMarketClosed(any())).thenReturn(closed);
    }

    @Test
    @DisplayName("재현: 휴장일엔 장 마감 보정(15:40)이 스냅샷을 건드리지 않는다")
    void closingBatchSkipsOnHoliday() {
        holiday(true);

        service.updateClosingPrices();

        verifyNoInteractions(snapshotRepository, stockPriceService);
    }

    @Test
    @DisplayName("재현: 휴장일엔 스캘핑 로테이션이 후보 수집·Gemini·저장을 하지 않는다")
    void scalpingRotationSkipsOnHoliday() {
        holiday(true);

        service.collectScalpingSnapshot();

        verifyNoInteractions(snapshotRepository, quantScreenerService, geminiService, investorTradeService, stockPriceService);
    }

    @Test
    @DisplayName("재현: 휴장일엔 중장기 로테이션(스윙·턴어라운드·가치)도 돌지 않는다")
    void longTermRotationSkipsOnHoliday() {
        holiday(true);

        service.collectLongTermSnapshots();

        verifyNoInteractions(snapshotRepository, quantScreenerService, geminiService, earningSurpriseService, stockPriceService);
    }

    @Test
    @DisplayName("거래일엔 종전대로 — 장 마감 보정이 전략별 최신 스냅샷을 읽는다")
    void tradingDayStillRuns() {
        holiday(false);

        service.updateClosingPrices();

        verify(snapshotRepository, atLeastOnce()).findLatestByStrategyType(any());
    }
}
