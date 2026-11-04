package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockDetailDto;
import com.myplatform.backend.dto.StockDetailDto.CandlePoint;
import com.myplatform.backend.dto.StockDetailDto.ChartData;
import com.myplatform.backend.dto.StockDetailDto.ChartSignal;
import com.myplatform.backend.dto.StockDetailDto.PriceInfo;
import com.myplatform.backend.dto.StockDetailDto.VolumePoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 차트 시그널은 최신 봉으로 판정한다 — ChartData 의 목록은 <b>최신→과거</b> 순이다(2026-11-04).
 *
 * <p>재현: 코드가 '마지막 원소 = 최근'으로 가정해 200봉 전 값과 비교했다. 11/4 09:05 삼성전자 실제 응답 — 현재가 227,500,
 * bbUpper[0]=260,071(오늘) · bbUpper[199]=145,029(1월 9일). 밴드 한가운데(%B 0.47)인데 1월 상단과 비교해
 * '볼린저 상단 터치 — 단기 과열'이 떴고, 같은 값이 Gemini 프롬프트에도 들어가 AI 가 "단기 과열 신호"라고 썼다.
 * 올해 오른 종목은 전부 상단 터치, 내린 종목은 전부 하단 터치로 보인다. 골든·데드크로스와 거래량 비교도 같은 방향으로 틀렸다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChartSignalServiceTest {

    @Mock private TechnicalIndicatorService technicalIndicatorService;
    @Mock private MarketCalendarService marketCalendar;

    @InjectMocks private ChartSignalService service;

    @BeforeEach
    void marketIsOpen() {
        when(marketCalendar.isMarketClosed(any(LocalDate.class))).thenReturn(false);
        when(marketCalendar.lastClosedTradingDay(any()))
                .thenAnswer(inv -> ((LocalDateTime) inv.getArgument(0)).toLocalDate().minusDays(1));
    }

    private void afterTheClose() {
        doAnswer(inv -> ((LocalDateTime) inv.getArgument(0)).toLocalDate())
                .when(marketCalendar).lastClosedTradingDay(any());
    }

    private static List<BigDecimal> list(String... values) {
        List<BigDecimal> out = new ArrayList<>();
        for (String v : values) out.add(new BigDecimal(v));
        return out;
    }

    /** 최신→과거 순 캔들 — fetchChartData 와 같은 순서. */
    private static List<CandlePoint> candlesNewestFirst() {
        List<CandlePoint> out = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            out.add(CandlePoint.builder().date("202611" + String.format("%02d", 28 - i))
                    .close(new BigDecimal(227_000 - i * 100)).build());
        }
        return out;
    }

    private static PriceInfo price(long current, Long volume) {
        return PriceInfo.builder().currentPrice(BigDecimal.valueOf(current)).tradingVolume(volume).build();
    }

    private List<String> codes(PriceInfo price, ChartData chart) {
        StockDetailDto dto = StockDetailDto.builder().price(price).chartData(chart).build();
        return service.detect(dto).stream().map(ChartSignal::getCode).toList();
    }

    private static ChartData.ChartDataBuilder chart() {
        return ChartData.builder().candles(candlesNewestFirst());
    }

    private static ChartData.ChartDataBuilder bands() {
        // 오늘 260,071~196,178 · 1월 145,029~91,710 (11/4 삼성전자 실측)
        return chart().bbUpper(list("260071.64", "258000", "150000", "145029.26"))
                .bbLower(list("196178.36", "195000", "95000", "91710.74"));
    }

    @Test
    @DisplayName("재현: 밴드 한가운데면 상단·하단 터치가 아니다 — 예전엔 1월 상단과 비교해 '상단 터치'")
    void insideTodaysBandIsNoTouch() {
        assertThat(codes(price(227_500, null), bands().build()))
                .doesNotContain("BB_UPPER_TOUCH", "BB_LOWER_TOUCH");
    }

    @Test
    @DisplayName("오늘 밴드 위·아래로 나가면 터치다")
    void outsideTodaysBandIsTouch() {
        assertThat(codes(price(261_000, null), bands().build())).contains("BB_UPPER_TOUCH");
        assertThat(codes(price(190_000, null), bands().build()))
                .contains("BB_LOWER_TOUCH").doesNotContain("BB_UPPER_TOUCH");
    }

    @Test
    @DisplayName("재현: 골든크로스는 어제→오늘로 본다 — 200봉 전 데드크로스를 읽지 않는다")
    void crossReadsLatestTwoBars() {
        ChartData chart = chart()
                .maLine5(list("101", "99", "60", "50"))
                .maLine20(list("100", "100", "55", "70"))
                .build();

        assertThat(codes(price(227_500, null), chart))
                .contains("MA5_20_GOLDEN").doesNotContain("MA5_20_DEAD");
    }

    /** 오늘(0번) · 최근 5거래일 100만 주 · 그 뒤 1월 3,000만 주 — 11/4 삼성전자 모양. */
    private static List<VolumePoint> volumesNewestFirst() {
        List<VolumePoint> out = new ArrayList<>();
        out.add(VolumePoint.builder().volume(883_465L).build());
        for (int i = 0; i < 5; i++) out.add(VolumePoint.builder().volume(1_000_000L).build());
        for (int i = 0; i < 6; i++) out.add(VolumePoint.builder().volume(30_000_000L).build());
        return out;
    }

    @Test
    @DisplayName("재현: 거래량은 직전 5거래일과 견준다 — 예전엔 200봉 전 거래량과 비교해 급증이 소강으로 나왔다")
    void volumeComparesWithLastFiveDays() {
        ChartData chart = chart().volumes(volumesNewestFirst()).build();

        assertThat(codes(price(227_500, 2_500_000L), chart))
                .contains("VOLUME_SURGE").doesNotContain("VOLUME_DRY");
    }

    @Test
    @DisplayName("장중 누적은 하루치와 견줄 수 없다 — 09시엔 어느 종목이나 '소강'이라 장이 끝난 뒤에만 말한다")
    void volumeDryOnlyAfterTheClose() {
        ChartData chart = chart().volumes(volumesNewestFirst()).build();

        assertThat(codes(price(227_500, 300_000L), chart)).doesNotContain("VOLUME_DRY");

        afterTheClose();
        assertThat(codes(price(227_500, 300_000L), chart)).contains("VOLUME_DRY");
    }
}
