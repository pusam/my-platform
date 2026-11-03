package com.myplatform.backend.service;

import com.myplatform.backend.dto.CompositeSignalDto;
import com.myplatform.backend.repository.StockPriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * scanTopRanked 의 @Async self-invocation 회귀 가드.
 * bare 호출(this.triggerRankingComputation)은 프록시를 우회해 computeRanking(KIS 1~3분)을
 * 요청 스레드에서 동기 실행 → nginx 타임아웃. self.trigger 경유로 async 유지해야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompositeSignalServiceTest {

    @Mock private ChartPatternService chartPatternService;
    @Mock private StockPriceService stockPriceService;
    @Mock private InvestorTradeService investorTradeService;
    @Mock private AiStockAnalysisService aiStockAnalysisService;
    @Mock private StockMasterService stockMasterService;
    @Mock private StockPriceRepository stockPriceRepository;
    @Mock private CacheManager cacheManager;
    @Mock private ObjectProvider<SignalOutcomeService> signalOutcomeProvider;
    @Mock private Cache cache;
    @Mock private CompositeSignalService self;

    @InjectMocks private CompositeSignalService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "self", self);   // @Autowired 필드 주입 대체
    }

    @Test
    @DisplayName("scanTopRanked 캐시 miss — self.triggerRankingComputation(@Async 프록시) 경유 + 즉시 빈 리스트 (요청 스레드 동기실행 금지)")
    void scanTopRanked_missRoutesThroughSelfProxy() {
        when(cacheManager.getCache("chartPatterns")).thenReturn(cache);
        when(cache.get("rank:20")).thenReturn(null);   // miss

        List<CompositeSignalDto> result = service.scanTopRanked(20);

        assertThat(result).isEmpty();                    // 즉시 빈 리스트 반환
        verify(self).triggerRankingComputation(20);      // self(프록시) 경유 — bare 호출이면 mock self 미호출로 실패
        // computeRanking 동기 실행 안 함(= 무거운 평가 deps 미접촉)
        verifyNoInteractions(chartPatternService, stockMasterService);
    }

    // ==================== 조회 실패는 '안 맞음'이 아니다 (2026-10-19) ====================
    // 재현: 다섯 신호 평가는 예외·데이터 없음을 전부 matched=false 로 돌려줘 '평가했는데 안 맞음'과 같았다.

    @Test
    @DisplayName("재현: 다섯 신호 조회가 전부 실패하면 matched 0 · unavailableCount 5 — 각 신호는 unavailable")
    void allLookupsFailingAreUnavailable() {
        org.mockito.Mockito.when(chartPatternService.detectPatterns("005930")).thenThrow(new RuntimeException("KIS"));
        org.mockito.Mockito.when(chartPatternService.detectSupportResistance("005930")).thenThrow(new RuntimeException("KIS"));
        org.mockito.Mockito.when(chartPatternService.computeVolumeProfile("005930")).thenReturn(null);
        org.mockito.Mockito.when(investorTradeService.getConsecutiveBuyStocks(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenThrow(new RuntimeException("DB"));
        org.mockito.Mockito.when(aiStockAnalysisService.getAnalysis()).thenReturn(null);

        com.myplatform.backend.dto.CompositeSignalDto dto = service.evaluate("005930");

        assertThat(dto.getMatchedCount()).isZero();
        assertThat(dto.getUnavailableCount()).isEqualTo(5);
        assertThat(dto.getSignals()).allMatch(com.myplatform.backend.dto.CompositeSignalDto.Signal::isUnavailable);
    }

    @Test
    @DisplayName("평가했는데 안 맞은 신호는 unavailable 이 아니다 — 패턴 없음·지지선 없음")
    void evaluatedButUnmatchedIsAvailable() {
        org.mockito.Mockito.when(chartPatternService.detectPatterns("005930")).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(chartPatternService.detectSupportResistance("005930"))
                .thenReturn(new com.myplatform.backend.dto.SupportResistanceDto());

        com.myplatform.backend.dto.CompositeSignalDto dto = service.evaluate("005930");

        assertThat(signal(dto, "PATTERN").isUnavailable()).isFalse();
        assertThat(signal(dto, "PATTERN").isMatched()).isFalse();
        assertThat(signal(dto, "SUPPORT").isUnavailable()).isFalse();
    }

    @Test
    @DisplayName("수급: 한쪽 조회가 실패하고 다른 쪽에 없으면 판정 불가, 다른 쪽에 있으면 매칭")
    void supplyPartialFailure() {
        com.myplatform.backend.dto.ConsecutiveBuyDto inst = new com.myplatform.backend.dto.ConsecutiveBuyDto();
        inst.setStockCode("000660");
        org.mockito.Mockito.when(investorTradeService.getConsecutiveBuyStocks("FOREIGN", 3)).thenThrow(new RuntimeException("DB"));
        org.mockito.Mockito.when(investorTradeService.getConsecutiveBuyStocks("INSTITUTION", 3)).thenReturn(java.util.List.of(inst));

        assertThat(signal(service.evaluate("005930"), "SUPPLY").isUnavailable()).isTrue();
        com.myplatform.backend.dto.CompositeSignalDto.Signal hit = signal(service.evaluate("000660"), "SUPPLY");
        assertThat(hit.isMatched()).isTrue();
        assertThat(hit.isUnavailable()).isFalse();
    }

    private static com.myplatform.backend.dto.CompositeSignalDto.Signal signal(
            com.myplatform.backend.dto.CompositeSignalDto dto, String id) {
        return dto.getSignals().stream().filter(s -> id.equals(s.getId())).findFirst().orElseThrow();
    }
}
