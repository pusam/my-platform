package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.StockDetailDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 종목 상세 배당수익률·증권사 목표주가. 읽던 네이버 메인 페이지가 stock.naver.com 으로 옮겨 값이 사라졌는데 화면은 카드를 숨겨 아무도 몰랐다.
 * 응답은 2026-11-05 삼성전자 실제 값이다.
 */
class NaverConsensusServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SAMSUNG = """
            {"itemCode":"005930","stockName":"삼성전자",
             "totalInfos":[{"code":"per","key":"PER","value":"8.98배"},
                           {"code":"dividendYieldRatio","key":"배당수익률","value":"0.83%"},
                           {"code":"dividend","key":"주당배당금","value":"1,668원"}],
             "consensusInfo":{"itemCode":"005930","createDate":"2026-11-04","recommMean":"4.00","priceTargetMean":"472,381"}}
            """;

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    @DisplayName("삼성전자 실제 응답 — 배당수익률 0.83%, 목표주가 472,381원, 기준일 11/04")
    void parsesRealResponse() throws Exception {
        NaverConsensusService.Snapshot s = NaverConsensusService.parse(json(SAMSUNG));
        assertThat(s.dividendYield()).isEqualByComparingTo("0.83");
        assertThat(s.targetPrice()).isEqualByComparingTo("472381");
        assertThat(s.asOf()).isEqualTo(LocalDate.of(2026, 11, 4));
    }

    @Test
    @DisplayName("무배당·표시 없음은 모름이다 — 0% 로 보이지 않게")
    void noDividendIsNull() throws Exception {
        for (String v : new String[]{"N/A", "-", "0.00%", ""}) {
            JsonNode root = json("{\"totalInfos\":[{\"code\":\"dividendYieldRatio\",\"value\":\"" + v + "\"}]}");
            assertThat(NaverConsensusService.parse(root).dividendYield()).as(v).isNull();
        }
    }

    @Test
    @DisplayName("목표주가가 없거나 말이 안 되는 값이면 모름이다")
    void missingOrAbsurdTargetIsNull() throws Exception {
        assertThat(NaverConsensusService.parse(json("{\"totalInfos\":[]}")).targetPrice()).isNull();
        assertThat(NaverConsensusService.parse(json("{\"consensusInfo\":{\"priceTargetMean\":\"N/A\"}}")).targetPrice()).isNull();
        assertThat(NaverConsensusService.parse(json("{\"consensusInfo\":{\"priceTargetMean\":\"4.00\"}}")).targetPrice()).isNull();
    }

    @Test
    @DisplayName("배당수익률 범위 밖은 쓰지 않는다 — 30% 이상은 파싱 오류로 본다(종전 규칙)")
    void absurdYieldIsNull() throws Exception {
        JsonNode root = json("{\"totalInfos\":[{\"code\":\"dividendYieldRatio\",\"value\":\"35.00%\"}]}");
        assertThat(NaverConsensusService.parse(root).dividendYield()).isNull();
    }

    @Test
    @DisplayName("같은 종목은 한 시간 다시 묻지 않는다")
    void cachesSuccess() {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.getForObject(anyString(), eq(String.class))).thenReturn(SAMSUNG);
        NaverConsensusService service = new NaverConsensusService(rest, MAPPER);

        assertThat(service.fetch("005930").targetPrice()).isEqualByComparingTo(new BigDecimal("472381"));
        assertThat(service.fetch("005930").targetPrice()).isEqualByComparingTo(new BigDecimal("472381"));

        verify(rest, times(1)).getForObject(anyString(), eq(String.class));
    }

    @Test
    @DisplayName("조회 실패는 null 이고 캐시하지 않는다 — 다음 조회에서 다시 묻는다")
    void failureIsNotCached() {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.getForObject(anyString(), eq(String.class))).thenThrow(new RestClientException("409"));
        NaverConsensusService service = new NaverConsensusService(rest, MAPPER);

        assertThat(service.fetch("005930")).isNull();
        assertThat(service.fetch("005930")).isNull();

        verify(rest, times(2)).getForObject(anyString(), eq(String.class));
    }

    @Test
    @DisplayName("상승여력은 지금 시세로 잰다 — 목표주가를 모르면 칸을 비운다")
    void upsideUsesCurrentPrice() {
        StockDetailDto.AiAnalysis ai = new StockDetailDto.AiAnalysis();
        StockDetailDto.PriceInfo price = new StockDetailDto.PriceInfo();
        price.setCurrentPrice(new BigDecimal("200000"));

        StockDetailService.applyConsensusTarget(ai, new NaverConsensusService.Snapshot(null, null, null), price);
        assertThat(ai.getConsensusTargetPrice()).isNull();

        StockDetailService.applyConsensusTarget(ai,
                new NaverConsensusService.Snapshot(null, new BigDecimal("472381"), LocalDate.of(2026, 11, 4)), price);
        assertThat(ai.getConsensusTargetPrice()).isEqualByComparingTo("472381");
        assertThat(ai.getTargetUpside()).isEqualByComparingTo("136.2");
        assertThat(ai.getConsensusSource()).isEqualTo("네이버 증권 컨센서스 · 11/04 기준");
    }

    @Test
    @DisplayName("이름표는 기준일을 적는다")
    void sourceLabelHasDate() throws Exception {
        assertThat(NaverConsensusService.parse(json(SAMSUNG)).sourceLabel()).isEqualTo("네이버 증권 컨센서스 · 11/04 기준");
    }
}
