package com.myplatform.backend.controller;

import com.myplatform.backend.dto.StockDetailDto;
import com.myplatform.backend.dto.StockDetailDto.*;
import com.myplatform.backend.service.StockDetailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * StockDetailController 단위 테스트 (Standalone MockMvc)
 *
 * 검증 포인트:
 * 1. /summary, /quick, /heavy 엔드포인트 응답 스펙
 * 2. 서비스 예외 시 500 + 에러 메시지
 * 3. 정상 응답 JSON 구조 (success, data, elapsed)
 */
@ExtendWith(MockitoExtension.class)
class StockDetailControllerTest {

    private MockMvc mockMvc;

    @Mock
    private StockDetailService stockDetailService;

    @InjectMocks
    private StockDetailController stockDetailController;

    private static final String STOCK_CODE = "005930";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(stockDetailController).build();
    }

    private StockDetailDto buildSampleDto() {
        return StockDetailDto.builder()
                .stockCode(STOCK_CODE)
                .stockName("삼성전자")
                .fetchedAt(LocalDateTime.now())
                .price(PriceInfo.builder()
                        .currentPrice(new BigDecimal("71000"))
                        .changePrice(new BigDecimal("1000"))
                        .changeRate(new BigDecimal("1.43"))
                        .tradingVolume(10000000L)
                        .build())
                .build();
    }

    // ========== /summary ==========

    @Nested
    @DisplayName("GET /api/stock/{code}/summary")
    class SummaryTests {

        @Test
        @DisplayName("정상 조회 → 200 + success:true + 데이터 포함")
        void success_returns200WithData() throws Exception {
            when(stockDetailService.getStockDetail(STOCK_CODE)).thenReturn(buildSampleDto());

            mockMvc.perform(get("/api/stock/{stockCode}/summary", STOCK_CODE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.stockCode").value(STOCK_CODE))
                    .andExpect(jsonPath("$.data.stockName").value("삼성전자"))
                    .andExpect(jsonPath("$.data.price.currentPrice").value(71000))
                    .andExpect(jsonPath("$.elapsed").exists());
        }

        @Test
        @DisplayName("서비스 예외 → 500 + success:false + 에러 메시지")
        void serviceException_returns500() throws Exception {
            when(stockDetailService.getStockDetail(STOCK_CODE))
                    .thenThrow(new RuntimeException("KIS API 장애"));

            mockMvc.perform(get("/api/stock/{stockCode}/summary", STOCK_CODE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").exists());
        }
    }

    // ========== /quick ==========

    @Nested
    @DisplayName("GET /api/stock/{code}/quick")
    class QuickTests {

        @Test
        @DisplayName("Quick 조회 → 200 + 시세 포함")
        void quickSuccess_returnsPriceData() throws Exception {
            when(stockDetailService.getStockDetailQuick(STOCK_CODE)).thenReturn(buildSampleDto());

            mockMvc.perform(get("/api/stock/{stockCode}/quick", STOCK_CODE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.price.currentPrice").value(71000));
        }

        @Test
        @DisplayName("Quick 예외 → 500")
        void quickException_returns500() throws Exception {
            when(stockDetailService.getStockDetailQuick(STOCK_CODE))
                    .thenThrow(new RuntimeException("timeout"));

            mockMvc.perform(get("/api/stock/{stockCode}/quick", STOCK_CODE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ========== /heavy ==========

    @Nested
    @DisplayName("GET /api/stock/{code}/heavy")
    class HeavyTests {

        @Test
        @DisplayName("Heavy 조회 → 200 + 리스크/AI 포함")
        void heavySuccess_returnsRiskAndAi() throws Exception {
            Map<String, Object> heavyData = new HashMap<>();
            heavyData.put("risk", RiskInfo.builder()
                    .riskScore(25).riskStatus("SAFE").build());
            heavyData.put("aiAnalysis", AiAnalysis.builder()
                    .overallScore(72).recommendation("TRADING_BUY").build());
            heavyData.put("peerComparisons", Collections.emptyList());

            when(stockDetailService.getStockDetailHeavy(STOCK_CODE)).thenReturn(heavyData);

            mockMvc.perform(get("/api/stock/{stockCode}/heavy", STOCK_CODE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.risk.riskScore").value(25))
                    .andExpect(jsonPath("$.data.aiAnalysis.overallScore").value(72));
        }
    }
}
