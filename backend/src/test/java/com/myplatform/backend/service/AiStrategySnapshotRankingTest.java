package com.myplatform.backend.service;

import com.myplatform.backend.entity.AiStrategySnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 전략 스냅샷 — 점수·순위는 알고리즘, LLM 은 코멘트·테마만({@link AiStrategySnapshotService#rankForSnapshot}, 2026-10-01 감사).
 *
 * <p>운영 실측(9/30 SWING): 11:16 저장은 알고리즘 1~5위였는데 13:00 에는 "Gemini 코멘트 보강(3종목)" 직후
 * "saved 5 (코멘트: 없음)" 으로 4~8위가 저장됐다. 상위 3개에만 "알고리즘 95% + Gemini 5%" 를 덮어써서,
 * 100점 동점이 많은 날 Gemini 가 90 미만을 주면 1~3위가 99 이하로 내려가 잘렸다. 9/1 이후 SWING 33회 중 8회.
 * 코드 주석은 "점수 100% 알고리즘, Gemini 는 코멘트만" 이었다 — 명세와 구현을 맞춘다.
 */
class AiStrategySnapshotRankingTest {

    private static AiStrategySnapshot candidate(String code, int score) {
        return AiStrategySnapshot.builder().stockCode(code).stockName(code).score(score).build();
    }

    private static GeminiService.AiScoreResult ai(String code, int aiScore, String comment) {
        return new GeminiService.AiScoreResult(code, aiScore, comment, List.of("테마"));
    }

    @Test
    @DisplayName("100점 동점 8개 중 상위 3개에 Gemini 가 60점을 줘도 저장 5개는 알고리즘 1~5위 그대로")
    void llmScoreNeverDropsAlgorithmTopPicks() {
        List<AiStrategySnapshot> candidates = new ArrayList<>();
        for (int i = 1; i <= 8; i++) candidates.add(candidate("S" + i, 100));

        List<AiStrategySnapshot> saved = AiStrategySnapshotService.rankForSnapshot(candidates, Map.of(
                "S1", ai("S1", 60, "코멘트1"), "S2", ai("S2", 60, "코멘트2"), "S3", ai("S3", 60, "코멘트3")), 5);

        assertThat(saved).extracting(AiStrategySnapshot::getStockCode).containsExactly("S1", "S2", "S3", "S4", "S5");
        assertThat(saved).extracting(AiStrategySnapshot::getScore).containsOnly(100);
        assertThat(saved).extracting(AiStrategySnapshot::getRankNum).containsExactly(1, 2, 3, 4, 5);
        // LLM 이 준 건 코멘트·테마뿐이고 맞는 종목에 붙는다
        assertThat(saved.get(0).getAiComment()).isEqualTo("코멘트1");
        assertThat(saved.get(0).getAiThemes()).isEqualTo("테마");
        assertThat(saved.get(3).getAiComment()).isNull();
    }

    @Test
    @DisplayName("LLM 점수가 높아도 낮아도 저장 점수는 알고리즘 점수 그대로 — 순서도 알고리즘 순")
    void llmScoreNeverChangesTheStoredScore() {
        List<AiStrategySnapshot> candidates = new ArrayList<>(List.of(
                candidate("A", 92), candidate("B", 90), candidate("C", 70)));

        List<AiStrategySnapshot> saved = AiStrategySnapshotService.rankForSnapshot(candidates, Map.of(
                "A", ai("A", 0, "낮게"), "C", ai("C", 100, "높게")), 5);

        assertThat(saved).extracting(AiStrategySnapshot::getStockCode).containsExactly("A", "B", "C");
        assertThat(saved).extracting(AiStrategySnapshot::getScore).containsExactly(92, 90, 70);
    }

    @Test
    @DisplayName("LLM 결과가 없어도(실패·빈 응답) 같은 목록 — 코멘트만 없다")
    void noLlmResultSameList() {
        List<AiStrategySnapshot> candidates = new ArrayList<>(List.of(
                candidate("A", 80), candidate("B", 85), candidate("C", 60)));

        List<AiStrategySnapshot> saved = AiStrategySnapshotService.rankForSnapshot(candidates, Map.of(), 2);

        assertThat(saved).extracting(AiStrategySnapshot::getStockCode).containsExactly("B", "A");
        assertThat(saved).extracting(AiStrategySnapshot::getAiComment).containsOnlyNulls();
    }
}
