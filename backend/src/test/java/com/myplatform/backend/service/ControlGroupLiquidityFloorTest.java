package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockPriceHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대조군도 추천 후보와 같은 거래대금 하한을 통과한 유니버스에서 뽑는다(2026-11-11) — 시그널과 대조군은 같은 유니버스여야 비교가 된다.
 */
class ControlGroupLiquidityFloorTest {

    private static final LocalDate LAST = LocalDate.of(2026, 11, 10);

    @Test
    @DisplayName("미달 종목은 대조군 유니버스에 없다 — 판정 불가(봉 9개·봉 없음)도 뽑지 않는다")
    void belowAndUnknownAreLeftOut() {
        Map<String, List<StockPriceHistory>> bars = Map.of(
                "THIN", LiquidityFloorTest.bars(20, 0.23),
                "OK", LiquidityFloorTest.bars(20, 19.7),
                "EDGE", LiquidityFloorTest.bars(20, 5),
                "FEW", LiquidityFloorTest.bars(9, 100));
        List<String> universe = ControlGroupService.liquidUniverse(
                List.of("THIN", "OK", "EDGE", "FEW", "NONE"), bars, LAST, 5);
        assertThat(universe).containsExactly("OK", "EDGE");

        for (int attempt = 0; attempt < 50; attempt++) {
            String pick = ControlGroupService.pickControlStock(universe, "SIG",
                    ControlGroupService.seedFor(LAST, "SIG", attempt));
            assertThat(pick).isIn("OK", "EDGE");
        }
    }

    @Test
    @DisplayName("하한을 끄면 유니버스는 그대로")
    void offKeepsUniverse() {
        List<String> codes = List.of("THIN", "NONE");
        assertThat(ControlGroupService.liquidUniverse(codes, Map.of(), LAST, 0)).isEqualTo(codes);
    }
}
