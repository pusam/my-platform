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
}
