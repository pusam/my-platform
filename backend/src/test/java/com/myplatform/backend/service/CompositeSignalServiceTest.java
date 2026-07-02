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
}
