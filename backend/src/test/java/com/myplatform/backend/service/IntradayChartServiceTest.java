package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.service.IntradayChartService.IntradayChartDto;
import com.myplatform.backend.service.IntradayChartService.MinuteBar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 당일 분봉('1일' 차트) — KIS 응답 파싱·5분봉 합성·DTO 변환 순수 함수.
 * 페이지네이션 수집(fetchDayMinuteBars)은 KIS mock 필요라 순수부만 고정(§4c 빈 응답 극성 포함).
 */
class IntradayChartServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode kisResponse(String rtCd, String... hourCloseVolTriples) throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < hourCloseVolTriples.length; i += 3) {
            if (i > 0) rows.append(',');
            String hour = hourCloseVolTriples[i];
            String close = hourCloseVolTriples[i + 1];
            String vol = hourCloseVolTriples[i + 2];
            rows.append(String.format(
                    "{\"stck_cntg_hour\":\"%s\",\"stck_oprc\":\"%s\",\"stck_hgpr\":\"%s\"," +
                    "\"stck_lwpr\":\"%s\",\"stck_prpr\":\"%s\",\"cntg_vol\":\"%s\"}",
                    hour, close, close, close, close, vol));
        }
        return MAPPER.readTree("{\"rt_cd\":\"" + rtCd + "\",\"output2\":[" + rows + "]}");
    }

    @Test
    @DisplayName("parseMinuteBars — 정상 행 파싱 + rt_cd≠0/결측/0가격 행은 건너뜀(§4c)")
    void parseMinuteBars_basics() throws Exception {
        JsonNode ok = kisResponse("0",
                "093000", "70100", "1000",
                "092900", "70000", "500",
                "092800", "0", "100");         // 종가 0 → skip
        List<MinuteBar> bars = IntradayChartService.parseMinuteBars(ok);
        assertThat(bars).hasSize(2);
        assertThat(bars.get(0).time()).isEqualTo(LocalTime.of(9, 30));
        assertThat(bars.get(0).close()).isEqualByComparingTo("70100");
        assertThat(bars.get(0).volume()).isEqualByComparingTo("1000");

        assertThat(IntradayChartService.parseMinuteBars(kisResponse("1"))).isEmpty();   // API 에러
        assertThat(IntradayChartService.parseMinuteBars(null)).isEmpty();
    }

    @Test
    @DisplayName("parseMinuteBars — 시가/고저 미제공이어도 종가로 폴백(전 봉 탈락 '수집 실패' 오탐 방지)")
    void parseMinuteBars_openOptionalFallback() throws Exception {
        // stck_oprc/hgpr/lwpr 없이 종가(stck_prpr)·시각만 있는 행 — KIS 분봉이 시가 미제공인 케이스
        JsonNode resp = MAPPER.readTree(
                "{\"rt_cd\":\"0\",\"output2\":[" +
                "{\"stck_cntg_hour\":\"093000\",\"stck_prpr\":\"70100\",\"cntg_vol\":\"500\"}]}");
        List<MinuteBar> bars = IntradayChartService.parseMinuteBars(resp);
        assertThat(bars).hasSize(1);   // 폴백으로 살아남음(이전엔 시가 null 로 전량 탈락)
        assertThat(bars.get(0).open()).isEqualByComparingTo("70100");   // 종가로 폴백
        assertThat(bars.get(0).high()).isEqualByComparingTo("70100");
        assertThat(bars.get(0).low()).isEqualByComparingTo("70100");
    }

    @Test
    @DisplayName("aggregateToBuckets — 5분 버킷: open=첫봉/close=마지막봉/고저=극값/거래량=합, 버킷 라벨=구간 시작")
    void aggregate_ohlcvSemantics() {
        List<MinuteBar> oneMin = new ArrayList<>();
        // 09:00~09:04 (버킷 09:00): 종가 100→104 상승, 고가 105(09:02), 저가 98(09:01)
        double[][] rows = {
                {0, 100, 100, 100, 100, 10},
                {1, 100, 101, 98, 101, 10},
                {2, 101, 105, 101, 102, 10},
                {3, 102, 103, 101, 103, 10},
                {4, 103, 104, 102, 104, 10},
                {5, 104, 106, 104, 106, 20},   // 다음 버킷(09:05)
        };
        for (double[] r : rows) {
            oneMin.add(new MinuteBar(LocalTime.of(9, (int) r[0]),
                    BigDecimal.valueOf(r[1]), BigDecimal.valueOf(r[2]),
                    BigDecimal.valueOf(r[3]), BigDecimal.valueOf(r[4]), BigDecimal.valueOf(r[5])));
        }

        List<MinuteBar> agg = IntradayChartService.aggregateToBuckets(oneMin, 5);

        assertThat(agg).hasSize(2);
        MinuteBar b1 = agg.get(0);
        assertThat(b1.time()).isEqualTo(LocalTime.of(9, 0));
        assertThat(b1.open()).isEqualByComparingTo("100");
        assertThat(b1.close()).isEqualByComparingTo("104");
        assertThat(b1.high()).isEqualByComparingTo("105");
        assertThat(b1.low()).isEqualByComparingTo("98");
        assertThat(b1.volume()).isEqualByComparingTo("50");
        assertThat(agg.get(1).time()).isEqualTo(LocalTime.of(9, 5));
    }

    @Test
    @DisplayName("toDto — candles 최신→과거(ChartData 규약), date=HH:mm, 빈 입력=dataAvailable=false(§4c)")
    void toDto_ordering() {
        List<MinuteBar> ascending = List.of(
                new MinuteBar(LocalTime.of(9, 0), BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.valueOf(2), BigDecimal.TEN),
                new MinuteBar(LocalTime.of(9, 5), BigDecimal.valueOf(2), BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE));

        IntradayChartDto dto = IntradayChartService.toDto(ascending);

        assertThat(dto.dataAvailable()).isTrue();
        assertThat(dto.candles()).hasSize(2);
        assertThat(dto.candles().get(0).date()).isEqualTo("09:05");   // 최신 먼저
        assertThat(dto.candles().get(1).date()).isEqualTo("09:00");
        assertThat(dto.volumes()).hasSize(2);

        IntradayChartDto empty = IntradayChartService.toDto(List.of());
        assertThat(empty.dataAvailable()).isFalse();
        assertThat(empty.candles()).isEmpty();
    }
}
