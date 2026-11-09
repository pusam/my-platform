package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.dto.JudgmentBoardDto;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.StockCatalystRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 종합판단 보드 머리줄의 간밤 미국장 — 오늘 탭과 같은 판정 근거를 싣는다(2026-11-09).
 * 재현(운영 11/9 17:05): 보드에 "🌙 간밤 미국장 약세 S&P500 +0.31% · 나스닥 +1.35% · SOX +4.21% · VIX 25.6(공포)" —
 * 지수가 전부 올랐는데 '약세'인 이유(VIX ≥ 25 규칙)가 없었다.
 */
class JudgmentBoardOvernightReasonTest {

    @SuppressWarnings("unchecked")
    private static JudgmentBoardService board(OvernightUsMarketService overnight) {
        return new JudgmentBoardService(mock(RecommendationService.class), mock(ChartPatternClient.class),
                mock(SectorStockConfig.class), overnight, mock(ObjectProvider.class),
                mock(StockCatalystRepository.class), mock(StockPriceService.class), mock(RvolService.class),
                mock(SignalOutcomeRepository.class), mock(InvestorBuyStreakService.class),
                mock(StockPriceHistoryRepository.class), mock(ObjectProvider.class));
    }

    private static Map<String, Object> view(String tilt, String reason, boolean available) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tilt", tilt);
        m.put("reason", reason);
        m.put("drivers", List.of("S&P500 +0.31%", "나스닥 +1.35%", "SOX +4.21%", "VIX 25.6(공포)"));
        m.put("dataAvailable", available);
        return m;
    }

    @Test
    @DisplayName("재현(11/9): 보드 머리줄도 판정 근거를 싣는다 — '약세'의 이유가 VIX 25 이상 규칙이라는 것")
    void boardCarriesReason() {
        OvernightUsMarketService overnight = mock(OvernightUsMarketService.class);
        when(overnight.getOvernightView()).thenReturn(view("BEAR", "VIX 25 이상(공포 경계) — 지수 등락과 별개", true));

        JudgmentBoardDto.Market market = board(overnight).buildMarket();

        assertThat(market.getOvernightTilt()).isEqualTo("BEAR");
        assertThat(market.getOvernightReason()).isEqualTo("VIX 25 이상(공포 경계) — 지수 등락과 별개");
    }

    @Test
    @DisplayName("근거가 없으면(보합권 중립) 비우고, 시세를 못 받았으면 판정·근거 둘 다 싣지 않는다")
    void noReasonOrNoData() {
        OvernightUsMarketService neutral = mock(OvernightUsMarketService.class);
        when(neutral.getOvernightView()).thenReturn(view("NEUTRAL", null, true));
        assertThat(board(neutral).buildMarket().getOvernightReason()).isNull();

        OvernightUsMarketService down = mock(OvernightUsMarketService.class);
        when(down.getOvernightView()).thenReturn(view("BEAR", "VIX 25 이상(공포 경계) — 지수 등락과 별개", false));
        JudgmentBoardDto.Market m = board(down).buildMarket();
        assertThat(m.getOvernightTilt()).isNull();
        assertThat(m.getOvernightReason()).isNull();
    }
}
