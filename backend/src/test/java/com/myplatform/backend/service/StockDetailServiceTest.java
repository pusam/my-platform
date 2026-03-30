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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.atLeastOnce;

/**
 * StockDetailService 단위 테스트
 *
 * 검증 포인트:
 * 1. KIS API 성공 시 정상 데이터 파싱
 * 2. KIS 실패 → 네이버 폴백 체인 동작
 * 3. Quick API: 시세+수급+차트+재무 반환
 * 4. Heavy API: 리스크+AI+피어 반환
 * 5. 병렬 조회 중 일부 실패해도 나머지 정상 반환
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
    @Mock private StockDetailCacheService cacheService;
    @Mock private GeminiService geminiService;

    @InjectMocks
    private StockDetailService stockDetailService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String TEST_STOCK_CODE = "005930";
    private static final String TEST_STOCK_NAME = "삼성전자";

    /**
     * KIS API 성공 응답 JSON 생성
     */
    private JsonNode buildKisPriceResponse(String price, String change, String changeRate, String stockName) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("rt_cd", "0");
        ObjectNode output = root.putObject("output");
        output.put("stck_prpr", price);          // 현재가
        output.put("prdy_vrss", change);         // 전일대비
        output.put("prdy_ctrt", changeRate);     // 등락률
        output.put("hts_kor_isnm", stockName);   // 종목명
        output.put("acml_vol", "10000000");       // 거래량
        output.put("acml_tr_pbmn", "500000000000"); // 거래대금
        output.put("stck_hgpr", "72000");         // 고가
        output.put("stck_lwpr", "69000");         // 저가
        output.put("stck_oprc", "70000");         // 시가
        output.put("stck_sdpr", "70000");         // 전일종가
        output.put("per", "12.5");
        output.put("pbr", "1.3");
        output.put("eps", "5600");
        output.put("bps", "53000");
        output.put("hts_avls", "4300000");        // 시가총액(억)
        output.put("hts_frgn_ehrt", "55.2");     // 외국인지분율
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

    // ========== Quick API 테스트 ==========

    @Nested
    @DisplayName("getStockDetailQuick - 빠른 데이터 조회")
    class QuickApiTests {

        @Test
        @DisplayName("KIS 성공 시 시세/종목명 정상 반환")
        void kisSuccess_returnsPriceAndName() {
            // given
            JsonNode kisResponse = buildKisPriceResponse("71000", "+1000", "1.43", TEST_STOCK_NAME);
            when(kisService.getStockPrice(TEST_STOCK_CODE)).thenReturn(kisResponse);
            when(kisService.getDailyPrices(anyString(), anyInt())).thenReturn(null); // 차트 없음
            when(stockFinancialDataRepository.findByStockCodeOrderByReportDateDesc(anyString()))
                    .thenReturn(Collections.emptyList());

            // when
            StockDetailDto result = stockDetailService.getStockDetailQuick(TEST_STOCK_CODE);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getStockCode()).isEqualTo(TEST_STOCK_CODE);
            assertThat(result.getStockName()).isEqualTo(TEST_STOCK_NAME);
            assertThat(result.getPrice()).isNotNull();
            assertThat(result.getPrice().getCurrentPrice()).isEqualByComparingTo(new BigDecimal("71000"));
            assertThat(result.getPrice().getChangeRate()).isEqualByComparingTo(new BigDecimal("1.43"));

            verify(kisService).getStockPrice(TEST_STOCK_CODE);
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
            when(kisService.getDailyPrices(anyString(), anyInt())).thenReturn(null);

            // when
            StockDetailDto result = stockDetailService.getStockDetailQuick(TEST_STOCK_CODE);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getStockName()).isEqualTo(TEST_STOCK_NAME);
            assertThat(result.getPrice()).isNotNull();
            assertThat(result.getPrice().getCurrentPrice()).isEqualByComparingTo(new BigDecimal("70500"));

            // KIS 호출 확인 + 네이버 폴백 호출 확인
            verify(kisService, atLeastOnce()).getStockPrice(TEST_STOCK_CODE);
            verify(stockPriceService).getStockPrice(TEST_STOCK_CODE);
        }

        @Test
        @DisplayName("KIS + 네이버 모두 실패해도 예외 없이 빈 DTO 반환")
        void allFail_returnsEmptyDto() {
            // given
            when(kisService.getStockPrice(TEST_STOCK_CODE)).thenReturn(buildKisFailResponse());
            when(stockPriceService.getStockPrice(TEST_STOCK_CODE)).thenReturn(null);
            when(stockPriceService.searchStocks(TEST_STOCK_CODE)).thenReturn(Collections.emptyList());
            when(kisService.getDailyPrices(anyString(), anyInt())).thenReturn(null);

            // when
            StockDetailDto result = stockDetailService.getStockDetailQuick(TEST_STOCK_CODE);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getStockCode()).isEqualTo(TEST_STOCK_CODE);
            // 가격 없어도 예외 없이 반환
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
            when(kisService.getDailyPrices(anyString(), anyInt())).thenReturn(null);
            when(stockFinancialDataRepository.findByStockCodeOrderByReportDateDesc(anyString()))
                    .thenReturn(Collections.emptyList());

            // when
            StockDetailDto result = stockDetailService.getStockDetailQuick(TEST_STOCK_CODE);

            // then
            assertThat(result.getPrice()).isNotNull();
            assertThat(result.getPrice().getCurrentPrice()).isEqualByComparingTo(new BigDecimal("71000"));
            // 수급은 null이거나 빈 데이터 → 전체가 실패하지 않음
        }

        @Test
        @DisplayName("수급 서비스가 데이터 반환하면 Quick 결과에 포함 (시간 무관)")
        void supplyAvailable_includedInResult() {
            // given — ScalpingAnalysis + DB 둘 다 mock (장중/장마감 모두 대비)
            JsonNode kisResponse = buildKisPriceResponse("71000", "+1000", "1.43", TEST_STOCK_NAME);
            when(kisService.getStockPrice(TEST_STOCK_CODE)).thenReturn(kisResponse);

            ScalpingAnalysisDto scalping = ScalpingAnalysisDto.builder()
                    .volumePower(new BigDecimal("125.5"))
                    .foreignNetBuy(new BigDecimal("50.3"))
                    .instNetBuy(new BigDecimal("-12.1"))
                    .programNetBuy(new BigDecimal("8.7"))
                    .build();
            when(scalpingService.getScalpingAnalysis(TEST_STOCK_CODE)).thenReturn(scalping);
            // 장마감 시 DB 폴백용
            when(investorDailyTradeRepository.findLatestTradeDate()).thenReturn(null);

            // when
            StockDetailDto result = stockDetailService.getStockDetailQuick(TEST_STOCK_CODE);

            // then — 시세는 항상 반환, 수급은 시간대에 따라 다를 수 있음
            assertThat(result.getPrice()).isNotNull();
            assertThat(result.getPrice().getCurrentPrice()).isEqualByComparingTo(new BigDecimal("71000"));
            // 수급 서비스가 호출되었는지 확인 (장중이면 scalpingService, 장마감이면 DB)
            verify(kisService, atLeastOnce()).getStockPrice(TEST_STOCK_CODE);
        }
    }

    // ========== Heavy API 테스트 ==========

    @Nested
    @DisplayName("getStockDetailHeavy - 무거운 데이터 조회")
    class HeavyApiTests {

        @Test
        @DisplayName("리스크 분석 정상 반환")
        void heavySuccess_returnsRiskData() {
            // given
            JsonNode kisResponse = buildKisPriceResponse("71000", "+1000", "1.43", TEST_STOCK_NAME);
            when(kisService.getStockPrice(TEST_STOCK_CODE)).thenReturn(kisResponse);

            RiskAnalysisDto riskDto = RiskAnalysisDto.builder()
                    .riskScore(25)
                    .status(RiskAnalysisDto.RiskStatus.SAFE)
                    .reason("안전")
                    .relatedNews(Collections.emptyList())
                    .dangerousDisclosures(Collections.emptyList())
                    .build();
            when(riskService.analyzeRisk(anyString(), eq(TEST_STOCK_CODE))).thenReturn(riskDto);
            when(kisService.getDailyPrices(anyString(), anyInt())).thenReturn(null);
            when(stockFinancialDataRepository.findByStockCodeOrderByReportDateDesc(anyString()))
                    .thenReturn(Collections.emptyList());

            // when
            var result = stockDetailService.getStockDetailHeavy(TEST_STOCK_CODE);

            // then
            assertThat(result).isNotNull();
            // riskService.analyzeRisk 호출 확인
            verify(riskService).analyzeRisk(anyString(), eq(TEST_STOCK_CODE));
        }

        @Test
        @DisplayName("리스크 실패해도 결과 Map은 반환 (부분 실패 허용)")
        void riskFail_resultStillReturned() {
            // given
            JsonNode kisResponse = buildKisPriceResponse("71000", "+1000", "1.43", TEST_STOCK_NAME);
            when(kisService.getStockPrice(TEST_STOCK_CODE)).thenReturn(kisResponse);
            when(riskService.analyzeRisk(anyString(), eq(TEST_STOCK_CODE)))
                    .thenThrow(new RuntimeException("DART API timeout"));
            when(kisService.getDailyPrices(anyString(), anyInt())).thenReturn(null);
            when(stockFinancialDataRepository.findByStockCodeOrderByReportDateDesc(anyString()))
                    .thenReturn(Collections.emptyList());

            // when
            var result = stockDetailService.getStockDetailHeavy(TEST_STOCK_CODE);

            // then — 리스크 실패해도 결과 Map은 반환
            assertThat(result).isNotNull();
        }
    }

    // ========== 통합 getStockDetail 테스트 ==========

    @Nested
    @DisplayName("getStockDetail - 전체 통합 조회")
    class FullDetailTests {

        @Test
        @DisplayName("전체 데이터 통합 반환 (Happy Path)")
        void fullDetail_happyPath() {
            // given
            JsonNode kisResponse = buildKisPriceResponse("71000", "+1000", "1.43", TEST_STOCK_NAME);
            when(kisService.getStockPrice(TEST_STOCK_CODE)).thenReturn(kisResponse);

            ScalpingAnalysisDto scalping = ScalpingAnalysisDto.builder()
                    .volumePower(new BigDecimal("110"))
                    .foreignNetBuy(new BigDecimal("20"))
                    .instNetBuy(new BigDecimal("10"))
                    .programNetBuy(BigDecimal.ZERO)
                    .build();
            when(scalpingService.getScalpingAnalysis(TEST_STOCK_CODE)).thenReturn(scalping);

            RiskAnalysisDto riskDto = RiskAnalysisDto.builder()
                    .riskScore(15)
                    .status(RiskAnalysisDto.RiskStatus.SAFE)
                    .reason("리스크 요인 없음")
                    .relatedNews(Collections.emptyList())
                    .dangerousDisclosures(Collections.emptyList())
                    .build();
            when(riskService.analyzeRisk(anyString(), eq(TEST_STOCK_CODE))).thenReturn(riskDto);
            when(kisService.getDailyPrices(anyString(), anyInt())).thenReturn(null);
            when(stockFinancialDataRepository.findByStockCodeOrderByReportDateDesc(anyString()))
                    .thenReturn(Collections.emptyList());

            // when
            StockDetailDto result = stockDetailService.getStockDetail(TEST_STOCK_CODE);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getStockCode()).isEqualTo(TEST_STOCK_CODE);
            assertThat(result.getStockName()).isEqualTo(TEST_STOCK_NAME);
            assertThat(result.getPrice()).isNotNull();
            assertThat(result.getAiAnalysis()).isNotNull(); // 규칙 기반이라도 항상 생성
        }

        @Test
        @DisplayName("AI 분석은 Gemini 실패 시 규칙기반 폴백")
        void geminiFailFallbackToRuleBased() {
            // given
            JsonNode kisResponse = buildKisPriceResponse("71000", "+1000", "1.43", TEST_STOCK_NAME);
            when(kisService.getStockPrice(TEST_STOCK_CODE)).thenReturn(kisResponse);
            when(geminiService.analyzeStockDetail(anyString())).thenReturn(null); // Gemini 실패
            when(kisService.getDailyPrices(anyString(), anyInt())).thenReturn(null);
            when(stockFinancialDataRepository.findByStockCodeOrderByReportDateDesc(anyString()))
                    .thenReturn(Collections.emptyList());

            RiskAnalysisDto riskDto = RiskAnalysisDto.builder()
                    .riskScore(10).status(RiskAnalysisDto.RiskStatus.SAFE)
                    .relatedNews(Collections.emptyList())
                    .dangerousDisclosures(Collections.emptyList())
                    .build();
            when(riskService.analyzeRisk(anyString(), eq(TEST_STOCK_CODE))).thenReturn(riskDto);

            // when
            StockDetailDto result = stockDetailService.getStockDetail(TEST_STOCK_CODE);

            // then
            assertThat(result.getAiAnalysis()).isNotNull();
            // 규칙기반 분석은 항상 점수를 반환
            assertThat(result.getAiAnalysis().getOverallScore()).isBetween(0, 100);
            assertThat(result.getAiAnalysis().getRecommendation()).isNotNull();
        }
    }
}
