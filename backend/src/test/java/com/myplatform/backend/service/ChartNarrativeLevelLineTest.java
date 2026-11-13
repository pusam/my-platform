package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.ChartNarrativeDto;
import com.myplatform.backend.dto.SupportResistanceDto;
import com.myplatform.backend.repository.StockPriceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 차트 해설의 '자주 닿은 가격대' 한 줄(2026-11-13) — 지지/저항 카드와 같은 봉·같은 레벨을 쓴다.
 *
 * <p>표시 전용이다 — 판정(관망·조건부 관심)은 이 줄과 무관하다({@code ChartNarrativeBuilderTest}).
 */
class ChartNarrativeLevelLineTest {

    private static final DateTimeFormatter D = DateTimeFormatter.BASIC_ISO_DATE;
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private static final int BARS = 40;

    private static String row(LocalDate date, long high, long low, long close) {
        return "{\"stck_bsop_date\":\"" + date.format(D) + "\",\"stck_oprc\":\"" + close + "\",\"stck_hgpr\":\"" + high
                + "\",\"stck_lwpr\":\"" + low + "\",\"stck_clpr\":\"" + close + "\",\"acml_vol\":\"1000\"}";
    }

    /** 40봉(과거 → 오늘). 평소 종가 100(고가 101·저가 99), 지정한 봉만 고가를 바꾸고 마지막 봉 종가는 lastClose. */
    private static ChartPatternService serviceWith(long lastClose, int[] spikeIndex, long[] spikeHigh) throws Exception {
        List<String> oldestFirst = new ArrayList<>();
        for (int i = 0; i < BARS; i++) {
            long high = 101, low = 99, close = 100;
            for (int k = 0; k < spikeIndex.length; k++) if (spikeIndex[k] == i) high = spikeHigh[k];
            if (i == BARS - 1) { close = lastClose; high = lastClose + 1; low = lastClose - 1; }
            oldestFirst.add(row(TODAY.minusDays(BARS - 1 - i), high, low, close));
        }
        List<String> latestFirst = new ArrayList<>(oldestFirst);
        java.util.Collections.reverse(latestFirst);   // KIS 응답은 최신이 앞이다

        KoreaInvestmentService kis = mock(KoreaInvestmentService.class);
        when(kis.getDailyPrices(anyString(), anyInt()))
                .thenReturn(new ObjectMapper().readTree("{\"output2\":[" + String.join(",", latestFirst) + "]}"));
        ChartPatternService service = new ChartPatternService(kis, mock(StockPriceRepository.class), mock(StockMasterService.class));
        ReflectionTestUtils.setField(service, "self", service);
        return service;
    }

    private static String text(ChartNarrativeDto dto) {
        return String.join(" ", dto.getSections().stream().flatMap(s -> s.getLines().stream()).toList());
    }

    @Test
    @DisplayName("두 번 닿은 저항 바로 아래면 '지금 위치'에 그 가격대를 한 줄로 말한다 — 카드의 레벨과 같은 값")
    void nearTwiceTouchedResistanceIsSaid() throws Exception {
        // 고가 110·111 두 번(±2% 안이라 한 가격대 110.5 → 표시 111원), 오늘 종가 108 → +2.3%
        ChartPatternService service = serviceWith(108, new int[]{10, 22}, new long[]{110, 111});

        ChartNarrativeDto dto = service.computeNarrative("005380");
        SupportResistanceDto card = service.detectSupportResistance("005380");

        assertThat(card.getResistance()).hasSize(1);
        assertThat(card.getResistance().get(0).getTouches()).isEqualTo(2);
        assertThat(dto.getSections().get(0).getTitle()).isEqualTo("지금 위치");
        assertThat(text(dto)).contains("저항 111원 부근(2회 닿고 돌아선 자리)의 2.3% 아래에 있습니다(오늘 시세 기준).");
    }

    @Test
    @DisplayName("한 번만 닿은 고점은 가격대로 말하지 않는다")
    void onceTouchedHighIsNotSaid() throws Exception {
        ChartPatternService service = serviceWith(108, new int[]{10}, new long[]{110});

        assertThat(service.detectSupportResistance("005380").getResistance()).hasSize(1);
        assertThat(text(service.computeNarrative("005380"))).doesNotContain("닿고 돌아선").doesNotContain("사이에 있습니다");
    }

    @Test
    @DisplayName("여러 번 닿았어도 먼 가격대(3% 밖)는 말하지 않는다")
    void farLevelIsNotSaid() throws Exception {
        ChartPatternService service = serviceWith(102, new int[]{10, 22}, new long[]{110, 111});

        assertThat(service.detectSupportResistance("005380").getResistance().get(0).getTouches()).isEqualTo(2);
        assertThat(text(service.computeNarrative("005380"))).doesNotContain("닿고 돌아선");
    }
}
