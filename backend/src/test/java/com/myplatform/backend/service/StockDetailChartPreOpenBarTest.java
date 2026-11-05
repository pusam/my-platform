package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.StockDetailDto.ChartData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 장이 열리기 전 KIS 일봉 맨 앞의 오늘 자리 봉(2026-11-06 운영 실측).
 *
 * <p>재현: 08:17 현대차 일봉 맨 앞이 20261106 · 시·고·저·종 전부 전일 종가 293,500 · 거래량 0 이었다. 없는 봉이 종가로 계산에 들어가
 * 20일선이 299,125(확정 봉 기준 300,675)가 됐고, 그 값이 AI 프롬프트로 갔다.
 */
class StockDetailChartPreOpenBarTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final DateTimeFormatter D = DateTimeFormatter.BASIC_ISO_DATE;
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    private static JsonNode bar(LocalDate date, long close, long volume) throws Exception {
        return M.readTree("{\"stck_bsop_date\":\"" + date.format(D) + "\",\"stck_oprc\":\"" + close + "\",\"stck_hgpr\":\"" + close
                + "\",\"stck_lwpr\":\"" + close + "\",\"stck_clpr\":\"" + close + "\",\"acml_vol\":\"" + volume + "\"}");
    }

    /** 지난 25일 봉 — 맨 앞(어제)만 200, 나머지 100. 최신이 앞이다. */
    private static List<JsonNode> settled() throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        for (int i = 1; i <= 25; i++) rows.add(bar(TODAY.minusDays(i), i == 1 ? 200 : 100, 1000));
        return rows;
    }

    private static ChartData chart(List<JsonNode> rows) {
        KoreaInvestmentService kis = mock(KoreaInvestmentService.class);
        when(kis.getDailyPriceRowsPaged(anyString(), anyInt(), any())).thenReturn(rows);
        return new StockDetailCacheService(kis, mock(VwapService.class), mock(RedisCacheService.class)).getCachedChartData("005380");
    }

    @Test
    @DisplayName("재현: 오늘 날짜·거래량 0 인 봉은 없는 봉이다 — 차트·이평·볼린저에 넣지 않는다")
    void preOpenBarIsNotABar() throws Exception {
        List<JsonNode> rows = new ArrayList<>(settled());
        rows.add(0, bar(TODAY, 200, 0));   // 전일 종가로 채운 자리 봉

        ChartData c = chart(rows);

        assertThat(c.getCandles().get(0).getDate()).isEqualTo(TODAY.minusDays(1).format(D));
        assertThat(c.getVolumes().get(0).getVolume()).isEqualTo(1000L);
        assertThat(c.getMa20()).as("확정 20봉 = 200 + 100×19").isEqualByComparingTo("105");
        assertThat(c.getMaLine20().get(0)).isEqualByComparingTo("105");
    }

    @Test
    @DisplayName("오늘 거래가 있으면 봉이다 — 장중 형성 봉은 그대로 쓴다")
    void tradedTodayStays() throws Exception {
        List<JsonNode> rows = new ArrayList<>(settled());
        rows.add(0, bar(TODAY, 150, 500));

        ChartData c = chart(rows);

        assertThat(c.getCandles().get(0).getDate()).isEqualTo(TODAY.format(D));
    }

    @Test
    @DisplayName("지난 날의 거래량 0 봉은 정지 기록이다 — 지우지 않는다")
    void pastZeroVolumeStays() throws Exception {
        List<JsonNode> rows = new ArrayList<>(settled());
        rows.set(3, bar(TODAY.minusDays(4), 100, 0));

        ChartData c = chart(rows);

        assertThat(c.getCandles()).hasSize(25);
    }
}
