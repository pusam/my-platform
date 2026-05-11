package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myplatform.backend.dto.*;
import com.myplatform.backend.dto.StockDetailDto.*;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StockDetailService 단위 테스트
 *
 * 검증 포인트 (Quick + Heavy API 중심 — getStockDetail 전체 통합은 Jsoup 실제 호출이 있어 단위 테스트 부적합):
 *  1. Quick: KIS 성공 시 시세 + 종목명 + 캐시된 차트/재무 반환
 *  2. Quick: KIS 실패 → 네이버 폴백
 *  3. Quick: KIS + 네이버 모두 실패해도 예외 없이 빈 DTO 반환
 *  4. Quick: 수급 조회 실패해도 시세는 정상 반환 (부분 실패 허용)
 *  5. Heavy: 캐시된 risk + peer + AI 정상 반환
 *  6. Heavy: 한 콜라보레이터 실패해도 나머지는 정상 반환
 *
 * 비고: stockDetailExecutor 는 동기 실행기(Runnable::run)로 주입 — async 가 inline 으로 완료된다.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class StockDetailServiceTest {

    @Mock private KoreaInvestmentService kisService;
    @Mock private ScalpingAnalysisService scalpingService;
    @Mock private RiskManagementService riskService;
    @Mock private VwapService vwapService;
    @Mock private StockPriceService stockPriceService;
    @Mock private InvestorDailyTradeRepository investorDailyTradeRepository;
    @Mock private StockFinancialDataRepository stockFinancialDataRepository;
    @Mock private GeminiService geminiService;
    @Mock private StockDetailCacheService cacheService;
    @Mock private ChartSignalService chartSignalService;
    @Mock private RedisCacheService redisCacheService;

    private StockDetailService stockDetailService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String TEST_STOCK_CODE = "005930";
    private static final String TEST_STOCK_NAME = "삼성전자";

    @BeforeEach
    void setUp() {
        // 동기 실행기 — supplyAsync 가 inline 으로 완료되어 테스트가 빨라진다.
        Executor syncExecutor = Runnable::run;

        // 생성자 인자 순서 = Lombok @RequiredArgsConstructor 가 필드 선언 순서대로 생성
        stockDetailService = new StockDetailService(
                kisService,
                scalpingService,
                riskService,
                vwapService,
                stockPriceService,
                investorDailyTradeRepository,
                stockFinancialDataRepository,
                geminiService,
                cacheService,
                chartSignalService,
                redisCacheService,
                syncExecutor
        );

        // Heavy 의 cacheService.getCachedXxx(stockCode, supplier) 들은
        // 캐시 미스 흐름(=supplier.get() 실행)을 기본으로 시뮬레이션
        when(cacheService.getCachedRiskInfo(anyString(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(cacheService.getCachedPeerData(anyString(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(cacheService.getCachedAiAnalysis(anyString(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());

        // 차트 시그널 — 기본 빈 리스트
        when(chartSignalService.detect(any())).thenReturn(Collections.emptyList());
    }

    /**
     * KIS API 성공 응답 JSON 생성
     */
    private JsonNode buildKisPriceResponse(String price, String change, String changeRate, String stockName) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("rt_cd", "0");
        ObjectNode output = root.putObject("output");
        output.put("stck_prpr", price);
        output.put("prdy_vrss", change);
        output.put("prdy_ctrt", changeRate);
        output.put("hts_kor_isnm", stockName);
        output.put("acml_vol", "10000000");
        output.put("acml_tr_pbmn", "500000000000");
        output.put("stck_hgpr", "72000");
        output.put("stck_lwpr", "69000");
        output.put("stck_oprc", "70000");
        output.put("stck_sdpr", "70000");
        output.put("per", "12.5");
        output.put("pbr", "1.3");
        output.put("eps", "5600");
        output.put("bps", "53000");
        output.put("hts_avls", "4300000");
        output.put("hts_frgn_ehrt", "55.2");
        return root;
    }

    /**
     * KIS API 실패 응답 JSON 생성
     */
    private JsonNode buildKisFailResponse() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("rt_cd", "1");
        root.put("msg1", "조회 실패");
        return root;
    }

    private FinancialInfo dummyFinancial() {
        return FinancialInfo.builder()
                .per(new BigDecimal("12.5"))
                .pbr(new BigDecimal("1.3"))
                .eps(new BigDecimal("5600"))
                .bps(new BigDecimal("53000"))
                .marketCap(4_300_000L)
                .build();
    }

    // ========== Quick API 테스트 ==========

    @Nested
    @DisplayName("getStockDetailQuick - 빠른 데이터 조회")
    class QuickApiTests {

        @Test
        @DisplayName("KIS 성공 시 시세/종목명 + 캐시된 차트/재무 반환")
        void kisSuccess_returnsPriceAndName() {
            // given
            JsonNode kisResponse = buildKisPriceResponse("71000", "+1000", "1.43", TEST_STOCK_NAME);
            when(kisService.getStockPrice(TEST_STOCK_CODE)).thenReturn(kisResponse);
            when(scalpingService.getScalpingAnalysis(TEST_STOCK_CODE)).thenReturn(
                    ScalpingAnalysisDto.builder()
                            .volumePower(new BigDecimal("110"))
                            .foreignNetBuy(new BigDecimal("20"))
                            .instNetBuy(new BigDecimal("10"))
                            .programNetBuy(BigDecimal.ZERO)
                            .build());
            when(cacheService.getCachedChartData(TEST_STOCK_CODE)).thenReturn(null);
            when(cacheService.getCachedFinancialInfo(TEST_STOCK_CODE)).thenReturn(dummyFinancial());

            // when
            StockDetailDto result = stockDetailService.getStockDetailQuick(TEST_STOCK_CODE);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getStockCode()).isEqualTo(TEST_STOCK_CODE);
            assertThat(result.getStockName()).isEqualTo(TEST_STOCK_NAME);
            assertThat(result.getPrice()).isNotNull();
            assertThat(result.getPrice().getCurrentPrice()).isEqualByComparingTo(new BigDecimal("71000"));
            assertThat(result.getPrice().getChangeRate()).isEqualByComparingTo(new BigDecimal("1.43"));
            assertThat(result.getFinancial()).isNotNull();
            assertThat(result.getFinancial().getPer()).isEqualByComparingTo(new BigDecimal("12.5"));

            verify(kisService).getStockPrice(TEST_STOCK_CODE);
            verify(cacheService).getCachedFinancialInfo(TEST_STOCK_CODE);
        }

        @Test
        @DisplayName("KIS 실패 → 네이버 폴백으로 시세 반환")
        void kisFail_fallbackToNaver() {
            // given
            when(kisService.getStockPrice(TEST_STOCK_CODE)).thenReturn(buildKisFailResponse());

            StockPriceDto naverData = new StockPriceDto();
            naverData.setStockCode(TEST_STOCK_CODE);
            naverData.setStockName(TEST_STOCK_NAME);
            naverData.setCurrentPrice(new BigDecimal("70500"));
            naverData.setChangePrice(new BigDecimal("500"));
            naverData.setChangeRate(new BigDecimal("0.71"));
            when(stockPriceService.getStockPrice(TEST_STOCK_CODE)).thenReturn(naverData);
            when(cacheService.getCachedChartData(TEST_STOCK_CODE)).thenReturn(null);
            when(cacheService.getCachedFinancialInfo(TEST_STOCK_CODE)).thenReturn(null);

            // when
            StockDetailDto result = stockDetailService.getStockDetailQuick(TEST_STOCK_CODE);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getStockName()).isEqualTo(TEST_STOCK_NAME);
            assertThat(result.getPrice()).isNotNull();
            assertThat(result.getPrice().getCurrentPrice()).isEqualByComparingTo(new BigDecimal("70500"));

            verify(kisService, atLeastOnce()).getStockPrice(TEST_STOCK_CODE);
            verify(stockPriceService).getStockPrice(TEST_STOCK_CODE);
        }

        @Test
        @DisplayName("KIS + 네이버 모두 실패해도 예외 없이 빈 DTO 반환")
        void allFail_returnsEmptyDto() {
            // given
            when(kisService.getStockPrice(TEST_STOCK_CODE)).thenReturn(buildKisFailResponse());
            when(stockPriceService.getStockPrice(TEST_STOCK_CODE)).thenReturn(null);
            when(cacheService.getCachedChartData(TEST_STOCK_CODE)).thenReturn(null);
            when(cacheService.getCachedFinancialInfo(TEST_STOCK_CODE)).thenReturn(null);

            // when
            StockDetailDto result = stockDetailService.getStockDetailQuick(TEST_STOCK_CODE);

            // then — 예외 없이 stockCode 만 있는 DTO 반환
            assertThat(result).isNotNull();
            assertThat(result.getStockCode()).isEqualTo(TEST_STOCK_CODE);
            assertThat(result.getPrice()).isNull();
        }

        @Test
        @DisplayName("수급 조회 실패해도 시세는 정상 반환 (부분 실패 허용)")
        void supplyFail_priceStillReturned() {
            // given
            JsonNode kisResponse = buildKisPriceResponse("71000", "+1000", "1.43", TEST_STOCK_NAME);
            when(kisService.getStockPrice(TEST_STOCK_CODE)).thenReturn(kisResponse);
            when(scalpingService.getScalpingAnalysis(TEST_STOCK_CODE))
                    .thenThrow(new RuntimeException("KIS API timeout"));
            when(cacheService.getCachedChartData(TEST_STOCK_CODE)).thenReturn(null);
            when(cacheService.getCachedFinancialInfo(TEST_STOCK_CODE)).thenReturn(dummyFinancial());

            // when
            StockDetailDto result = stockDetailService.getStockDetailQuick(TEST_STOCK_CODE);

            // then
            assertThat(result.getPrice()).isNotNull();
            assertThat(result.getPrice().getCurrentPrice()).isEqualByComparingTo(new BigDecimal("71000"));
            // 수급 실패해도 전체 흐름은 살아있음 (supplyDemand 는 null 이거나 비어있음)
        }

        @Test
        @DisplayName("차트 캐시 미스(null) 시에도 다른 데이터는 정상 반환")
        void chartCacheMiss_otherDataStillReturned() {
            // given
            JsonNode kisResponse = buildKisPriceResponse("71000", "+1000", "1.43", TEST_STOCK_NAME);
            when(kisService.getStockPrice(TEST_STOCK_CODE)).thenReturn(kisResponse);
            when(cacheService.getCachedChartData(TEST_STOCK_CODE)).thenReturn(null);
            when(cacheService.getCachedFinancialInfo(TEST_STOCK_CODE)).thenReturn(dummyFinancial());

            // when
            StockDetailDto result = stockDetailService.getStockDetailQuick(TEST_STOCK_CODE);

            // then
            assertThat(result.getChartData()).isNull();
            assertThat(result.getPrice()).isNotNull();
            assertThat(result.getFinancial()).isNotNull();
        }

        @Test
        @DisplayName("차트 캐시 히트 시 ChartData 가 DTO 에 주입됨")
        void chartCacheHit_chartDataInjected() {
            // given
            JsonNode kisResponse = buildKisPriceResponse("71000", "+1000", "1.43", TEST_STOCK_NAME);
            when(kisService.getStockPrice(TEST_STOCK_CODE)).thenReturn(kisResponse);

            ChartData cachedChart = ChartData.builder()
                    .ma5(new BigDecimal("70500"))
                    .ma20(new BigDecimal("69800"))
                    .build();
            when(cacheService.getCachedChartData(TEST_STOCK_CODE)).thenReturn(cachedChart);
            when(cacheService.getCachedFinancialInfo(TEST_STOCK_CODE)).thenReturn(null);

            // when
            StockDetailDto result = stockDetailService.getStockDetailQuick(TEST_STOCK_CODE);

            // then
            assertThat(result.getChartData()).isNotNull();
            assertThat(result.getChartData().getMa5()).isEqualByComparingTo(new BigDecimal("70500"));
            verify(cacheService).getCachedChartData(TEST_STOCK_CODE);
        }
    }

    // ========== Heavy API 테스트 ==========

    @Nested
    @DisplayName("getStockDetailHeavy - 무거운 데이터 조회")
    class HeavyApiTests {

        @Test
        @DisplayName("리스크 + 피어 + AI 정상 반환")
        void heavySuccess_returnsAllSections() {
            // given
            JsonNode kisResponse = buildKisPriceResponse("71000", "+1000", "1.43", TEST_STOCK_NAME);
            when(kisService.getStockPrice(TEST_STOCK_CODE)).thenReturn(kisResponse);

            RiskAnalysisDto riskDto = RiskAnalysisDto.builder()
                    .riskScore(25)
                    .status(RiskAnalysisDto.RiskStatus.SAFE)
                    .reason("안전")
                    .relatedNews(new ArrayList<>())  // ★ mutable — service 가 addAll 함
                    .dangerousDisclosures(new ArrayList<>())
                    .build();
            when(riskService.analyzeRisk(anyString(), eq(TEST_STOCK_CODE))).thenReturn(riskDto);

            // cacheService.getCachedPeerData/AiAnalysis 를 직접 반환값으로 stub → 내부 supplier(=네트워크) 미호출
            Map<String, Object> peerMap = new HashMap<>();
            peerMap.put("peers", Collections.emptyList());
            peerMap.put("sectorName", "반도체");
            doReturn(peerMap).when(cacheService).getCachedPeerData(anyString(), any());

            AiAnalysis aiStub = AiAnalysis.builder().overallScore(65).recommendation("HOLD").build();
            doReturn(aiStub).when(cacheService).getCachedAiAnalysis(anyString(), any());

            // when
            Map<String, Object> result = stockDetailService.getStockDetailHeavy(TEST_STOCK_CODE);

            // then
            assertThat(result).isNotNull();
            assertThat(result.get("risk")).isNotNull();
            assertThat(((RiskInfo) result.get("risk")).getRiskScore()).isEqualTo(25);
            assertThat(result).containsKey("peerComparisons");
            assertThat(result.get("aiAnalysis")).isEqualTo(aiStub);

            verify(riskService, atLeastOnce()).analyzeRisk(anyString(), eq(TEST_STOCK_CODE));
        }

        @Test
        @DisplayName("리스크 실패해도 result Map 반환 + 나머지 키 존재 (부분 실패 허용)")
        void riskFail_resultStillReturned() {
            // given
            JsonNode kisResponse = buildKisPriceResponse("71000", "+1000", "1.43", TEST_STOCK_NAME);
            when(kisService.getStockPrice(TEST_STOCK_CODE)).thenReturn(kisResponse);
            when(riskService.analyzeRisk(anyString(), eq(TEST_STOCK_CODE)))
                    .thenThrow(new RuntimeException("DART API timeout"));

            // peer / AI 는 stub 으로 — 내부 네트워크 호출 방지
            Map<String, Object> peerMap = new HashMap<>();
            peerMap.put("peers", Collections.emptyList());
            doReturn(peerMap).when(cacheService).getCachedPeerData(anyString(), any());
            doReturn(null).when(cacheService).getCachedAiAnalysis(anyString(), any());

            // when
            Map<String, Object> result = stockDetailService.getStockDetailHeavy(TEST_STOCK_CODE);

            // then — 리스크 실패해도 다른 섹션은 살아있어야 함
            assertThat(result).isNotNull();
            assertThat(result.get("risk")).isNull(); // 실패한 섹션만 null
            assertThat(result).containsKey("peerComparisons"); // 다른 섹션은 정상
        }

        @Test
        @DisplayName("cacheService 가 캐시된 값 직접 반환하면 supplier 호출 없음")
        void cacheHit_supplierNotInvoked() {
            // given — Heavy 의 cacheService.getCachedRiskInfo 가 캐시된 RiskInfo 직접 반환
            // doReturn 사용 — setUp 의 thenAnswer 가 already 발동하지 않게.
            RiskInfo cachedRisk = RiskInfo.builder()
                    .riskScore(10)
                    .riskStatus("SAFE")
                    .news(Collections.emptyList())
                    .disclosures(Collections.emptyList())
                    .build();
            doReturn(cachedRisk).when(cacheService).getCachedRiskInfo(anyString(), any());

            Map<String, Object> peerMap = new HashMap<>();
            peerMap.put("peers", Collections.emptyList());
            peerMap.put("sectorName", "반도체");
            doReturn(peerMap).when(cacheService).getCachedPeerData(anyString(), any());

            AiAnalysis aiStub = AiAnalysis.builder().overallScore(72).recommendation("BUY").build();
            doReturn(aiStub).when(cacheService).getCachedAiAnalysis(anyString(), any());

            JsonNode kisResponse = buildKisPriceResponse("71000", "+1000", "1.43", TEST_STOCK_NAME);
            when(kisService.getStockPrice(TEST_STOCK_CODE)).thenReturn(kisResponse);

            // when
            Map<String, Object> result = stockDetailService.getStockDetailHeavy(TEST_STOCK_CODE);

            // then — supplier 가 호출되지 않으므로 riskService 호출 0 회
            assertThat(result.get("risk")).isEqualTo(cachedRisk);
            assertThat(((AiAnalysis) result.get("aiAnalysis")).getOverallScore()).isEqualTo(72);
            verify(riskService, never()).analyzeRisk(anyString(), anyString());
        }
    }
}
