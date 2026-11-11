package com.myplatform.backend.controller;

import com.myplatform.backend.service.JudgmentBoardService;
import com.myplatform.backend.service.RecommendationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** /recommendation/top5 응답 — 거래대금 하한 키 세 개(2026-11-11). 모르는 값은 null 로 싣는다(Map.of 는 null 을 못 담는다). */
class RecommendationTop5LiquidityKeysTest {

    @Test
    @DisplayName("하한·뺀 수·후보 수를 싣고, 모르면 null 로 싣는다")
    @SuppressWarnings("unchecked")
    void carriesLiquidityKeys() {
        RecommendationService service = mock(RecommendationService.class);
        RecommendationService.Top5Response r = new RecommendationService.Top5Response(List.of(), "11:30 기준", true, Map.of());
        r.applyLiquidity(5.0, null);   // DB 스냅샷 — 뺀 수·후보 수 모름
        when(service.getTop5()).thenReturn(r);

        Map<String, Object> body = (Map<String, Object>) new RecommendationController(service, mock(JudgmentBoardService.class))
                .getTop5().getBody();

        assertThat(body).containsEntry("minAvgTradedValueEok", 5.0)
                .containsKey("belowLiquidityFloorCount").containsKey("candidateCount");
        assertThat(body.get("belowLiquidityFloorCount")).isNull();
        assertThat(body.get("candidateCount")).isNull();
        assertThat(body).containsKeys("success", "dataAvailable", "data", "dataTime", "realtime", "delta");
    }
}
