package com.myplatform.backend.service;

import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import com.myplatform.backend.service.StockCatalystService.StockRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 모닝 브리핑 재료 워밍 (V31) — BUY 컷 필터 / 상한 / 중복 제거 / 예외 격리.
 *
 * P2-CAT1: 종목당 1콜 → <b>배치 1콜</b>(classifyBatch)로 전환. 상한(5)이 quota 가드 — 배치 대상
 * 선정(컷·중복·상한)이 깨지면 비용 사고. (개별 종목 격리·뉴스수집은 classifyBatch 내부 책임.)
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

    @Captor private ArgumentCaptor<List<StockRef>> refsCaptor;

    private RecommendationSnapshot snapshot(String code, String name, int score) {
        RecommendationSnapshot s = new RecommendationSnapshot();
        s.setStockCode(code);
        s.setStockName(name);
        s.setTotalScore(score);
        return s;
    }

    private List<String> codesOf(List<StockRef> refs) {
        return refs.stream().map(StockRef::code).toList();
    }

    @Test
    @DisplayName("BUY 컷(55) 이상만 배치 대상 — 48점 종목 제외")
    void warm_onlyAboveBuyCut() {
        when(snapshotRepository.findLatestSnapshot()).thenReturn(List.of(
                snapshot("005930", "삼성전자", 82),
                snapshot("035420", "NAVER", 48)));

        service.warmCatalysts();

        verify(stockCatalystService).classifyBatch(refsCaptor.capture());
        assertThat(codesOf(refsCaptor.getValue())).containsExactly("005930");   // 48점 제외
    }

    @Test
    @DisplayName("상한 5종목 — 컷 이상 7종목이어도 배치는 5건까지 (Gemini quota 가드)")
    void warm_capsAtMax() {
        List<RecommendationSnapshot> seven = IntStream.rangeClosed(1, 7)
                .mapToObj(i -> snapshot("00000" + i, "종목" + i, 60 + i))
                .toList();
        when(snapshotRepository.findLatestSnapshot()).thenReturn(seven);

        service.warmCatalysts();

        verify(stockCatalystService).classifyBatch(refsCaptor.capture());
        assertThat(refsCaptor.getValue()).hasSize(MorningBriefingService.CATALYST_WARM_MAX);
    }

    @Test
    @DisplayName("같은 종목 중복 행은 배치에 1건만")
    void warm_dedupes() {
        when(snapshotRepository.findLatestSnapshot()).thenReturn(List.of(
                snapshot("005930", "삼성전자", 82),
                snapshot("005930", "삼성전자", 80)));

        service.warmCatalysts();

        verify(stockCatalystService).classifyBatch(refsCaptor.capture());
        assertThat(codesOf(refsCaptor.getValue())).containsExactly("005930");
    }

    @Test
    @DisplayName("배치 분류 예외 → 워밍이 예외 전파 없이 완료 (외곽 격리)")
    void warm_isolatesBatchFailure() {
        when(snapshotRepository.findLatestSnapshot()).thenReturn(List.of(
                snapshot("005930", "삼성전자", 82)));
        when(stockCatalystService.classifyBatch(any())).thenThrow(new RuntimeException("Gemini 장애"));

        service.warmCatalysts();   // 예외 전파 없이 완료되어야 함

        verify(stockCatalystService).classifyBatch(any());
    }

    @Test
    @DisplayName("스냅샷 없음 → 배치 미호출")
    void warm_skipsWhenNoSnapshot() {
        when(snapshotRepository.findLatestSnapshot()).thenReturn(List.of());

        service.warmCatalysts();

        verify(stockCatalystService, never()).classifyBatch(any());
    }
}
