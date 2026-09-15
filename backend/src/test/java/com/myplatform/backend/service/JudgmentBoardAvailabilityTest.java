package com.myplatform.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class JudgmentBoardAvailabilityTest {
    @Test
    void missingMomentumIsNotAnEmptySuccessfulBoard() {
        RecommendationService recommendations = mock(RecommendationService.class);
        when(recommendations.getTop5()).thenReturn(RecommendationService.Top5Response.unavailable());
        JudgmentBoardService board = board(recommendations);
        assertThatThrownBy(() -> board.getBoard("momentum"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("추천 데이터");
    }

    @Test
    void failedDiscoveryTrackIsNotSilentlyOmitted() {
        RecommendationService recommendations = mock(RecommendationService.class);
        when(recommendations.getTop5()).thenReturn(
                new RecommendationService.Top5Response(List.of(), "15:00 기준", true, Map.of()));
        when(recommendations.getValueTop10()).thenThrow(new IllegalStateException("DB unavailable"));
        JudgmentBoardService board = board(recommendations);
        assertThatThrownBy(() -> board.getBoard("union"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("발굴 트랙");
    }

    private JudgmentBoardService board(RecommendationService recommendations) {
        JudgmentBoardService board = mock(JudgmentBoardService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(board, "recommendationService", recommendations);
        return board;
    }
}
