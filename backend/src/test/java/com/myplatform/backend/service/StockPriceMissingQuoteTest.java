package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.StockPrice;
import com.myplatform.backend.repository.StockPriceRepository;
import com.myplatform.core.util.DateTimeUtil;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>시세가 없는 응답을 "조회 성공"으로 돌려주지 않는다</b>(2026-09-22).
 *
 * <p><b>고치려는 결함</b>: KIS 는 <b>없는 종목코드에도 200 + {@code rt_cd=0}</b> 을 준다(§4c 에 여러 번
 * 기록된 KIS 특성 — "틀린 요청에도 200 을 준다"). {@code output} 은 빈 값이라
 * {@code getBigDecimalValue} 가 전부 {@code BigDecimal.ZERO} 로 돌려주고, 종목명은 {@code ""} 가 된다.
 * 그 DTO 가 non-null 이라 컨트롤러의 {@code price == null → 404} 분기를 그대로 통과했다.
 *
 * <p>prod 실측 {@code GET /api/stock/999999}:
 * <pre>
 *   success true · message "시세 조회 성공"
 *   stockName "" · currentPrice 0 · volume 0 · dataSource "KIS"
 * </pre>
 * 게다가 그 0 원 DTO 가 {@code stock_price} 에 <b>저장</b>된다 — 실측으로 999999·888888 행이 남았다.
 *
 * <p><b>무엇으로 가르나</b>: <b>현재가뿐이다.</b> 0 원은 현실에 없는 값이라 결측으로 단정할 수 있다
 * (§4c 의 "PBR/PER 의 0 은 현실에 없는 값이라 결측으로 단정할 수 있다"와 같은 논리).
 * ⚠ 거래량·등락률·종목명으로 가르면 안 된다:
 * <ul>
 *   <li><b>보합</b>은 등락률·전일대비가 <b>정상적으로 0</b> 이다(F3 불변식, 2026-09-17).
 *   <li><b>거래정지</b>는 KIS 가 <b>동결가를 계속 주고 거래량이 0</b> 이다(§4c 2026-09-07).
 *       가격은 유효하므로 조회 성공이다.
 * </ul>
 *
 * <p>가격을 <b>보정하지 않는다</b> — 쓸 수 있는 시세가 없다고 판단할 뿐이다(§3 미보정 불변식).
 */
class StockPriceMissingQuoteTest {

    private static final String CODE = "999999";

    private StockPriceService service;
    private KoreaInvestmentService kisService;
    private StockPriceRepository priceRepository;
    private ObjectMapper om;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        kisService = mock(KoreaInvestmentService.class);
        priceRepository = mock(StockPriceRepository.class);
        service = new StockPriceService(mock(RestTemplate.class), priceRepository, om,
                kisService, mock(StockMasterService.class), new SimpleMeterRegistry());
        when(priceRepository.findTopByStockCodeOrderByFetchedAtDesc(CODE)).thenReturn(Optional.empty());
    }

    /** rt_cd=0 인 정상 응답 골격. 값이 null 이면 그 필드를 아예 넣지 않는다(진짜 결측). */
    private ObjectNode quote(String current, String changePrice, String changeRate,
                             String volume, String name) {
        ObjectNode out = om.createObjectNode();
        if (current != null) {
            out.put("stck_prpr", current);
            out.put("stck_oprc", current);
            out.put("stck_hgpr", current);
            out.put("stck_lwpr", current);
        }
        if (changePrice != null) out.put("prdy_vrss", changePrice);
        if (changeRate != null) out.put("prdy_ctrt", changeRate);
        if (volume != null) out.put("acml_vol", volume);
        if (name != null) out.put("hts_kor_isnm", name);
        ObjectNode resp = om.createObjectNode();
        resp.put("rt_cd", "0");
        resp.set("output", out);
        return resp;
    }

    private StockPriceDto fetchKis() {
        return (StockPriceDto) ReflectionTestUtils.invokeMethod(service, "fetchFromKoreaInvestment", CODE);
    }

    // ==================== 쓸 수 있는 시세가 있는 경우 — 전부 성공이어야 한다 ====================

    @Nested
    @DisplayName("유효한 시세는 그대로 통과한다")
    class ValidQuotes {

        @Test
        @DisplayName("정상 종목 — 현재가 72,000 · 등락 +1.5%")
        void normalStock() {
            when(kisService.getStockPrice(CODE)).thenReturn(quote("72000", "1100", "1.55", "1234567", "삼성전자"));

            StockPriceDto dto = fetchKis();

            assertThat(dto).isNotNull();
            assertThat(dto.getCurrentPrice()).isEqualByComparingTo("72000");
        }

        @Test
        @DisplayName("보합 — 전일대비 0 · 등락률 0% 는 결측이 아니다(F3 불변식)")
        void unchangedIsValid() {
            when(kisService.getStockPrice(CODE)).thenReturn(quote("10000", "0", "0", "50000", "보합종목"));

            StockPriceDto dto = fetchKis();

            assertThat(dto).isNotNull();
            assertThat(dto.getCurrentPrice()).isEqualByComparingTo("10000");
            assertThat(dto.getChangeRate()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("거래정지 — 동결가 973원 · 거래량 0 도 유효한 시세다(§4c 2026-09-07)")
        void haltedStockIsValid() {
            when(kisService.getStockPrice(CODE)).thenReturn(quote("973", "0", "0", "0", "정지종목"));

            StockPriceDto dto = fetchKis();

            assertThat(dto).isNotNull();
            assertThat(dto.getCurrentPrice()).isEqualByComparingTo("973");
            assertThat(dto.getVolume()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("종목명이 비어도 가격이 있으면 시세다 — 이름으로 가르지 않는다")
        void missingNameWithPriceIsValid() {
            when(kisService.getStockPrice(CODE)).thenReturn(quote("5000", "10", "0.20", "100", null));

            assertThat(fetchKis()).isNotNull();
        }
    }

    // ==================== 쓸 수 있는 시세가 없는 경우 ====================

    @Nested
    @DisplayName("시세가 없으면 조회 성공이 아니다")
    class NoUsableQuote {

        @Test
        @DisplayName("없는 종목 — rt_cd=0 인데 값이 전부 0 이면 null (prod 999999 의 실제 응답 모양)")
        void nonexistentCodeYieldsNull() {
            when(kisService.getStockPrice(CODE)).thenReturn(quote("0", "0", "0", "0", ""));

            assertThat(fetchKis())
                    .as("0원은 현실에 없는 값이다 — 조회 성공으로 돌려주면 컨트롤러가 404 를 못 낸다")
                    .isNull();
        }

        @Test
        @DisplayName("현재가 필드가 아예 없는 응답도 null")
        void absentPriceFieldYieldsNull() {
            when(kisService.getStockPrice(CODE)).thenReturn(quote(null, null, null, "0", ""));

            assertThat(fetchKis()).isNull();
        }
    }

    // ==================== 조회 실패는 종전대로 ====================

    @Nested
    @DisplayName("조회 실패 — 기존 동작 유지")
    class FetchFailure {

        @Test
        @DisplayName("응답 자체가 null 이면 null")
        void nullResponse() {
            when(kisService.getStockPrice(CODE)).thenReturn(null);

            assertThat(fetchKis()).isNull();
        }

        @Test
        @DisplayName("rt_cd 가 0 이 아니면 null — 에러 응답")
        void errorResponse() {
            ObjectNode resp = om.createObjectNode();
            resp.put("rt_cd", "1");
            resp.put("msg1", "조회할 수 없는 종목입니다");
            when(kisService.getStockPrice(CODE)).thenReturn(resp);

            assertThat(fetchKis()).isNull();
        }

        @Test
        @DisplayName("output 이 없으면 null")
        void missingOutput() {
            ObjectNode resp = om.createObjectNode();
            resp.put("rt_cd", "0");
            when(kisService.getStockPrice(CODE)).thenReturn(resp);

            assertThat(fetchKis()).isNull();
        }
    }

    // ==================== 저장된 0원도 시세가 아니다 ====================

    /**
     * ⚠ <b>배포 후 실측으로 드러난 누락</b>(2026-09-22). 저장 시점 가드만으로는 부족하다 —
     * <b>이미 쌓인 0원 행</b>이 15분 캐시 창 안이면 그대로 200 으로 나갔다.
     * prod {@code /api/stock/999999} 가 08:16 적재분을 돌려줬다(888888 은 캐시가 없어 404 였다).
     * 읽는 쪽도 같은 기준으로 걸러야 컨트롤러가 404 를 낼 수 있다.
     */
    @Nested
    @DisplayName("캐시에 남은 0원 행")
    class CachedZeroPrice {

        private StockPrice row(String price) {
            StockPrice e = new StockPrice();
            e.setStockCode(CODE);
            e.setCurrentPrice(new BigDecimal(price));
            // ⚠ LocalDateTime.now() 를 쓰면 안 된다 — isValidCache 는 DateTimeUtil.kstNow() 로 재는데
            //   CI 러너는 UTC 라 JVM 기본 시각이 KST 보다 9시간 과거가 되어 캐시가 만료로 판정된다.
            //   로컬(KST)에서는 둘이 같아 통과하고 CI 에서만 깨진다 — 2026-09-22 에 실제로 그랬다.
            e.setFetchedAt(DateTimeUtil.kstNow());   // 캐시 창 안
            e.setDataSource("KIS");
            return e;
        }

        @Test
        @DisplayName("저장된 0원 행은 쓰지 않는다 — 신규 조회로 떨어지고 없는 종목이면 null")
        void storedZeroIsNotServed() {
            when(priceRepository.findTopByStockCodeOrderByFetchedAtDesc(CODE))
                    .thenReturn(Optional.of(row("0")));
            when(kisService.isConfigured()).thenReturn(true);
            when(kisService.isTokenAvailable()).thenReturn(true);
            when(kisService.getStockPrice(CODE)).thenReturn(quote("0", "0", "0", "0", ""));

            assertThat(service.getStockPrice(CODE)).isNull();
        }

        @Test
        @DisplayName("저장된 정상 가격은 종전대로 캐시에서 바로 나간다 — KIS 를 부르지 않는다")
        void storedRealPriceStillServed() {
            when(priceRepository.findTopByStockCodeOrderByFetchedAtDesc(CODE))
                    .thenReturn(Optional.of(row("72000")));
            when(kisService.isConfigured()).thenReturn(true);

            StockPriceDto dto = service.getStockPrice(CODE);

            assertThat(dto).isNotNull();
            assertThat(dto.getCurrentPrice()).isEqualByComparingTo("72000");
            verify(kisService, never()).getStockPrice(CODE);
        }
    }
}
