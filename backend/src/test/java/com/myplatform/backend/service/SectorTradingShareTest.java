package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.dto.SectorTradingDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 섹터 거래대금 '전체 대비' — 여러 섹터에 속한 종목을 두 번 세지 않는다(2026-10-02 화면 점검).
 *
 * <p>재현: 시장 탭 거래대금이 "통신 8.42조 · 전체 대비 35.7% / 총 23.58조"였다. 통신 섹터에 삼성전자·SK하이닉스가 들어 있어
 * 반도체(8.86조)와 같은 거래대금이 통신에 한 번 더 잡혔고, 분모도 섹터 합계의 총합이라 중복이 그대로 더해졌다.
 */
class SectorTradingShareTest {

    private static SectorTradingDto sector(String name, String total) {
        SectorTradingDto d = new SectorTradingDto();
        d.setSectorName(name);
        d.setTotalTradingValue(new BigDecimal(total));
        return d;
    }

    @Test
    @DisplayName("분모는 종목 단위로 한 번만 센 전체 — 겹치는 테마 때문에 비율 합은 100% 를 넘을 수 있다")
    void shareUsesDeduplicatedMarketTotal() {
        // 반도체 = A(60)+B(40), 통신 = A(60)+C(10) → 섹터 합계 총합 170, 종목 단위 전체 110
        SectorTradingDto semi = sector("반도체", "100");
        SectorTradingDto telecom = sector("통신", "70");
        Map<String, BigDecimal> unique = new LinkedHashMap<>();
        unique.put("A", new BigDecimal("60"));
        unique.put("B", new BigDecimal("40"));
        unique.put("C", new BigDecimal("10"));

        SectorTradingService.applyMarketShare(List.of(semi, telecom), unique);

        assertThat(semi.getMarketTotalTradingValue()).isEqualByComparingTo("110");
        assertThat(semi.getPercentage()).isEqualByComparingTo("90.91");   // 예전 분모(170)면 58.82
        assertThat(telecom.getPercentage()).isEqualByComparingTo("63.64");
    }

    @Test
    @DisplayName("거래대금이 하나도 없으면 비율은 null — 0% 로 위장하지 않는다")
    void noTradingValueGivesNullShare() {
        SectorTradingDto s = sector("반도체", "0");

        SectorTradingService.applyMarketShare(List.of(s), Map.of());

        assertThat(s.getPercentage()).isNull();
        assertThat(s.getMarketTotalTradingValue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("통신 섹터에 반도체·전자 대형주가 없다 — 삼성전자·SK하이닉스는 반도체에만")
    void telecomHasNoSemiconductorGiants() {
        SectorStockConfig config = new SectorStockConfig();   // 생성자에서 섹터 목록을 채운다

        assertThat(config.getSector("TELECOM").getStockCodes())
                .contains("017670", "030200", "032640")
                .doesNotContain("005930", "000660", "009150", "066570", "377300");
        assertThat(config.getSector("SEMICONDUCTOR").getStockCodes()).contains("005930", "000660");
    }
}
