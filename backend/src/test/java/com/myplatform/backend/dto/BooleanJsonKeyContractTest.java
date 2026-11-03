package com.myplatform.backend.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원시 {@code boolean isX} 칸은 JSON 에서 {@code x} 로 나간다 — 화면은 그 이름으로 읽는다(2026-10-19).
 *
 * <p>Lombok 은 원시 boolean {@code isBothSelling} 의 getter 를 {@code isBothSelling()} 으로 만들고, Jackson 은 거기서 'is' 를 떼어
 * 속성 이름을 {@code bothSelling} 으로 정한다(래퍼 {@code Boolean isX} 는 {@code getIsX()} → {@code isX} 로 다르다). 화면 다섯 곳이
 * {@code isX} 로 읽어 늘 undefined 였다 — 종목 진단 '동반 순매수·순매도' 문구는 한 번도 안 보였고, 트레이딩 지표의 VWAP
 * '신뢰도 낮음' 경고는 늘 떴고, 나스닥 선물 '매수 보류' 경고는 한 번도 안 떴다. 화면 테스트가 실제 응답에 없는 {@code isX} 로
 * 가짜 데이터를 만들어 통과하고 있었다. 이 테스트는 실제 이름을 고정한다 — 바꾸면 화면도 같이 바꿀 것.
 */
class BooleanJsonKeyContractTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    @DisplayName("종목 진단 수급: bothBuying·bothSelling·foreignBuying·institutionBuying")
    void supplyDemandKeys() throws Exception {
        StockDiagnosisDto.SupplyDemandDto dto = new StockDiagnosisDto.SupplyDemandDto();
        dto.setBothSelling(true);
        dto.setBothBuying(false);
        String json = mapper.writeValueAsString(dto);
        assertThat(json).contains("\"bothSelling\":true", "\"bothBuying\":false", "\"foreignBuying\":", "\"institutionBuying\":")
                .doesNotContain("\"isBothSelling\"", "\"isBothBuying\"");
    }

    @Test
    @DisplayName("동종업계 비교 current · 공시 dangerous · 나스닥 선물 tradingHalt · VWAP reliable")
    void otherKeys() throws Exception {
        StockDetailDto.PeerComparison peer = new StockDetailDto.PeerComparison();
        peer.setCurrent(true);
        RiskAnalysisDto.DartDisclosure disclosure = new RiskAnalysisDto.DartDisclosure();
        disclosure.setDangerous(true);
        TradingIndicatorDto.NasdaqFuturesResult nasdaq = new TradingIndicatorDto.NasdaqFuturesResult();
        nasdaq.setTradingHalt(true);
        TradingIndicatorDto.VwapResult vwap = new TradingIndicatorDto.VwapResult();
        vwap.setReliable(true);

        assertThat(mapper.writeValueAsString(peer)).contains("\"current\":true").doesNotContain("\"isCurrent\"");
        assertThat(mapper.writeValueAsString(disclosure)).contains("\"dangerous\":true").doesNotContain("\"isDangerous\"");
        assertThat(mapper.writeValueAsString(nasdaq)).contains("\"tradingHalt\":true").doesNotContain("\"isTradingHalt\"");
        assertThat(mapper.writeValueAsString(vwap)).contains("\"reliable\":true").doesNotContain("\"isReliable\"");
    }
}
