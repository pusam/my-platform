package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 'AI TOP PICK' 텔레그램 — 같은 날 같은 종목·유형은 한 번, 등락률을 모르면 0% 로 쓰지 않는다(2026-10-07).
 *
 * <p>재현: AI 분석은 9·12·15시 크론 말고도 2분 워머가 결과가 1시간 지나면 다시 계산하고(10/7 운영 07:53·08:54·10:00·11:00),
 * 분석이 끝날 때마다 90점 이상 종목에 알림을 보낸다 — 같은 종목이 장중 매시간 다시 나갈 수 있다. 등락률을 모르면 '(0.00%)'(보합)
 * 로 썼다(§4c).
 */
class AiTopPickAlertDedupTest {

    @Test
    @DisplayName("재현: 같은 날 같은 종목·유형은 한 번만 — 다른 유형·다음 날은 다시")
    void oncePerStockTypeDay() {
        Map<String, LocalDate> alerted = new ConcurrentHashMap<>();
        LocalDate d = LocalDate.of(2026, 10, 7);

        assertThat(AiStockAnalysisService.shouldAlert(alerted, "short:005930", d)).isTrue();
        assertThat(AiStockAnalysisService.shouldAlert(alerted, "short:005930", d)).isFalse();   // 1시간 뒤 재분석
        assertThat(AiStockAnalysisService.shouldAlert(alerted, "long:005930", d)).isTrue();     // 다른 유형
        assertThat(AiStockAnalysisService.shouldAlert(alerted, "short:005930", d.plusDays(1))).isTrue();
        // 지난 날 기록은 남기지 않는다(맵이 날마다 자라지 않게)
        assertThat(alerted).containsOnlyKeys("short:005930");
    }

    @Test
    @DisplayName("재현: 등락률을 모르면 '(0.00%)' 가 아니라 현재가만")
    void unknownChangeRateIsOmitted() {
        assertThat(AiStockAnalysisService.priceLine(new BigDecimal("50000"), null))
                .isEqualTo("💰 현재가: <b>50,000원</b>");
        assertThat(AiStockAnalysisService.priceLine(new BigDecimal("50000"), new BigDecimal("1.234")))
                .isEqualTo("💰 현재가: <b>50,000원</b> (+1.23%)");
        assertThat(AiStockAnalysisService.priceLine(new BigDecimal("50000"), new BigDecimal("-0.5")))
                .isEqualTo("💰 현재가: <b>50,000원</b> (-0.50%)");
        assertThat(AiStockAnalysisService.priceLine(null, null)).isEqualTo("💰 현재가: <b>N/A</b>");
    }
}
