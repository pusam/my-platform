package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 모멘텀 스크리너(AI 스캘핑 후보) — 시가총액을 거래량순위 응답에서 계산한다(2026-10-02).
 *
 * <p><b>무엇이 틀렸나</b>: 종목마다 주식기본조회(CTPF1002R)를 불러 {@code hts_avls} 를 읽었는데, 공식 샘플
 * (chk_search_stock_info.py)의 응답 필드에 <b>{@code hts_avls} 가 없다</b>. 그래서 시가총액이 늘 '모름' → 거래량 상위 30종목을
 * 전부 스킵 → 0건(운영 로그: 매 회차 "거래량 상위 종목 30건 조회됨 … 최종 0건") → AI 스캘핑이 30일 164회 전부
 * 네이버 시총 상위 대형주로 채워졌고, 그 대형주가 종합추천에서 'AI전략1~3위' 태그를 받았다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuantScreenerMomentumMarketCapTest {

    @Mock private StockFinancialDataRepository stockFinancialDataRepository;
    @Mock private TelegramNotificationService telegramNotificationService;
    @Mock private KoreaInvestmentService koreaInvestmentService;
    @Mock private StockPriceService stockPriceService;
    @Mock private StockStatusService stockStatusService;

    @InjectMocks private QuantScreenerService service;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 거래량순위 응답 — 필드명은 공식 샘플 chk_volume_rank.py 의 COLUMN_MAPPING 그대로. */
    private static JsonNode volumeRank() throws Exception {
        return MAPPER.readTree("""
            {"rt_cd":"0","msg1":"정상처리 되었습니다.","output":[
              {"hts_kor_isnm":"삼성전자","mksc_shrn_iscd":"005930","stck_prpr":"89000","prdy_ctrt":"1.25",
               "acml_vol":"21000000","lstn_stcn":"5919637922","vol_inrt":"45.10"},
              {"hts_kor_isnm":"작은회사","mksc_shrn_iscd":"123450","stck_prpr":"2500","prdy_ctrt":"3.00",
               "acml_vol":"9000000","lstn_stcn":"20000000","vol_inrt":"300.00"},
              {"hts_kor_isnm":"주식수없음","mksc_shrn_iscd":"222220","stck_prpr":"50000","prdy_ctrt":"2.00",
               "acml_vol":"5000000","vol_inrt":"80.00"}
            ]}""");
    }

    /** 운영에서 실제로 오던 주식기본조회 모양 — hts_avls 가 없다. */
    private static JsonNode stockInfoWithoutMarketCap() throws Exception {
        return MAPPER.readTree("""
            {"rt_cd":"0","output":{"pdno":"00000A005930","prdt_abrv_name":"삼성전자","lstg_stqt":"5919637922","thdt_clpr":"89000"}}""");
    }

    @Test
    @DisplayName("재현: 거래량 상위에 시총 5조 원대 종목이 있어도 0건이던 것 — 이제 상장 주식수×현재가로 통과")
    void largeCapFromVolumeRankPasses() throws Exception {
        when(koreaInvestmentService.getVolumeRankStocks()).thenReturn(volumeRank());
        when(koreaInvestmentService.getStockInfo(anyString())).thenReturn(stockInfoWithoutMarketCap());
        when(stockStatusService.isActive(anyString())).thenReturn(true);

        List<ScreenerResultDto> result = service.getMomentumStocks(10);

        assertThat(result).extracting(ScreenerResultDto::getStockCode).containsExactly("005930");
        assertThat(result.get(0).getMarketCap()).isEqualByComparingTo("5268478");
        // 종목마다 주식기본조회를 부르지 않는다(필드도 없고, 30회 호출만 늘린다)
        verify(koreaInvestmentService, never()).getStockInfo(anyString());
    }

    @Test
    @DisplayName("시총 1,000억 미만(작은회사 500억)·주식수 모름은 스킵 — 모르는 시총을 짐작하지 않는다")
    void smallOrUnknownCapIsSkipped() throws Exception {
        when(koreaInvestmentService.getVolumeRankStocks()).thenReturn(volumeRank());
        when(stockStatusService.isActive(anyString())).thenReturn(true);

        assertThat(service.getMomentumStocks(10)).extracting(ScreenerResultDto::getStockCode)
                .doesNotContain("123450", "222220");
    }

    @Test
    @DisplayName("거래정지·상폐 게이트를 거친다 — 새 스크리너 소비처 규칙(§4)")
    void haltedStockIsExcluded() throws Exception {
        when(koreaInvestmentService.getVolumeRankStocks()).thenReturn(volumeRank());
        when(stockStatusService.isActive(anyString())).thenReturn(true);
        when(stockStatusService.isActive("005930")).thenReturn(false);

        assertThat(service.getMomentumStocks(10)).isEmpty();
    }

    @Test
    @DisplayName("시가총액 = 상장 주식수 × 현재가 ÷ 1억 — 하나라도 없거나 0 이하면 모름(null)")
    void marketCapFromShares() {
        assertThat(QuantScreenerService.marketCapEokFromShares(new BigDecimal("5919637922"), new BigDecimal("89000")))
                .isEqualByComparingTo("5268478");
        assertThat(QuantScreenerService.marketCapEokFromShares(null, new BigDecimal("89000"))).isNull();
        assertThat(QuantScreenerService.marketCapEokFromShares(new BigDecimal("100"), null)).isNull();
        assertThat(QuantScreenerService.marketCapEokFromShares(BigDecimal.ZERO, new BigDecimal("89000"))).isNull();
    }
}
