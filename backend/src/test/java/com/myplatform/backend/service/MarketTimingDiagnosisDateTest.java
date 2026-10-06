package com.myplatform.backend.service;

import com.myplatform.backend.dto.MarketTimingDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시장 진단 문장의 등락비 — 데이터가 가진 날짜로 말한다(2026-10-06 화면 점검).
 *
 * <p>재현: 장 시작 전(10/6 09:30) 시장 타이밍·오늘 탭·모닝브리핑이 쓰는 진단 문장이 "코스피 당일 등락비: 138.0"이라 했는데
 * 그 값은 직전 거래일(10/2) 장 마감 확정치였다 — 오늘 등락비는 16:30 에야 저장된다.
 */
class MarketTimingDiagnosisDateTest {

    private static MarketTimingDto.MarketStatusDto status(LocalDate date, String ratio) {
        MarketTimingDto.MarketStatusDto s = new MarketTimingDto.MarketStatusDto();
        s.setTradeDate(date);
        s.setDailyRatio(ratio == null ? null : new BigDecimal(ratio));
        return s;
    }

    @Test
    @DisplayName("재현: 직전 거래일 값을 '당일'이라 하지 않는다 — 그 값의 거래일을 붙인다")
    void ratioCarriesItsTradeDate() {
        String note = MarketTimingService.dailyRatioNote("코스피", status(LocalDate.of(2026, 10, 2), "138.0"));

        assertThat(note).contains("코스피 등락비(10/02): 138.0").doesNotContain("당일");
    }

    @Test
    @DisplayName("거래일을 모르면 날짜를 지어내지 않는다 · 등락비가 없으면 문장에서 뺀다")
    void unknownDateOrMissingRatio() {
        assertThat(MarketTimingService.dailyRatioNote("코스닥", status(null, "141.0")))
                .contains("코스닥 등락비(날짜 모름): 141.0");
        assertThat(MarketTimingService.dailyRatioNote("코스닥", status(LocalDate.of(2026, 10, 2), null))).isEmpty();
        assertThat(MarketTimingService.dailyRatioNote("코스닥", null)).isEmpty();
    }
}
