package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.dto.JudgmentBoardDto;
import com.myplatform.backend.service.RecommendationService.RecommendationDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 종합판단 보드 union 의 "—" 설명(2026-10-07).
 *
 * <p>재현: 점수표({@link RecommendationService#categoryScoreSnapshot()})는 종합추천 계산이 남기는 메모리 값이라 서버를 다시 띄우면
 * 다음 계산(장중 첫 조회 또는 평일 11:30)까지 비어 있다. 그동안 발굴 트랙 종목이 전부 "—" 인데 보드는 '순수 발굴주(momentum 신호
 * 없음)'라고 설명했다 — 저녁에 재시작하면 다음 날 오전까지 틀린 설명이 붙었다(2026-10-04 점검의 남은 LOW). 그때는 모른다고 말한다.
 */
class JudgmentBoardUnionSnapshotTest {

    private static RecommendationService.Top5Response resp(List<RecommendationDto> items) {
        return new RecommendationService.Top5Response(items, "15:00 기준", true, Map.of());
    }

    private static JudgmentBoardDto unionBoard(boolean scoreSnapshotReady) {
        RecommendationService rec = mock(RecommendationService.class);
        when(rec.getTop5()).thenReturn(resp(List.of()));
        when(rec.getValueTop10()).thenReturn(resp(List.of(
                RecommendationDto.builder().stockCode("000001").stockName("가치주").build())));
        when(rec.getGrowthTop10()).thenReturn(resp(List.of()));
        when(rec.getOversoldTop10()).thenReturn(resp(List.of()));
        when(rec.getEarningsTop10()).thenReturn(resp(List.of()));
        when(rec.getSmartMoneyTop10()).thenReturn(resp(List.of()));
        when(rec.categoryScoreSnapshot()).thenReturn(Map.of());
        when(rec.hasCategoryScoreSnapshot()).thenReturn(scoreSnapshotReady);

        ChartPatternClient chart = mock(ChartPatternClient.class);
        when(chart.getTimingSignals(any())).thenReturn(new ChartPatternClient.TimingFetch(false, List.of()));
        SectorStockConfig sectors = mock(SectorStockConfig.class);
        when(sectors.getAllSectors()).thenReturn(List.of());

        // 나머지 의존(재료·시세·이력·RVOL·채널·국면)은 전부 best-effort 라 비워 둬도 보드는 나온다
        JudgmentBoardService board = mock(JudgmentBoardService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(board, "recommendationService", rec);
        ReflectionTestUtils.setField(board, "chartPatternClient", chart);
        ReflectionTestUtils.setField(board, "sectorStockConfig", sectors);
        return board.getBoard("union");
    }

    @Test
    @DisplayName("재현: 점수표가 아직 없으면(재시작 뒤 첫 계산 전) '순수 발굴주'라고 하지 않고 계산 전이라고 말한다")
    void missingScoreSnapshotIsNotCalledPureDiscovery() {
        JudgmentBoardDto dto = unionBoard(false);

        assertThat(dto.getUnionStats().getUnscoredRows()).isEqualTo(1);
        assertThat(dto.getUnionStats().isScoreSnapshotReady()).isFalse();
        assertThat(dto.getNote()).contains("계산 전").doesNotContain("순수 발굴주");
    }

    @Test
    @DisplayName("점수표가 있으면 종전 설명 — 그 표에 없는 종목은 순수 발굴주다")
    void readySnapshotKeepsPureDiscoveryNote() {
        JudgmentBoardDto dto = unionBoard(true);

        assertThat(dto.getUnionStats().isScoreSnapshotReady()).isTrue();
        assertThat(dto.getNote()).contains("순수 발굴주").doesNotContain("계산 전");
    }

    @Test
    @DisplayName("계산을 했지만 후보가 0 인 빈 표는 '있다' — 없음(null)과 구분한다")
    void emptyButComputedSnapshotIsReady() {
        RecommendationService rec = mock(RecommendationService.class, CALLS_REAL_METHODS);
        assertThat(rec.hasCategoryScoreSnapshot()).isFalse();          // 재시작 직후
        ReflectionTestUtils.setField(rec, "cachedScoreMap", Map.of());
        assertThat(rec.hasCategoryScoreSnapshot()).isTrue();
    }
}
