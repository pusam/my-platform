package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.repository.StockPriceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F3 — <b>정상 보합(0%)을 결측으로 취급해 다른 시점의 등락률로 덮지 않는다</b>(2026-09-17 감사).
 *
 * <p><b>고치려는 결함</b>: 등락률이 {@code null} 이든 {@code 0} 이든 같은 "결측"으로 보고
 * ① 일봉 API 의 <b>다른 날</b> 등락률, ② 네이버의 등락률로 덮었다. 현재가는 그대로라
 * 같은 DTO 안에서 <b>가격과 등락률의 시점이 어긋난다</b>. 통합시세(NXT 포함)와 KRX 일봉은 거래소·시점이
 * 달라 특히 위험하다. 시세는 단일 경로라 이 DTO 를 목록·상세·추천이 모두 소비한다.
 *
 * <p><b>파싱은 이미 구분 가능하다</b> — {@code getBigDecimalValue} 는 필드 부재·빈 문자열·숫자 아님이면
 * null 을 준다. 그러니 "0 = 결측"은 소비 쪽의 판단 오류다.
 *
 * <p>보충은 <b>같은 기준가격임을 확인할 수 있을 때만</b> 허용한다 — 일봉 종가가 현재가와 같아야 그
 * 등락률이 이 가격의 등락률이다. 근거가 없으면 null 을 유지한다(§4c).
 */
class StockPriceZeroChangeRateTest {

    private static final String CODE = "005930";
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private StockPriceService service;
    private KoreaInvestmentService kisService;
    private ObjectMapper om;
    private StockPriceRepository priceRepository;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        kisService = mock(KoreaInvestmentService.class);
        priceRepository = mock(StockPriceRepository.class);
        StockMasterService master = mock(StockMasterService.class);
        service = new StockPriceService(mock(RestTemplate.class), priceRepository, om,
                kisService, master, new SimpleMeterRegistry());
        when(priceRepository.findTopByStockCodeOrderByFetchedAtDesc(CODE)).thenReturn(Optional.empty());
    }

    /** KIS 현재가 응답 — rate/changePrice 에 null 을 주면 필드를 아예 넣지 않는다(진짜 결측). */
    private ObjectNode kisQuote(String current, String changePrice, String changeRate) {
        ObjectNode out = om.createObjectNode();
        out.put("stck_prpr", current);
        out.put("stck_oprc", current);
        out.put("stck_hgpr", current);
        out.put("stck_lwpr", current);
        out.put("acml_vol", "1000");
        if (changePrice != null) out.put("prdy_vrss", changePrice);
        if (changeRate != null) out.put("prdy_ctrt", changeRate);
        out.put("hts_kor_isnm", "삼성전자");
        ObjectNode resp = om.createObjectNode();
        resp.put("rt_cd", "0");
        resp.set("output", out);
        return resp;
    }

    /** 일봉 응답 — 첫 행의 날짜/종가/등락률을 지정한다. */
    private ObjectNode daily(LocalDate date, String close, String rate) {
        ObjectNode bar = om.createObjectNode();
        bar.put("stck_bsop_date", date.format(YMD));
        bar.put("stck_clpr", close);
        bar.put("prdy_ctrt", rate);
        ArrayNode arr = om.createArrayNode();
        arr.add(bar);
        ObjectNode resp = om.createObjectNode();
        resp.set("output2", arr);
        return resp;
    }

    private StockPriceDto fetchKis() {
        return (StockPriceDto) ReflectionTestUtils.invokeMethod(service, "fetchFromKoreaInvestment", CODE);
    }

    // ==================== 정상 보합 보존 ====================

    @Nested
    @DisplayName("정상 0% 보합")
    class LegitimateZero {

        @Test
        @DisplayName("10,000원·전일대비 0·등락률 0% 는 그대로 0% — 다른 날 일봉의 +5% 로 덮지 않는다")
        void zeroIsPreservedAgainstDailyFallback() {
            when(kisService.getStockPrice(CODE)).thenReturn(kisQuote("10000", "0", "0"));
            // 다른 시점의 일봉: 어제 종가 9,500·+5%
            when(kisService.getDailyPrices(eq(CODE), anyInt()))
                    .thenReturn(daily(LocalDate.now().minusDays(1), "9500", "5.00"));

            StockPriceDto dto = fetchKis();

            assertThat(dto).isNotNull();
            assertThat(dto.getCurrentPrice()).isEqualByComparingTo("10000");
            assertThat(dto.getChangeRate()).isEqualByComparingTo("0");
            assertThat(dto.getDataSource()).isEqualTo("KIS");
        }

        @Test
        @DisplayName("보합이면 일봉 API 를 아예 부르지 않는다 — 불필요한 KIS 호출도 막는다")
        void zeroDoesNotTriggerDailyCall() {
            when(kisService.getStockPrice(CODE)).thenReturn(kisQuote("10000", "0", "0"));

            fetchKis();

            verify(kisService, never()).getDailyPrices(eq(CODE), anyInt());
        }

        @Test
        @DisplayName("보합이면 네이버 보충도 하지 않는다 — getStockPrice 전체 경로에서 0% 유지")
        void zeroIsPreservedThroughFullFetchPath() {
            when(kisService.isConfigured()).thenReturn(true);
            when(kisService.isTokenAvailable()).thenReturn(true);
            when(kisService.getStockPrice(CODE)).thenReturn(kisQuote("10000", "0", "0"));

            StockPriceDto dto = (StockPriceDto) ReflectionTestUtils.invokeMethod(service, "fetchStockPrice", CODE);

            assertThat(dto).isNotNull();
            assertThat(dto.getChangeRate()).isEqualByComparingTo("0");
            assertThat(dto.getCurrentPrice()).isEqualByComparingTo("10000");
        }
    }

    // ==================== 진짜 결측 ====================

    @Nested
    @DisplayName("진짜 결측(null)")
    class ActuallyMissing {

        @Test
        @DisplayName("등락률만 결측이고 전일대비가 유효하면 같은 응답으로 계산한다 — 정상 경로 보존")
        void computedFromSameResponseChangePrice() {
            when(kisService.getStockPrice(CODE)).thenReturn(kisQuote("10300", "300", null));

            StockPriceDto dto = fetchKis();

            assertThat(dto.getChangeRate()).isEqualByComparingTo("3.00");   // 300/10000
            verify(kisService, never()).getDailyPrices(eq(CODE), anyInt());
        }

        @Test
        @DisplayName("전일대비 0 + 등락률 결측도 같은 응답으로 0% 계산 — 0 을 '없음'으로 보지 않는다")
        void zeroChangePriceStillComputesZeroRate() {
            when(kisService.getStockPrice(CODE)).thenReturn(kisQuote("10000", "0", null));

            StockPriceDto dto = fetchKis();

            assertThat(dto.getChangeRate()).isEqualByComparingTo("0.00");
            verify(kisService, never()).getDailyPrices(eq(CODE), anyInt());
        }

        @Test
        @DisplayName("둘 다 결측이고 일봉 종가가 현재가와 같으면 보충한다 — 같은 기준가격 근거가 있다")
        void dailyFallbackAllowedWhenCloseMatchesCurrentPrice() {
            when(kisService.getStockPrice(CODE)).thenReturn(kisQuote("10000", null, null));
            when(kisService.getDailyPrices(eq(CODE), anyInt()))
                    .thenReturn(daily(LocalDate.now(), "10000", "2.50"));

            StockPriceDto dto = fetchKis();

            assertThat(dto.getChangeRate()).isEqualByComparingTo("2.50");
            assertThat(dto.getDataSource()).isEqualTo("KIS_DAILY");
        }

        @Test
        @DisplayName("둘 다 결측인데 일봉 종가가 현재가와 다르면 null 유지 — 다른 시점 값을 복사하지 않는다")
        void dailyFallbackRejectedWhenCloseDiffers() {
            when(kisService.getStockPrice(CODE)).thenReturn(kisQuote("10000", null, null));
            when(kisService.getDailyPrices(eq(CODE), anyInt()))
                    .thenReturn(daily(LocalDate.now().minusDays(1), "9500", "5.00"));

            StockPriceDto dto = fetchKis();

            assertThat(dto.getChangeRate()).isNull();
            assertThat(dto.getCurrentPrice()).isEqualByComparingTo("10000");   // 현재가는 건드리지 않는다
            assertThat(dto.getDataSource()).isEqualTo("KIS");
        }

        @Test
        @DisplayName("일봉 첫 행에 날짜가 없으면 보충하지 않는다 — '첫 행 = 어제'로 가정하지 않는다")
        void dailyFallbackRejectedWithoutDate() {
            when(kisService.getStockPrice(CODE)).thenReturn(kisQuote("10000", null, null));
            ObjectNode bar = om.createObjectNode();
            bar.put("stck_clpr", "10000");
            bar.put("prdy_ctrt", "5.00");
            ArrayNode arr = om.createArrayNode();
            arr.add(bar);
            ObjectNode resp = om.createObjectNode();
            resp.set("output2", arr);
            when(kisService.getDailyPrices(eq(CODE), anyInt())).thenReturn(resp);

            assertThat(fetchKis().getChangeRate()).isNull();
        }

        @Test
        @DisplayName("일봉 조회 자체가 실패해도 null 유지 — 0 으로 위장하지 않는다")
        void dailyFallbackFailureKeepsNull() {
            when(kisService.getStockPrice(CODE)).thenReturn(kisQuote("10000", null, null));
            when(kisService.getDailyPrices(eq(CODE), anyInt())).thenReturn(null);

            assertThat(fetchKis().getChangeRate()).isNull();
        }
    }

    // ==================== 네이버 보충 ====================

    @Nested
    @DisplayName("네이버 보충")
    class NaverSupplement {

        @Test
        @DisplayName("보합(0/0)은 네이버 보충 대상이 아니다")
        void zeroIsNotSupplemented() {
            StockPriceDto kisDto = new StockPriceDto();
            kisDto.setStockCode(CODE);
            kisDto.setCurrentPrice(new BigDecimal("10000"));
            kisDto.setChangePrice(BigDecimal.ZERO);
            kisDto.setChangeRate(BigDecimal.ZERO);

            assertThat(StockPriceService.needsExternalChangeRate(kisDto)).isFalse();
        }

        @Test
        @DisplayName("둘 다 null 일 때만 보충 대상")
        void onlyNullsAreSupplemented() {
            StockPriceDto dto = new StockPriceDto();
            dto.setCurrentPrice(new BigDecimal("10000"));
            assertThat(StockPriceService.needsExternalChangeRate(dto)).isTrue();

            dto.setChangeRate(BigDecimal.ZERO);
            assertThat(StockPriceService.needsExternalChangeRate(dto)).isFalse();
        }

        @Test
        @DisplayName("현재가가 없으면 보충하지 않는다 — 기준가격을 대조할 수 없다")
        void noCurrentPriceNoSupplement() {
            StockPriceDto dto = new StockPriceDto();
            assertThat(StockPriceService.needsExternalChangeRate(dto)).isFalse();
        }

        @Test
        @DisplayName("네이버 현재가가 KIS 현재가와 다르면 등락률을 가져오지 않는다 — 다른 기준가격")
        void naverRateRejectedWhenPriceDiffers() {
            StockPriceDto kisDto = new StockPriceDto();
            kisDto.setCurrentPrice(new BigDecimal("10000"));
            StockPriceDto naver = new StockPriceDto();
            naver.setCurrentPrice(new BigDecimal("9800"));
            naver.setChangeRate(new BigDecimal("5.00"));

            StockPriceService.applyNaverSupplement(kisDto, naver);

            assertThat(kisDto.getChangeRate()).isNull();
        }

        @Test
        @DisplayName("네이버 현재가가 같으면 0% 를 포함해 그대로 가져온다 — 같은 기준가격 근거")
        void naverRateAcceptedWhenPriceMatchesIncludingZero() {
            StockPriceDto kisDto = new StockPriceDto();
            kisDto.setCurrentPrice(new BigDecimal("10000"));
            StockPriceDto naver = new StockPriceDto();
            naver.setCurrentPrice(new BigDecimal("10000"));
            naver.setChangeRate(BigDecimal.ZERO);
            naver.setChangePrice(BigDecimal.ZERO);

            StockPriceService.applyNaverSupplement(kisDto, naver);

            assertThat(kisDto.getChangeRate()).isEqualByComparingTo("0");
            assertThat(kisDto.getChangePrice()).isEqualByComparingTo("0");
        }
    }
}
