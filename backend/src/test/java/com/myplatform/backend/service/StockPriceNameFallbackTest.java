package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 종목명이 비어 오면 <b>마스터에서 보충</b>한다 — 지어내지는 않는다(2026-09-22).
 *
 * <p><b>고치려는 결함</b>: KIS 현재가 API(FHKST01010100)가 {@code hts_kor_isnm} 을 <b>빈 값</b>으로 준다.
 * prod 실측 — {@code stock_price} 최근 행의 {@code stock_name} 이 005930·000660·011780 전부 {@code ''} 다.
 * 그런데 {@code stock_master} 에는 <b>2,670종목 전부 실명</b>이 들어 있다(005930 → 삼성전자).
 * 쓰기만 하고({@code cacheName}) 읽지는 않아서, 화면 종목명 자리에 "005930" 이 그대로 떴다.
 *
 * <p>시세는 단일 경로(§1)라 이 DTO 의 이름이 목록·상세·추천·발굴로 전부 나간다 —
 * 그래서 한 곳을 고치면 화면 전부가 같이 고쳐지고, 반대로 한 곳이 비면 전부가 빈다.
 * 마스터 조회는 부팅 시 워밍된 메모리 맵이라 비용이 없다.
 *
 * <p><b>불변식</b>:
 * <ul>
 *   <li>소스(KIS/네이버)가 준 이름이 <b>우선</b> — 마스터로 덮어쓰지 않는다(상장사 개명 시 소스가 빠르다).
 *   <li>마스터도 모르면 <b>소스가 준 값을 그대로</b> 돌려준다 — 종목코드를 이름 자리에 넣지 않는다(§4c).
 *   <li>보충은 <b>이름에만</b> 한다 — 가격·거래량은 손대지 않는다(§3 미보정).
 * </ul>
 */
class StockPriceNameFallbackTest {

    private static final String CODE = "005930";

    private StockPriceService service;
    private KoreaInvestmentService kisService;
    private StockMasterService masterService;
    private ObjectMapper om;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        kisService = mock(KoreaInvestmentService.class);
        masterService = mock(StockMasterService.class);
        StockPriceRepository priceRepository = mock(StockPriceRepository.class);
        service = new StockPriceService(mock(RestTemplate.class), priceRepository, om,
                kisService, masterService, new SimpleMeterRegistry());
        when(priceRepository.findTopByStockCodeOrderByFetchedAtDesc(CODE)).thenReturn(Optional.empty());
    }

    private ObjectNode quote(String name) {
        ObjectNode out = om.createObjectNode();
        out.put("stck_prpr", "282000");
        out.put("stck_oprc", "280000");
        out.put("stck_hgpr", "283000");
        out.put("stck_lwpr", "279000");
        out.put("prdy_vrss", "2000");
        out.put("prdy_ctrt", "0.71");
        out.put("acml_vol", "1234567");
        if (name != null) out.put("hts_kor_isnm", name);
        ObjectNode resp = om.createObjectNode();
        resp.put("rt_cd", "0");
        resp.set("output", out);
        return resp;
    }

    private StockPriceDto fetchKis() {
        return (StockPriceDto) ReflectionTestUtils.invokeMethod(service, "fetchFromKoreaInvestment", CODE);
    }

    // ==================== 순수 함수 ====================

    @Nested
    @DisplayName("보충 규칙(순수 함수)")
    class Rule {

        @Test
        @DisplayName("소스가 이름을 주면 그대로 — 마스터로 덮지 않는다")
        void sourceWins() {
            assertThat(StockPriceService.preferGivenNameElseMaster("삼성전자", "옛이름")).isEqualTo("삼성전자");
        }

        @Test
        @DisplayName("소스가 비었으면 마스터로 보충한다 — 빈 문자열·공백·null 전부")
        void masterFillsWhenSourceBlank() {
            assertThat(StockPriceService.preferGivenNameElseMaster("", "삼성전자")).isEqualTo("삼성전자");
            assertThat(StockPriceService.preferGivenNameElseMaster("   ", "삼성전자")).isEqualTo("삼성전자");
            assertThat(StockPriceService.preferGivenNameElseMaster(null, "삼성전자")).isEqualTo("삼성전자");
        }

        @Test
        @DisplayName("둘 다 없으면 소스 값을 그대로 — 코드를 이름으로 채우지 않는다(§4c)")
        void nothingIsInvented() {
            assertThat(StockPriceService.preferGivenNameElseMaster("", null)).isEmpty();
            assertThat(StockPriceService.preferGivenNameElseMaster("", "")).isEmpty();
            assertThat(StockPriceService.preferGivenNameElseMaster(null, null)).isNull();
            assertThat(StockPriceService.preferGivenNameElseMaster(null, "  ")).isNull();
        }
    }

    // ==================== KIS 경로 ====================

    @Nested
    @DisplayName("KIS 시세 경로")
    class KisPath {

        @Test
        @DisplayName("prod 실제 형태 — hts_kor_isnm 이 빈 값이면 마스터 이름이 나온다")
        void emptyKisNameIsFilledFromMaster() {
            when(kisService.getStockPrice(CODE)).thenReturn(quote(""));
            when(masterService.getName(CODE)).thenReturn("삼성전자");

            StockPriceDto dto = fetchKis();

            assertThat(dto).isNotNull();
            assertThat(dto.getStockName()).isEqualTo("삼성전자");
            assertThat(dto.getCurrentPrice()).isEqualByComparingTo("282000");   // 가격은 그대로
        }

        @Test
        @DisplayName("KIS 가 이름을 주면 마스터를 쓰지 않는다")
        void kisNameWins() {
            when(kisService.getStockPrice(CODE)).thenReturn(quote("삼성전자"));
            when(masterService.getName(CODE)).thenReturn("헌이름");

            assertThat(fetchKis().getStockName()).isEqualTo("삼성전자");
        }

        @Test
        @DisplayName("마스터도 모르면 빈 값 그대로 — 종목코드로 채우지 않는다")
        void masterMissKeepsBlank() {
            when(kisService.getStockPrice(CODE)).thenReturn(quote(""));
            when(masterService.getName(CODE)).thenReturn(null);

            StockPriceDto dto = fetchKis();

            assertThat(dto).isNotNull();
            assertThat(dto.getStockName()).isNotEqualTo(CODE);
            assertThat(dto.getStockName()).isNullOrEmpty();
        }

        @Test
        @DisplayName("마스터 적재(cacheName)는 종전대로 계속 호출한다 — 신규 상장 자동 등록 경로")
        void cacheNameStillCalled() {
            when(kisService.getStockPrice(CODE)).thenReturn(quote("삼성전자"));
            when(masterService.getName(CODE)).thenReturn(null);

            fetchKis();

            verify(masterService).cacheName(CODE, "삼성전자", "KIS");
        }
    }
}
