package com.myplatform.backend.service;

import com.myplatform.backend.service.RecommendationService.RecommendationDto;
import com.myplatform.backend.service.RecommendationService.Top5Response;
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
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * union 재료 일괄 워밍 (P2-CAT3) — 5트랙 dedup·상한 cap·null 스킵 + classifyBatch 위임.
 */
@ExtendWith(MockitoExtension.class)
class CatalystWarmingServiceTest {

    @Mock private RecommendationService recommendationService;
    @Mock private StockCatalystService stockCatalystService;
    @Mock private SchedulerLockService schedulerLockService;

    @InjectMocks
    private CatalystWarmingService service;

    @Captor private ArgumentCaptor<List<StockRef>> refsCaptor;

    private Top5Response top5(RecommendationDto... items) {
        return new Top5Response(List.of(items), "", false, Map.of());
    }

    private RecommendationDto dto(String code, String name) {
        return RecommendationDto.builder().stockCode(code).stockName(name).build();
    }

    private List<String> codesOf(List<StockRef> refs) {
        return refs.stream().map(StockRef::code).toList();
    }

    @Test
    @DisplayName("collectUnionRefs — 5트랙 수집 + 트랙 간 중복 dedup(첫 등장 순서)")
    void collectUnionRefs_dedupesAcrossTracks() {
        when(recommendationService.getValueTop10()).thenReturn(top5(dto("005930", "삼성전자"), dto("000660", "SK하이닉스")));
        when(recommendationService.getGrowthTop10()).thenReturn(top5(dto("000660", "SK하이닉스"), dto("035420", "NAVER")));
        // 나머지 트랙(oversold/earnings/smartmoney)은 미스텁 → null → 빈 리스트(best-effort)

        List<StockRef> refs = service.collectUnionRefs();

        assertThat(codesOf(refs)).containsExactly("005930", "000660", "035420");   // 000660 중복 제거
    }

    @Test
    @DisplayName("collectUnionRefs — 상한 UNION_WARM_MAX(25)에서 컷")
    void collectUnionRefs_capsAtMax() {
        RecommendationDto[] thirty = IntStream.rangeClosed(1, 30)
                .mapToObj(i -> dto(String.format("%06d", i), "종목" + i))
                .toArray(RecommendationDto[]::new);
        when(recommendationService.getValueTop10()).thenReturn(top5(thirty));

        List<StockRef> refs = service.collectUnionRefs();

        assertThat(refs).hasSize(CatalystWarmingService.UNION_WARM_MAX);   // 30 중 25만
    }

    @Test
    @DisplayName("collectUnionRefs — null/blank code·name 스킵, 트랙 예외는 best-effort 무시")
    void collectUnionRefs_skipsInvalidAndTrackFailure() {
        when(recommendationService.getValueTop10()).thenReturn(top5(
                dto("005930", "삼성전자"),
                dto(null, "노코드"),
                dto("000660", "  "),        // blank name
                dto("035420", "NAVER")));
        when(recommendationService.getGrowthTop10()).thenThrow(new RuntimeException("트랙 장애"));

        List<StockRef> refs = service.collectUnionRefs();

        assertThat(codesOf(refs)).containsExactly("005930", "035420");   // null/blank 제외, 트랙 예외 무시
    }

    @Test
    @DisplayName("warmUnionCatalysts — 수집한 union refs 로 classifyBatch 위임 + 저장수 반환")
    void warmUnionCatalysts_delegatesToClassifyBatch() {
        when(recommendationService.getValueTop10()).thenReturn(top5(dto("005930", "삼성전자"), dto("000660", "SK하이닉스")));
        when(stockCatalystService.classifyBatch(any())).thenReturn(2);

        int warmed = service.warmUnionCatalysts();

        assertThat(warmed).isEqualTo(2);
        verify(stockCatalystService).classifyBatch(refsCaptor.capture());
        assertThat(codesOf(refsCaptor.getValue())).containsExactly("005930", "000660");
    }

    @Test
    @DisplayName("warmUnionCatalysts — 대상 없음 → 0, classifyBatch 미호출")
    void warmUnionCatalysts_emptyNoBatch() {
        when(recommendationService.getValueTop10()).thenReturn(top5());

        int warmed = service.warmUnionCatalysts();

        assertThat(warmed).isZero();
        verify(stockCatalystService, never()).classifyBatch(any());
    }
}
