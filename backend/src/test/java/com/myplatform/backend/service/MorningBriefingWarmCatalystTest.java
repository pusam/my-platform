package com.myplatform.backend.service;

import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 모닝 브리핑 재료 워밍 (V31) — BUY 컷 필터 / 상한 / 중복 제거 / 예외 격리.
 *
 * 워밍은 종목당 네이버+Gemini 1회씩이라 상한(5)이 quota 가드 — 깨지면 비용 사고.
 */
@ExtendWith(MockitoExtension.class)
class MorningBriefingWarmCatalystTest {

    @Mock private InvestorTradeService investorTradeService;
    @Mock private WatchlistService watchlistService;
    @Mock private MarketTimingService marketTimingService;
    @Mock private QuantScreenerService quantScreenerService;
    @Mock private TelegramNotificationService telegramNotificationService;
    @Mock private RecommendationSnapshotRepository snapshotRepository;
    @Mock private StockCatalystService stockCatalystService;

    @InjectMocks
    private MorningBriefingService service;

    private RecommendationSnapshot snapshot(String code, String name, int score) {
        RecommendationSnapshot s = new RecommendationSnapshot();
        s.setStockCode(code);
        s.setStockName(name);
        s.setTotalScore(score);
        return s;
    }

    @Test
    @DisplayName("BUY 컷(55) 이상만 워밍 — 48점 종목은 분류 안 함")
    void warm_onlyAboveBuyCut() {
        when(snapshotRepository.findLatestSnapshot()).thenReturn(List.of(
                snapshot("005930", "삼성전자", 82),
                snapshot("035420", "NAVER", 48)));

        service.warmCatalysts();

        verify(stockCatalystService).getCatalyst("005930", "삼성전자");
        verify(stockCatalystService, never()).getCatalyst(eq("035420"), anyString());
    }

    @Test
    @DisplayName("상한 5종목 — 컷 이상 7종목이어도 5건까지만 (Gemini quota 가드)")
    void warm_capsAtMax() {
        List<RecommendationSnapshot> seven = IntStream.rangeClosed(1, 7)
                .mapToObj(i -> snapshot("00000" + i, "종목" + i, 60 + i))
                .toList();
        when(snapshotRepository.findLatestSnapshot()).thenReturn(seven);

        service.warmCatalysts();

        verify(stockCatalystService, times(MorningBriefingService.CATALYST_WARM_MAX))
                .getCatalyst(anyString(), anyString());
    }

    @Test
    @DisplayName("같은 종목 중복 행은 1회만 워밍")
    void warm_dedupes() {
        when(snapshotRepository.findLatestSnapshot()).thenReturn(List.of(
                snapshot("005930", "삼성전자", 82),
                snapshot("005930", "삼성전자", 80)));

        service.warmCatalysts();

        verify(stockCatalystService, times(1)).getCatalyst("005930", "삼성전자");
    }

    @Test
    @DisplayName("개별 종목 분류 예외 → 다음 종목 계속 (격리)")
    void warm_isolatesPerStockFailure() {
        when(snapshotRepository.findLatestSnapshot()).thenReturn(List.of(
                snapshot("005930", "삼성전자", 82),
                snapshot("000660", "SK하이닉스", 78)));
        when(stockCatalystService.getCatalyst("005930", "삼성전자"))
                .thenThrow(new RuntimeException("Gemini 장애"));

        service.warmCatalysts();   // 예외 전파 없이 완료되어야 함

        verify(stockCatalystService).getCatalyst("000660", "SK하이닉스");
    }

    @Test
    @DisplayName("스냅샷 없음 → 워밍 스킵 (분류 호출 0건)")
    void warm_skipsWhenNoSnapshot() {
        when(snapshotRepository.findLatestSnapshot()).thenReturn(List.of());

        service.warmCatalysts();

        verify(stockCatalystService, never()).getCatalyst(anyString(), anyString());
    }
}
