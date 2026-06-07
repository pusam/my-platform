package com.myplatform.backend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.StockPrice;
import com.myplatform.backend.repository.StockPriceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P0-1: 가격 outlier 가드 맹점 회귀 테스트.
 *
 * <p>VERIFICATION_BACKLOG.md P0-1 의 합격 기준 4개 + 경계값을 그대로 고정한다.
 * 핵심 맹점: 현재가만 보는 "당일 [저가~고가]±10%" 밴드는, 현재가·고가·저가가 <b>일괄 ×10</b> 되면
 * 현재가가 여전히 밴드 안이라 절대 발화하지 않는다. 일괄 스케일링은 직전 저장가(DB 앵커) 배수로만 잡힌다.
 *
 * <p>합격 기준:
 * <ol>
 *   <li>현재가·고저가가 일괄 ×10 → 가드 발화(ERROR 로깅)</li>
 *   <li>정상 종목(소폭 등락) → 절대 미발화</li>
 *   <li>전일(직전 저장) 종가 0/null → 예외 없이 skip</li>
 *   <li>가격은 여전히 미보정 (로깅만)</li>
 * </ol>
 * 경계값: 직전가 대비 ×4.9 미발화 / ×5.1 발화.
 *
 * <p>가드는 private 이므로 {@code ReflectionTestUtils} 로 호출하고, ERROR 발화 여부는
 * StockPriceService 로거에 ListAppender 를 붙여 캡처한다. (가격 보정 없음 — 탐지/로깅만)
 */
class StockPriceOutlierGuardTest {

    private static final String CODE = "005930";

    private StockPriceService service;
    private StockPriceRepository stockPriceRepository;
    private ObjectMapper objectMapper;
    private KoreaInvestmentService kisService;

    private Logger guardLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        stockPriceRepository = mock(StockPriceRepository.class);
        objectMapper = new ObjectMapper();
        kisService = mock(KoreaInvestmentService.class);
        StockMasterService stockMasterService = mock(StockMasterService.class);

        service = new StockPriceService(
                restTemplate,
                stockPriceRepository,
                objectMapper,
                kisService,
                stockMasterService,
                new SimpleMeterRegistry());

        // ERROR 발화 캡처
        guardLogger = (Logger) LoggerFactory.getLogger(StockPriceService.class);
        appender = new ListAppender<>();
        appender.start();
        guardLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        guardLogger.detachAppender(appender);
    }

    // ---- helpers -----------------------------------------------------------

    /** 직전 저장가(DB 앵커) 를 stub. null 을 넘기면 Optional.empty(). */
    private void givenLastSavedPrice(BigDecimal prev) {
        Optional<StockPrice> result;
        if (prev == null) {
            result = Optional.empty();
        } else {
            StockPrice sp = new StockPrice();
            sp.setCurrentPrice(prev);
            result = Optional.of(sp);
        }
        when(stockPriceRepository.findTopByStockCodeOrderByFetchedAtDesc(CODE)).thenReturn(result);
    }

    /** 직전 저장 엔티티는 있으나 currentPrice 가 null/0 인 경우 stub. */
    private void givenLastSavedPriceEntity(BigDecimal prevOrNull) {
        StockPrice sp = new StockPrice();
        sp.setCurrentPrice(prevOrNull);
        when(stockPriceRepository.findTopByStockCodeOrderByFetchedAtDesc(CODE))
                .thenReturn(Optional.of(sp));
    }

    private ObjectNode rawOutput(String cur, String high, String low, String sdpr, String ctrt) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("stck_prpr", cur);
        out.put("stck_hgpr", high);
        out.put("stck_lwpr", low);
        out.put("stck_sdpr", sdpr);
        out.put("prdy_ctrt", ctrt);
        return out;
    }

    private StockPriceDto dto(BigDecimal cur, BigDecimal high, BigDecimal low, BigDecimal ctrt) {
        StockPriceDto d = new StockPriceDto();
        d.setStockCode(CODE);
        d.setCurrentPrice(cur);
        d.setHighPrice(high);
        d.setLowPrice(low);
        d.setChangeRate(ctrt);
        return d;
    }

    private void invokeGuard(ObjectNode output, StockPriceDto d) {
        ReflectionTestUtils.invokeMethod(service, "warnIfPriceOutlier", CODE, output, d);
    }

    private long errorCount() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).count();
    }

    // ---- 합격 기준 ----------------------------------------------------------

    @Nested
    @DisplayName("기준1: 현재가·고저가 일괄 ×10 → 발화")
    class BatchScaledTen {
        @Test
        @DisplayName("직전가 70000 대비 모든 가격 ×10 (700000/690000~710000) → ERROR 발화")
        void batchTenTimes_fires() {
            givenLastSavedPrice(new BigDecimal("70000"));
            // 현재가가 [저가,고가] 밴드 안에 있고, 등락률도 정상 → 밴드/등락률 그물로는 안 잡힘.
            StockPriceDto d = dto(new BigDecimal("700000"), new BigDecimal("710000"),
                    new BigDecimal("690000"), new BigDecimal("0.5"));
            invokeGuard(rawOutput("700000", "710000", "690000", "697000", "0.5"), d);

            assertThat(errorCount())
                    .as("일괄 ×10 은 DB 앵커 배수 그물로 발화해야 한다")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    @DisplayName("기준2: 정상 종목(소폭 등락) → 절대 미발화")
    class NormalStock {
        @Test
        @DisplayName("직전가 70000, 현재가 70500 (+0.7%) → ERROR 0건")
        void normalSmallMove_neverFires() {
            givenLastSavedPrice(new BigDecimal("70000"));
            StockPriceDto d = dto(new BigDecimal("70500"), new BigDecimal("71000"),
                    new BigDecimal("70000"), new BigDecimal("0.71"));
            invokeGuard(rawOutput("70500", "71000", "70000", "70000", "0.71"), d);

            assertThat(errorCount()).as("정상 등락은 절대 발화하면 안 된다").isZero();
        }
    }

    @Nested
    @DisplayName("기준3: 직전 저장 종가 0/null/없음 → 예외 없이 skip")
    class MissingAnchor {
        @Test
        @DisplayName("직전 저장 이력 없음(Optional.empty) → 예외 없이 통과")
        void noAnchor_skipsGracefully() {
            givenLastSavedPrice(null); // Optional.empty()
            StockPriceDto d = dto(new BigDecimal("700000"), new BigDecimal("710000"),
                    new BigDecimal("690000"), new BigDecimal("0.5"));

            assertThatCode(() -> invokeGuard(
                    rawOutput("700000", "710000", "690000", "697000", "0.5"), d))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("직전 저장가 null → DB 앵커 그물 skip, 예외 없음")
        void anchorPriceNull_skips() {
            givenLastSavedPriceEntity(null);
            StockPriceDto d = dto(new BigDecimal("700000"), new BigDecimal("710000"),
                    new BigDecimal("690000"), new BigDecimal("0.5"));

            assertThatCode(() -> invokeGuard(
                    rawOutput("700000", "710000", "690000", "697000", "0.5"), d))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("직전 저장가 0 → DB 앵커 그물 skip, 예외 없음")
        void anchorPriceZero_skips() {
            givenLastSavedPriceEntity(BigDecimal.ZERO);
            StockPriceDto d = dto(new BigDecimal("700000"), new BigDecimal("710000"),
                    new BigDecimal("690000"), new BigDecimal("0.5"));

            assertThatCode(() -> invokeGuard(
                    rawOutput("700000", "710000", "690000", "0", "0.5"), d))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("기준4: 발화해도 가격은 미보정 (로깅만)")
    class NoCorrection {
        @Test
        @DisplayName("일괄 ×10 발화 후에도 dto 가격 필드는 원본 그대로")
        void fires_butPriceUnchanged() {
            givenLastSavedPrice(new BigDecimal("70000"));
            StockPriceDto d = dto(new BigDecimal("700000"), new BigDecimal("710000"),
                    new BigDecimal("690000"), new BigDecimal("0.5"));
            invokeGuard(rawOutput("700000", "710000", "690000", "697000", "0.5"), d);

            assertThat(errorCount()).isGreaterThanOrEqualTo(1); // 발화는 했고
            assertThat(d.getCurrentPrice()).isEqualByComparingTo("700000"); // 보정 안 함
            assertThat(d.getHighPrice()).isEqualByComparingTo("710000");
            assertThat(d.getLowPrice()).isEqualByComparingTo("690000");
        }
    }

    // ---- 경계값 -------------------------------------------------------------

    @Nested
    @DisplayName("경계값: DB 앵커 배수 임계 5")
    class AnchorRatioBoundary {
        @Test
        @DisplayName("×4.9 (490000 vs 100000) → 미발화")
        void ratio4_9_doesNotFire() {
            givenLastSavedPrice(new BigDecimal("100000"));
            // 밴드/등락률 그물에 걸리지 않도록 현재가를 밴드 안, 등락률 정상으로 둔다.
            StockPriceDto d = dto(new BigDecimal("490000"), new BigDecimal("495000"),
                    new BigDecimal("485000"), new BigDecimal("0.4"));
            invokeGuard(rawOutput("490000", "495000", "485000", "488000", "0.4"), d);

            assertThat(errorCount()).as("×4.9 는 임계 미만이라 미발화").isZero();
        }

        @Test
        @DisplayName("×5.1 (510000 vs 100000) → 발화")
        void ratio5_1_fires() {
            givenLastSavedPrice(new BigDecimal("100000"));
            StockPriceDto d = dto(new BigDecimal("510000"), new BigDecimal("515000"),
                    new BigDecimal("505000"), new BigDecimal("0.4"));
            invokeGuard(rawOutput("510000", "515000", "505000", "508000", "0.4"), d);

            assertThat(errorCount()).as("×5.1 은 임계 이상이라 발화").isGreaterThanOrEqualTo(1);
        }
    }

    // ---- P2-11: 저측 글리치 / 손상 등락률 (P0-1 기존 그물 동작 고정) -----------

    @Nested
    @DisplayName("P2-11: 저측 글리치 ×0.2 / 손상 등락률은 P0-1 그물이 이미 잡는다")
    class P2_11_DataQuality {
        @Test
        @DisplayName("저측 글리치 ×0.1 (7000 vs 직전 70000) → DB 앵커 그물(≤0.2) 발화")
        void lowGlitch_fires() {
            givenLastSavedPrice(new BigDecimal("70000"));
            // 현재가가 자기 밴드 안(7000∈[6900,7100])·등락률 정상 → 그물1·2 미발화, 그물3(0.1배) 발화
            StockPriceDto d = dto(new BigDecimal("7000"), new BigDecimal("7100"),
                    new BigDecimal("6900"), new BigDecimal("0.5"));
            invokeGuard(rawOutput("7000", "7100", "6900", "7000", "0.5"), d);

            assertThat(errorCount()).as("0.1배는 ≤0.2 임계로 발화").isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("손상 등락률 900% → 전일대비율 그물(±31%) 발화")
        void corruptChangeRate_fires() {
            givenLastSavedPrice(new BigDecimal("70000"));
            // 밴드 정상·배수 정상이지만 등락률만 900 → 그물2 발화
            StockPriceDto d = dto(new BigDecimal("70500"), new BigDecimal("71000"),
                    new BigDecimal("70000"), new BigDecimal("900"));
            invokeGuard(rawOutput("70500", "71000", "70000", "70000", "900"), d);

            assertThat(errorCount()).as("900% 는 ±31% 초과로 발화").isGreaterThanOrEqualTo(1);
        }
    }

    // ---- ①: UN vs J raw 대조 진단 (×10 원인 분류) ---------------------------

    @Nested
    @DisplayName("①: 이상치 발화 시 UN(통합) vs J(KRX단독) raw 대조 로깅")
    class UnVsJDiagnostic {
        @Test
        @DisplayName("일괄 ×10 발화 → J 재조회 후 [가격이상-진단] UN/J 대조 ERROR 로깅 (보정 없음)")
        void outlier_logsUnVsJ() {
            givenLastSavedPrice(new BigDecimal("70000"));
            // J(KRX 단독) 응답은 정상가(70500) — UN(700000)만 ×10 임을 대조로 드러낸다.
            ObjectNode jResp = objectMapper.createObjectNode();
            jResp.put("rt_cd", "0");
            jResp.set("output", rawOutput("70500", "71000", "70000", "70000", "0.71"));
            when(kisService.getStockPriceByMarketDiv(CODE, "J")).thenReturn(jResp);

            StockPriceDto d = dto(new BigDecimal("700000"), new BigDecimal("710000"),
                    new BigDecimal("690000"), new BigDecimal("0.5"));
            invokeGuard(rawOutput("700000", "710000", "690000", "697000", "0.5"), d);

            assertThat(appender.list.stream()
                    .anyMatch(e -> e.getLevel() == Level.ERROR
                            && e.getFormattedMessage().contains("[가격이상-진단]")
                            && e.getFormattedMessage().contains("UN[")
                            && e.getFormattedMessage().contains("J[")))
                    .as("이상치 발화 시 UN vs J raw 대조 로그가 남아야 한다")
                    .isTrue();
            // 진단도 가격은 보정하지 않는다(로깅 전용).
            assertThat(d.getCurrentPrice()).isEqualByComparingTo("700000");
        }

        @Test
        @DisplayName("J 응답 없음(NXT 전용/미상장) → 그 사실을 단서로 로깅, 예외 없음")
        void jResponseNull_logsAsClue() {
            givenLastSavedPrice(new BigDecimal("70000"));
            when(kisService.getStockPriceByMarketDiv(CODE, "J")).thenReturn(null);

            StockPriceDto d = dto(new BigDecimal("700000"), new BigDecimal("710000"),
                    new BigDecimal("690000"), new BigDecimal("0.5"));
            assertThatCode(() -> invokeGuard(
                    rawOutput("700000", "710000", "690000", "697000", "0.5"), d))
                    .doesNotThrowAnyException();

            assertThat(appender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("J(KRX단독) 응답 없음")))
                    .as("J 응답이 없으면 그 사실 자체를 진단 단서로 남긴다").isTrue();
        }

        @Test
        @DisplayName("정상 종목 → J 재조회 안 함 (진단 로그·KIS 호출 없음)")
        void normal_noDiagnostic() {
            givenLastSavedPrice(new BigDecimal("70000"));
            StockPriceDto d = dto(new BigDecimal("70500"), new BigDecimal("71000"),
                    new BigDecimal("70000"), new BigDecimal("0.71"));
            invokeGuard(rawOutput("70500", "71000", "70000", "70000", "0.71"), d);

            assertThat(appender.list.stream()
                    .noneMatch(e -> e.getFormattedMessage().contains("[가격이상-진단]")))
                    .as("정상 종목은 UN/J 대조 진단을 호출하지 않는다").isTrue();
            verifyNoInteractions(kisService);
        }
    }
}
