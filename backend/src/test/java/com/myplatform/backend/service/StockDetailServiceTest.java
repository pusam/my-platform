package com.myplatform.backend.service;

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
    @Mock private org.springframework.beans.factory.ObjectProvider<StockAnalysisService> stockAnalysisProvider;
    @Mock private StockDetailCacheService cacheService;
    @Mock private ChartSignalService chartSignalService;
    @Mock private RedisCacheService redisCacheService;

    private StockDetailService stockDetailService;

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
                stockAnalysisProvider,
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
     * 공용 시세 DTO 생성 — Quick/Heavy 시세는 stockPriceService.getStockPrice() 경로 사용.
     * (목록 화면과 동일 캐시/DB 소스로 통일하면서 시세 소스가 KIS JsonNode → StockPriceDto 로 바뀜)
     */
    private StockPriceDto buildPriceDto(String price, String rate, String name) {
        StockPriceDto dto = new StockPriceDto();
        dto.setStockCode(TEST_STOCK_CODE);
        dto.setStockName(name);
        dto.setCurrentPrice(new BigDecimal(price));
        dto.setChangeRate(new BigDecimal(rate));
        dto.setHighPrice(new BigDecimal("72000"));
        dto.setLowPrice(new BigDecimal("69000"));
        dto.setOpenPrice(new BigDecimal("70000"));
        dto.setDataSource("KIS");
        return dto;
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
            when(stockPriceService.getStockPrice(TEST_STOCK_CODE))
                    .thenReturn(buildPriceDto("71000", "1.43", TEST_STOCK_NAME));
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

            verify(stockPriceService).getStockPrice(TEST_STOCK_CODE);
            verify(cacheService).getCachedFinancialInfo(TEST_STOCK_CODE);
        }

        @Test
        @DisplayName("재현: 시세 블록에 시세를 가져온 시각(asOf) — 화면이 응답 받은 시각을 시세 시각처럼 보이지 않게(2026-10-04)")
        void priceCarriesItsOwnFetchTime() {
            StockPriceDto dto = buildPriceDto("71000", "1.43", TEST_STOCK_NAME);
            java.time.LocalDateTime fetched = java.time.LocalDateTime.of(2026, 10, 2, 15, 31, 5);
            dto.setFetchedAt(fetched);
            when(stockPriceService.getStockPrice(TEST_STOCK_CODE)).thenReturn(dto);
            when(cacheService.getCachedChartData(TEST_STOCK_CODE)).thenReturn(null);
            when(cacheService.getCachedFinancialInfo(TEST_STOCK_CODE)).thenReturn(dummyFinancial());

            StockDetailDto result = stockDetailService.getStockDetailQuick(TEST_STOCK_CODE);

            assertThat(result.getPrice().getAsOf()).isEqualTo(fetched);
        }

        @Test
        @DisplayName("공용 시세 경로(stockPriceService)로 시세 반환 — KIS→네이버 폴백은 내부 책임")
        void priceFromCommonService() {
            // given — 시세 소스는 stockPriceService.getStockPrice() 로 통일됨(목록과 동일).
            //         KIS→네이버 폴백은 stockPriceService 내부에서 처리되므로 여기선 결과만 stub.
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

            verify(stockPriceService).getStockPrice(TEST_STOCK_CODE);
        }

        @Test
        @DisplayName("KIS + 네이버 모두 실패해도 예외 없이 빈 DTO 반환")
        void allFail_returnsEmptyDto() {
            // given — 공용 시세 경로가 null 반환 (KIS+네이버 모두 실패는 내부에서 처리됨)
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
            when(stockPriceService.getStockPrice(TEST_STOCK_CODE))
                    .thenReturn(buildPriceDto("71000", "1.43", TEST_STOCK_NAME));
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
            when(stockPriceService.getStockPrice(TEST_STOCK_CODE))
                    .thenReturn(buildPriceDto("71000", "1.43", TEST_STOCK_NAME));
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
            when(stockPriceService.getStockPrice(TEST_STOCK_CODE))
                    .thenReturn(buildPriceDto("71000", "1.43", TEST_STOCK_NAME));

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
            // given — Heavy 시세/종목명도 공용 경로(stockPriceService) 사용
            when(stockPriceService.getStockPrice(TEST_STOCK_CODE))
                    .thenReturn(buildPriceDto("71000", "1.43", TEST_STOCK_NAME));

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
            when(stockPriceService.getStockPrice(TEST_STOCK_CODE))
                    .thenReturn(buildPriceDto("71000", "1.43", TEST_STOCK_NAME));
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

            when(stockPriceService.getStockPrice(TEST_STOCK_CODE))
                    .thenReturn(buildPriceDto("71000", "1.43", TEST_STOCK_NAME));

            // when
            Map<String, Object> result = stockDetailService.getStockDetailHeavy(TEST_STOCK_CODE);

            // then — supplier 가 호출되지 않으므로 riskService 호출 0 회
            assertThat(result.get("risk")).isEqualTo(cachedRisk);
            assertThat(((AiAnalysis) result.get("aiAnalysis")).getOverallScore()).isEqualTo(72);
            verify(riskService, never()).analyzeRisk(anyString(), anyString());
        }
    }

    // ========== P1-3: 시세 단일 경로 회귀 가드 ==========

    @Nested
    @DisplayName("P1-3 시세 단일 경로 — getQuick/getHeavy 는 StockPriceService.getStockPrice() 만 경유")
    class SinglePriceSourceGuard {

        @Test
        @DisplayName("getQuick 표시가격은 stockPriceService sentinel 그대로 + KIS 직접 시세호출 0회")
        void quick_displayPriceFromSinglePathOnly() {
            // 구분 가능한 sentinel — 별도 시세 경로가 끼면 이 값이 그대로 안 나온다.
            when(stockPriceService.getStockPrice(TEST_STOCK_CODE))
                    .thenReturn(buildPriceDto("88888", "1.11", TEST_STOCK_NAME));
            when(cacheService.getCachedChartData(TEST_STOCK_CODE)).thenReturn(null);
            when(cacheService.getCachedFinancialInfo(TEST_STOCK_CODE)).thenReturn(null);

            StockDetailDto result = stockDetailService.getStockDetailQuick(TEST_STOCK_CODE);

            assertThat(result.getPrice()).isNotNull();
            assertThat(result.getPrice().getCurrentPrice()).isEqualByComparingTo("88888");
            verify(stockPriceService).getStockPrice(TEST_STOCK_CODE);
            // 회귀 가드: getQuick 이 표시가격을 KIS 에서 직접 당겨오면(병렬 경로) 이 검증이 깨진다.
            verify(kisService, never()).getStockPrice(anyString());
        }

        @Test
        @DisplayName("getHeavy 도 시세를 stockPriceService.getStockPrice() 경유로 받음")
        void heavy_routesPriceThroughSinglePath() {
            doReturn(RiskInfo.builder().riskScore(10).riskStatus("SAFE")
                    .news(Collections.emptyList()).disclosures(Collections.emptyList()).build())
                    .when(cacheService).getCachedRiskInfo(anyString(), any());
            Map<String, Object> peerMap = new HashMap<>();
            peerMap.put("peers", Collections.emptyList());
            doReturn(peerMap).when(cacheService).getCachedPeerData(anyString(), any());
            doReturn(AiAnalysis.builder().overallScore(72).recommendation("BUY").build())
                    .when(cacheService).getCachedAiAnalysis(anyString(), any());
            when(stockPriceService.getStockPrice(TEST_STOCK_CODE))
                    .thenReturn(buildPriceDto("88888", "1.11", TEST_STOCK_NAME));

            stockDetailService.getStockDetailHeavy(TEST_STOCK_CODE);

            verify(stockPriceService, atLeastOnce()).getStockPrice(TEST_STOCK_CODE);
        }
    }

    // ========== P3-6: 손상 등락률 표시 계층 정제 ==========

    @Nested
    @DisplayName("P3-6 표시 등락률 정제 — 손상 prdy_ctrt(>40%)는 null(§4c), 저장/시그널 경로 무관")
    class DisplayChangeRateSanitize {

        @Test
        @DisplayName("displaySafeChangeRate 경계: 40 유지 / 40.01·900 → null / 음수 대칭 / null → null")
        void helperBoundaries() {
            assertThat(StockDetailService.displaySafeChangeRate(new BigDecimal("40"))).isEqualByComparingTo("40");
            assertThat(StockDetailService.displaySafeChangeRate(new BigDecimal("40.01"))).isNull();
            assertThat(StockDetailService.displaySafeChangeRate(new BigDecimal("900.00"))).isNull();
            assertThat(StockDetailService.displaySafeChangeRate(new BigDecimal("-900.00"))).isNull();
            assertThat(StockDetailService.displaySafeChangeRate(new BigDecimal("29.99"))).isEqualByComparingTo("29.99");
            assertThat(StockDetailService.displaySafeChangeRate(null)).isNull();
        }

        @Test
        @DisplayName("getQuick: 손상 등락률(900%) 종목은 표시 changeRate 가 null 로 정제 — 현재가는 유지")
        void quick_corruptRateNulled() {
            when(stockPriceService.getStockPrice(TEST_STOCK_CODE))
                    .thenReturn(buildPriceDto("71000", "900.00", TEST_STOCK_NAME));
            when(cacheService.getCachedChartData(TEST_STOCK_CODE)).thenReturn(null);
            when(cacheService.getCachedFinancialInfo(TEST_STOCK_CODE)).thenReturn(null);

            StockDetailDto result = stockDetailService.getStockDetailQuick(TEST_STOCK_CODE);

            assertThat(result.getPrice()).isNotNull();
            assertThat(result.getPrice().getCurrentPrice()).isEqualByComparingTo("71000");   // 가격 무변경
            assertThat(result.getPrice().getChangeRate()).isNull();                          // 손상 등락률만 null
        }
    }

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("AI 프롬프트 정합 (2026-07-10)")
    class AiPromptIntegrity {

        private com.myplatform.backend.dto.StockDetailDto dtoWith(String dataSource, BigDecimal foreignNetBuy) {
            var price = com.myplatform.backend.dto.StockDetailDto.PriceInfo.builder()
                    .currentPrice(new BigDecimal("70000")).changeRate(BigDecimal.ZERO).build();
            var supply = com.myplatform.backend.dto.StockDetailDto.SupplyDemand.builder()
                    .foreignNetBuy(foreignNetBuy).instNetBuy(BigDecimal.ZERO).dataSource(dataSource).build();
            return com.myplatform.backend.dto.StockDetailDto.builder()
                    .stockCode("298040").stockName("효성중공업").price(price).supplyDemand(supply).build();
        }

        @Test
        @DisplayName("Fix1: 프롬프트에 분석 기준일 주입(날짜 환각 방지)")
        void promptHasReferenceDate() {
            String prompt = stockDetailService.buildGeminiPrompt(dtoWith("실시간", new BigDecimal("100")));
            assertThat(prompt).contains("분석 기준일:");
            assertThat(prompt).contains(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString());
        }

        @Test
        @DisplayName("Fix2: 장전(초기화) 수급 → 0억 raw 대신 5일 누적 대체 주입(§4c)")
        void preMarketSupplyUses5Day() {
            StockAnalysisService mockAnalysis = org.mockito.Mockito.mock(StockAnalysisService.class);
            when(mockAnalysis.getFiveDayNetBuy("298040")).thenReturn(
                    new StockAnalysisService.FiveDaySupply(
                            new BigDecimal("937"), new BigDecimal("-120"), 5));   // 억원(저장 단위) — 예전 픽스처는 원이라 실제로는 "+0억"이 나갔다
            when(stockAnalysisProvider.getIfAvailable()).thenReturn(mockAnalysis);

            String prompt = stockDetailService.buildGeminiPrompt(dtoWith("장전(초기화)", BigDecimal.ZERO));

            assertThat(prompt).contains("최근 5거래일 누적 수급 — 외국인 +937억, 기관 -120억");
            assertThat(prompt).doesNotContain("외국인 순매수: 0억");
        }

        @Test
        @DisplayName("Fix2: 장전 + 5일 데이터 없음 → '미수집' 표기(가짜 0 금지)")
        void preMarketNoDataShowsMissing() {
            // stockAnalysisProvider.getIfAvailable() 기본 null → 5일 미가용
            String prompt = stockDetailService.buildGeminiPrompt(dtoWith("장전(초기화)", BigDecimal.ZERO));
            assertThat(prompt).contains("미수집");
            assertThat(prompt).doesNotContain("외국인 순매수: 0억");
        }

        @Test
        @DisplayName("Fix4: 가짜 뉴스 문구가 프롬프트에 주입되지 않음")
        void noFabricatedNews() {
            String prompt = stockDetailService.buildGeminiPrompt(dtoWith("실시간", new BigDecimal("100")));
            assertThat(prompt).doesNotContain("자사주 1조원 소각");
            assertThat(prompt).doesNotContain("역대 최대 실적");
        }

        @Test
        @DisplayName("추세 채널 줄 — 30봉 상승 추세 캔들(최신순) → '상승 채널'+위치, 봉 부족/캔들 없음 → null(§4c 생략)")
        void channelLineFromCandles() {
            // 최신→과거 순(ChartData.candles 규약) — 최신 130 → 과거 101 상승 추세
            java.util.List<com.myplatform.backend.dto.StockDetailDto.CandlePoint> candles = new java.util.ArrayList<>();
            for (int i = 0; i < 30; i++) {
                java.math.BigDecimal close = java.math.BigDecimal.valueOf(130 - i);
                candles.add(com.myplatform.backend.dto.StockDetailDto.CandlePoint.builder()
                        .close(close).high(close.add(BigDecimal.ONE)).low(close.subtract(BigDecimal.ONE)).build());
            }
            String line = StockDetailService.buildChannelLine(candles);
            assertThat(line).contains("추세 채널(30일 회귀)");
            assertThat(line).contains("상승 채널");
            assertThat(line).contains("채널 내 위치");

            assertThat(StockDetailService.buildChannelLine(candles.subList(0, 5))).isNull();   // 봉 부족
            assertThat(StockDetailService.buildChannelLine(null)).isNull();
            assertThat(StockDetailService.buildChannelLine(java.util.List.of())).isNull();
        }
    }

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("라벨 정합 순수 함수 (Fix3)")
    class LabelResolution {
        @Test
        @DisplayName("'수급 강세' 미스노머 제거 — 차트약세+매수rec → '매수 우위'(수급 단어 없음)")
        void noSupplyStrengthMisnomer() {
            assertThat(StockDetailService.resolveTechnicalSignal("이평선 하향 이탈", "BUY", null))
                    .isEqualTo("매수 우위");
            assertThat(StockDetailService.resolveTechnicalSignal("NEUTRAL", "BUY", null))
                    .doesNotContain("수급");
        }

        @Test
        @DisplayName("본문 종합판단 관망/매도인데 점수 매수 → 라벨 억제(본문 정합)")
        void suppressWhenBodyConflicts() {
            assertThat(StockDetailService.resolveTechnicalSignal("이평선 하향 이탈", "BUY", "관망"))
                    .isEqualTo("관망");
            assertThat(StockDetailService.resolveTechnicalSignal("NEUTRAL", "TRADING_BUY", "매도"))
                    .isEqualTo("관망 (본문 매도 의견)");
        }

        @Test
        @DisplayName("classifyVerdict — 최초 등장 판단어 추출")
        void classifyVerdictFirstToken() {
            assertThat(StockDetailService.classifyVerdict("관망 또는 조정 시 매수 기회")).isEqualTo("관망");
            assertThat(StockDetailService.classifyVerdict("매수 우위")).isEqualTo("매수");
            assertThat(StockDetailService.classifyVerdict("근거 없음")).isNull();
            assertThat(StockDetailService.classifyVerdict(null)).isNull();
        }

        @Test
        @DisplayName("reconcileRecommendationWithBody — 본문 매도/관망이면 매수 계열 뱃지 억제(HOLD) (#2)")
        void reconcileSuppressesBullishAgainstBearishBody() {
            assertThat(StockDetailService.reconcileRecommendationWithBody("BUY", "매도")).isEqualTo("HOLD");
            assertThat(StockDetailService.reconcileRecommendationWithBody("TRADING_BUY", "관망")).isEqualTo("HOLD");
            assertThat(StockDetailService.reconcileRecommendationWithBody("WAIT_AND_BUY", "매도")).isEqualTo("HOLD");
            assertThat(StockDetailService.reconcileRecommendationWithBody("SELL", "매수")).isEqualTo("HOLD");
        }

        @Test
        @DisplayName("reconcileRecommendationWithBody — 본문 일치·근거없음이면 원본 유지(§4c fail-open)")
        void reconcileKeepsWhenConsistentOrNull() {
            assertThat(StockDetailService.reconcileRecommendationWithBody("BUY", "매수")).isEqualTo("BUY");
            assertThat(StockDetailService.reconcileRecommendationWithBody("SELL", "매도")).isEqualTo("SELL");
            assertThat(StockDetailService.reconcileRecommendationWithBody("BUY", null)).isEqualTo("BUY");
            assertThat(StockDetailService.reconcileRecommendationWithBody("HOLD", "관망")).isEqualTo("HOLD");
        }
    }

    @Nested
    @DisplayName("수급 패널(DB 일별) — 2026-10-03 화면 점검")
    class SupplyPanelFromDb {

        private com.myplatform.backend.entity.InvestorDailyTrade row(String investor, String type, String net) {
            return com.myplatform.backend.entity.InvestorDailyTrade.builder()
                    .tradeDate(java.time.LocalDate.of(2026, 10, 2)).stockCode("009150").stockName("삼성전기")
                    .investorType(investor).tradeType(type).netBuyAmount(new BigDecimal(net)).build();
        }

        @Test
        @DisplayName("재현: 기관(기관 계) −31.62억에 연기금 −63.24억을 더해 '기관 −95억'이던 것 — 연기금은 이미 기관에 들어 있다")
        void pensionIsNotAddedToInstitution() {
            java.time.LocalDate d = java.time.LocalDate.of(2026, 10, 2);
            when(investorDailyTradeRepository.findLatestTradeDate()).thenReturn(d);
            when(investorDailyTradeRepository.findByStockCodeAndDateRange("009150", d, d)).thenReturn(List.of(
                    row("FOREIGN", "BUY", "727.26"), row("INSTITUTION", "SELL", "-31.62"), row("PENSION", "SELL", "-63.24")));

            SupplyDemand sd = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    stockDetailService, "getDailySummaryFromDb", "009150");

            assertThat(sd.getForeignNetBuy()).isEqualByComparingTo("727.26");
            assertThat(sd.getInstNetBuy()).isEqualByComparingTo("-31.62");
        }

        @Test
        @DisplayName("연기금 행만 있으면 기관 합계는 모름(null) — 연기금을 기관 전체로 내놓지 않는다")
        void pensionOnlyMeansInstitutionUnknown() {
            java.time.LocalDate d = java.time.LocalDate.of(2026, 10, 2);
            when(investorDailyTradeRepository.findLatestTradeDate()).thenReturn(d);
            when(investorDailyTradeRepository.findByStockCodeAndDateRange("009150", d, d)).thenReturn(List.of(
                    row("FOREIGN", "SELL", "-10.00"), row("PENSION", "BUY", "5.00")));

            SupplyDemand sd = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    stockDetailService, "getDailySummaryFromDb", "009150");

            assertThat(sd.getInstNetBuy()).isNull();
        }

        @Test
        @DisplayName("장전·조회 실패의 빈 수급은 0 이 아니라 모름 — 예전엔 '+0억' 세 줄")
        void emptySupplyIsUnknownNotZero() {
            SupplyDemand sd = stockDetailService.buildEmptySupplyDemand();
            assertThat(sd.getForeignNetBuy()).isNull();
            assertThat(sd.getInstNetBuy()).isNull();
            assertThat(sd.getProgramNetBuy()).isNull();
            assertThat(sd.getVolumePower()).isNull();
        }
    }

    @Nested
    @DisplayName("지어낸 재무·Peer 값 제거 — 2026-10-03 화면 점검")
    class NoFabricatedFinancials {

        @Test
        @DisplayName("재현: KIS 외국인 소진율이 비면 시총 구간으로 45/35/25/10% 를 채우던 것 — 이제 모름(null), TSR·자사주 추정도 없음")
        void foreignOwnershipNotEstimated() {
            FinancialInfo f = FinancialInfo.builder().marketCap(1_178_000L).dividendYield(new BigDecimal("3.5")).build();

            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    stockDetailService, "enrichWithForwardMetrics", "009150", f, null);

            assertThat(f.getForeignOwnership()).isNull();
            assertThat(f.getTotalShareholderReturn()).isNull();
            assertThat(f.getBuybackInfo()).isNull();
        }

        @Test
        @DisplayName("재현: Peer 표가 손으로 적은 상수(PBR·PER·ROE·배당)로 시작하던 것 — KIS 가 못 주면 값 없음")
        void peerValuesAreLiveOnly() {
            when(kisService.getStockPrice(anyString())).thenReturn(null);   // KIS 실패
            FinancialInfo current = FinancialInfo.builder().pbr(new BigDecimal("1.40")).per(new BigDecimal("12.0")).build();

            List<StockDetailDto.PeerComparison> peers = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    stockDetailService, "buildPeerComparisons", "005930", "삼성전자", current);

            assertThat(peers).isNotEmpty();
            for (StockDetailDto.PeerComparison p : peers) {
                if (p.isCurrent()) {
                    assertThat(p.getPbr()).isEqualByComparingTo("1.40");
                } else {
                    assertThat(p.getPbr()).as(p.getStockName()).isNull();
                    assertThat(p.getPer()).as(p.getStockName()).isNull();
                    assertThat(p.getRoe()).as(p.getStockName()).isNull();
                    assertThat(p.getDividendYield()).as(p.getStockName()).isNull();
                }
            }
        }
    }

    @Nested
    @DisplayName("AI 카드 — 키워드 점수·자체 가격 가이드 없음(2026-10-03)")
    class AiVerdictOnly {

        @Test
        @DisplayName("재현: 본문에 '순매수'가 있어도 종합 판단이 관망이면 HOLD — 숫자 점수·가격 가이드·충돌 문구 없음")
        void verdictFromBodyOnly() {
            String resp = "■ 수급\n- 외국인 순매수 지속, 매수세 유입\n■ 종합 판단\n관망. 추가 확인 필요\n";
            StockDetailDto dto = StockDetailDto.builder().stockCode("009150").build();

            AiAnalysis a = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    stockDetailService, "parseGeminiResponse", resp, dto);

            assertThat(a.getOverallScore()).isNull();
            assertThat(a.getRecommendation()).isEqualTo("HOLD");
            assertThat(a.getPriceGuide()).isNull();
            assertThat(a.getConflictAnalysis()).isNull();
        }

        @Test
        @DisplayName("판단어 매핑 — 모르면 null(HOLD 로 채우지 않는다)")
        void verdictMapping() {
            assertThat(StockDetailService.verdictRecommendation("매수")).isEqualTo("BUY");
            assertThat(StockDetailService.verdictRecommendation("관망")).isEqualTo("HOLD");
            assertThat(StockDetailService.verdictRecommendation("매도")).isEqualTo("SELL");
            assertThat(StockDetailService.verdictRecommendation(null)).isNull();
        }

        @Test
        @DisplayName("재현: Gemini 가 없을 때 '적극 매수 구간입니다'·점수·목표가를 지어내던 규칙 경로 — 이제 '분석 불가'와 관측 근거만")
        void ruleFallbackHasNoVerdict() {
            StockDetailDto dto = StockDetailDto.builder().stockCode("009150")
                    .price(PriceInfo.builder().currentPrice(new BigDecimal("1577000")).changeRate(new BigDecimal("1.02")).build())
                    .supplyDemand(SupplyDemand.builder().volumePower(new BigDecimal("129.1"))
                            .foreignNetBuy(new BigDecimal("727")).instNetBuy(new BigDecimal("-31.6")).build())
                    .build();

            AiAnalysis a = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    stockDetailService, "generateAiAnalysis", dto);

            assertThat(a.getOverallScore()).isNull();
            assertThat(a.getRecommendation()).isNull();
            assertThat(a.getPriceGuide()).isNull();
            assertThat(a.getStrategy()).isEqualTo(StockDetailService.NO_AI_STRATEGY_TEXT);
        }
    }
}

