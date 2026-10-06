package com.myplatform.backend.service;

import com.myplatform.backend.dto.AiStrategySnapshotDto;
import com.myplatform.backend.service.RecommendationService.StockScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 종합추천 점수는 Gemini 테마에 따라 달라지지 않는다(2026-10-06, 사용자 결정 "1번").
 *
 * <p>재현: AI 전략 스냅샷 1~3위 종목은 Gemini 가 붙인 테마 개수로 섹터 축에 최대 +10(100점 환산 +12.5)을 받았다. 그런데 Gemini 무료
 * 한도는 하루 20회(운영 429 본문: {@code GenerateRequestsPerDayPerProjectPerModel-FreeTier · 한도 20})이고 한국 16시에 초기화돼,
 * 저녁 회차와 아침 재료 분류가 쓰고 나면 장중 회차엔 테마가 거의 안 붙는다(9/29~10/2 오전 회차 14~22개 중 2~5개, 10/6 오후
 * 12개 중 0개). 같은 종목·같은 시장이어도 한도가 남았는지에 따라 점수가 갈렸다 — 시장과 무관한 입력. 테마는 화면 표시(AI 전략 탭)에만
 * 남기고 점수에서는 뺀다. 10/1 의 "LLM 은 점수·순위를 바꾸지 않는다"(AI 스냅샷 순위에서 Gemini 점수 제거)와 같은 원칙이다.
 */
class RecommendationGeminiThemeTest {

    private static AiStrategySnapshotDto snap(String code, String themes, String changeRate) {
        return AiStrategySnapshotDto.builder()
                .stockCode(code).stockName("종목" + code)
                .aiThemes(themes)
                .changeRate(new BigDecimal(changeRate))
                .build();
    }

    private static StockScore stock(String code, String changeRate) {
        StockScore s = new StockScore(code, "종목" + code);
        s.changeRate = new BigDecimal(changeRate);
        return s;
    }

    private static Map<String, StockScore> scoreWith(List<AiStrategySnapshotDto> valueSnapshots) {
        RecommendationService service = mock(RecommendationService.class, CALLS_REAL_METHODS);
        SectorTradingService sector = mock(SectorTradingService.class);
        AiStrategySnapshotService ai = mock(AiStrategySnapshotService.class);
        when(sector.getSectorRotation()).thenReturn(List.of());   // 국면 UNKNOWN — 섹터 일괄 가산 없음
        when(ai.getAllLatestSnapshots()).thenReturn(AiStrategySnapshotDto.AllStrategiesResponse.builder()
                .strategies(Map.of("VALUE", valueSnapshots)).build());
        ReflectionTestUtils.setField(service, "sectorTradingService", sector);
        ReflectionTestUtils.setField(service, "aiStrategyService", ai);

        Map<String, StockScore> scoreMap = new LinkedHashMap<>();
        scoreMap.put("000001", stock("000001", "1.0"));   // AI 스냅샷에 있고 테마 3개
        scoreMap.put("000002", stock("000002", "1.0"));   // AI 스냅샷 밖 — 같은 등락률
        ReflectionTestUtils.invokeMethod(service, "scoreSectorMomentum", scoreMap);
        return scoreMap;
    }

    @Test
    @DisplayName("재현: Gemini 테마가 붙은 AI 시드도 섹터 점수는 같은 등락률의 다른 종목과 같다")
    void themesDoNotRaiseSectorScore() {
        Map<String, StockScore> scored = scoreWith(List.of(snap("000001", "반도체,HBM,AI", "1.0")));

        assertThat(scored.get("000001").sectorMomentum)
                .isEqualTo(scored.get("000002").sectorMomentum)
                .isEqualTo(2);   // 등락률 +1.0% 가산(0.5 초과)만 — 테마 3개로 +10 이던 것이 없다
    }

    @Test
    @DisplayName("Gemini 가 응답했든 못 했든(테마 없음) 점수가 같다 — 한도 상태가 추천을 바꾸지 않는다")
    void sameScoreWithOrWithoutGeminiResponse() {
        int withThemes = scoreWith(List.of(snap("000001", "반도체,HBM,AI", "1.0"))).get("000001").sectorMomentum;
        int withoutThemes = scoreWith(List.of(snap("000001", null, "1.0"))).get("000001").sectorMomentum;

        assertThat(withThemes).isEqualTo(withoutThemes);
    }

    @Test
    @DisplayName("스냅샷 등락률은 종전대로 쓴다 — 빠지는 것은 테마 가산뿐")
    void snapshotChangeRateStillUsed() {
        // 스냅샷 등락률 +3.5%(>3 → +4) 가 종목 자체 값(+1.0% → +2)보다 우선 — 종전 규칙(2026-07-28 이중가산 제거 뒤 1회만)
        Map<String, StockScore> scored = scoreWith(List.of(snap("000001", "반도체", "3.5")));

        assertThat(scored.get("000001").sectorMomentum).isEqualTo(4);
    }
}
