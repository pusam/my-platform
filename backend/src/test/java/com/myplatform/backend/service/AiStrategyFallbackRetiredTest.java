package com.myplatform.backend.service;

import com.myplatform.backend.entity.AiStrategySnapshot.StrategyType;
import com.myplatform.backend.repository.AiStrategySnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 전략 후보가 없을 때 '대체 목록'을 추천처럼 저장하지 않는다(2026-10-02, §4c).
 *
 * <p><b>무엇이 있었나</b>: 전략 스크리너가 빈 목록이면 ① 네이버 시가총액 상위 → ② 네이버 거래상위 HTML → ③ 고정 대형주
 * 5종목(삼성전자·SK하이닉스…, 50점 "시가총액 상위 대표주") 순으로 채웠다. 운영 실측: AI 스캘핑 30일 164회 전부가
 * "시총 상위 대형주"(60~64점)였고, 그 1~3위가 종합추천 AI 시드('AI전략1~3위' 태그)로 들어갔다 — 전략이 고른 적 없는 종목이다.
 *
 * <p>이제 후보가 없으면 그 회차는 저장하지 않는다 — 직전 스냅샷이 그 생성 시각과 함께 남는다.
 * ⚠ 이 테스트가 깨지면 <b>그게 의도다</b>: 빈 화면을 피하려고 그럴듯한 종목을 채우지 말 것.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiStrategyFallbackRetiredTest {

    @Mock private AiStrategySnapshotRepository snapshotRepository;
    @Mock private MarketCalendarService marketCalendarService;
    @Mock private QuantScreenerService quantScreenerService;
    @Mock private StockPriceService stockPriceService;
    @Mock private GeminiService geminiService;
    @Mock private InvestorTradeService investorTradeService;
    @Mock private EarningSurpriseService earningSurpriseService;
    @Mock private StockStatusService stockStatusService;

    @InjectMocks private AiStrategySnapshotService service;

    @Test
    @DisplayName("재현: 모멘텀 후보 0건이면 시총 상위 대형주를 '스캘핑 추천'으로 저장하던 것 — 이제 저장하지 않는다")
    void emptyScalpingCandidatesSaveNothing() {
        when(quantScreenerService.getMomentumStocks(anyInt())).thenReturn(List.of());

        service.collectAndSaveSnapshot(StrategyType.SCALPING);

        verify(snapshotRepository, never()).saveAll(any());
        verify(stockPriceService, never()).getStockPrices(any());
    }

    @Test
    @DisplayName("스윙·가치도 같다 — 스크리너가 비면 저장하지 않는다")
    void emptySwingAndValueSaveNothing() {
        when(quantScreenerService.getMagicFormulaStocks(anyInt(), any())).thenReturn(List.of());
        when(quantScreenerService.getLowPegStocks(any(), any(), anyInt())).thenReturn(List.of());

        service.collectAndSaveSnapshot(StrategyType.SWING);
        service.collectAndSaveSnapshot(StrategyType.VALUE);

        verify(snapshotRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("대체 목록 메서드는 지워졌다 — 되살리지 말 것")
    void fallbackMethodsAreGone() {
        assertThat(Arrays.stream(AiStrategySnapshotService.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(n -> n.equals("createFallbackStocks") || n.startsWith("crawlNaver") || n.startsWith("fetchNaver"))
                .toList())
                .isEmpty();
    }
}
