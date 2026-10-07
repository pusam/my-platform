package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.InvestorTradeDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 시장 탭 수급 패널의 '장중 잠정' 표시 근거 — KIS 순위로 만든 행은 조회 시각(asOf)을 갖는다(2026-10-07 화면 점검).
 *
 * <p>재현: 10:29 시장 탭 수급 패널이 '외국인 상위 10 +3,930억 · 10.07 기준'이라 했다 — 그 합은 그 시각까지의 장중 잠정
 * 집계(KIS 순위, 워머 30초)인데 하루치처럼 읽혔고, 같은 패널의 연속 순매수 종목은 10.06 장 마감까지의 확정치였다. 10시 전엔
 * 기관 순위가 비어 기관 줄만 DB(전일 확정)로 떨어져 한 줄은 오늘 장중·한 줄은 어제가 한 이름표 아래 섞였다.
 * 행이 언제 받은 값인지 알아야 줄마다 정직하게 적을 수 있다 — DB 확정치는 asOf 가 없다(null).
 */
class SmartMoneyRealtimeAsOfTest {

    @Test
    @DisplayName("재현: KIS 순위로 만든 행은 조회 시각(asOf)과 오늘 거래일을 갖는다")
    @SuppressWarnings("unchecked")
    void realtimeRowsCarryFetchTime() throws Exception {
        KoreaInvestmentService kis = mock(KoreaInvestmentService.class);
        JsonNode body = new ObjectMapper().readTree("""
                {"rt_cd":"0","output":[
                  {"mksc_shrn_iscd":"009150","hts_kor_isnm":"삼성전기","frgn_ntby_tr_pbmn":"134000","stck_prpr":"1675000","prdy_ctrt":"5.95"}
                ]}""");
        when(kis.getForeignInstitutionTotal("1", true, true)).thenReturn(body);
        InvestorTradeService service = new InvestorTradeService(
                mock(com.myplatform.backend.repository.InvestorDailyTradeRepository.class),
                mock(KisInvestorDataCollector.class), kis, mock(RedisCacheService.class),
                mock(MarketCalendarService.class), mock(org.springframework.beans.factory.ObjectProvider.class));

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        List<InvestorTradeDto> rows = service.refreshSmartMoneyFromKis("FOREIGN", 10);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTradeDate()).isEqualTo(LocalDate.now());
        assertThat(rows.get(0).getAsOf()).isNotNull().isAfterOrEqualTo(before);
        assertThat(rows.get(0).getNetBuyAmount()).isEqualByComparingTo("1340.00");
    }
}
