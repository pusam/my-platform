package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.InvestorTradeDto;
import com.myplatform.backend.dto.MarketTimingDto;
import com.myplatform.backend.dto.ScalpingAnalysisDto;
import com.myplatform.backend.dto.StockDetailDto;
import com.myplatform.backend.repository.MarketDailyStatusRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-11-19(수능일, 정규장 10:00~16:30·마감 버퍼 16:40)에 틀리게 보일 화면 문구 세 가지 — 서버가 달력으로 판정해 싣는다(2026-11-10 리허설).
 *
 * <p>재현: (a) 허브 시간대 배너는 08:00 부터 '장 진행 중'인데 그날은 NXT 프리마켓도 없어 10:00 에 거래가 열린다. (b) 수급 패널 이름표는
 * 조회 시각 15:50 을 기준으로 '장중 가집계'와 '가집계(14:30 기준)'을 갈라, 그날 15:50~16:50 장중 값이 하루치처럼 보인다. (c) 종목 상세는
 * 15:40 뒤면 당일 외국인·기관 행을 받아들이고 '장 마감(15:40) 뒤 제공' 안내를 쓴다 — 그날 16:00 은 장중이다.
 */
class LateSessionLabelsTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate CSAT = LocalDate.of(2026, 11, 19);
    private static final LocalDate DAY_BEFORE = LocalDate.of(2026, 11, 18);

    private static Clock at(LocalDate day, int hour, int minute) {
        return Clock.fixed(LocalDateTime.of(day, LocalTime.of(hour, minute)).atZone(KST).toInstant(), KST);
    }

    // ---------- (a) 허브 시간대 배너의 시작 ----------

    private static String tradingStartsAt(LocalDate day) {
        MarketDailyStatusRepository repo = mock(MarketDailyStatusRepository.class);
        when(repo.findLatestAll()).thenReturn(List.of());
        MarketTimingService service = new MarketTimingService(repo, mock(TelegramNotificationService.class),
                mock(RedisCacheService.class), new MarketCalendarService(), mock(KoreaInvestmentService.class)) {
            @Override
            LocalDateTime now() {
                return LocalDateTime.of(day, LocalTime.of(8, 30));
            }
        };
        MarketTimingDto dto = service.getCurrentMarketTiming();
        return dto.getTradingStartsAt();
    }

    @Test
    @DisplayName("재현(a): 수능일 시장 상태 응답은 거래 시작을 10:00 으로 싣는다 — 평소는 08:00(NXT 프리마켓)")
    void tradingStartFollowsTheDay() {
        assertThat(tradingStartsAt(CSAT)).isEqualTo("10:00");
        assertThat(tradingStartsAt(DAY_BEFORE)).isEqualTo("08:00");
    }

    // ---------- (b) 수급 패널 장중 판정 ----------

    @SuppressWarnings("unchecked")
    private static InvestorTradeDto realtimeRowAt(LocalDate day, int hour, int minute) throws Exception {
        KoreaInvestmentService kis = mock(KoreaInvestmentService.class);
        JsonNode body = new ObjectMapper().readTree("""
                {"rt_cd":"0","output":[
                  {"mksc_shrn_iscd":"009150","hts_kor_isnm":"삼성전기","frgn_ntby_tr_pbmn":"134000","stck_prpr":"1675000","prdy_ctrt":"5.95"}
                ]}""");
        when(kis.getForeignInstitutionTotal("1", true, true)).thenReturn(body);
        InvestorTradeService service = new InvestorTradeService(
                mock(com.myplatform.backend.repository.InvestorDailyTradeRepository.class),
                mock(KisInvestorDataCollector.class), kis, mock(RedisCacheService.class),
                new MarketCalendarService(), mock(org.springframework.beans.factory.ObjectProvider.class));
        service.clock = at(day, hour, minute);
        return service.refreshSmartMoneyFromKis("FOREIGN", 10).get(0);
    }

    @Test
    @DisplayName("재현(b): 수능일 16:00 에 받은 순위 행은 장중 값이다 — 마감 10분 뒤(16:50)부터 장중이 아니다")
    void realtimeRowIsIntradayUntilTheLateClose() throws Exception {
        assertThat(realtimeRowAt(CSAT, 16, 0).getIntraday()).isTrue();
        assertThat(realtimeRowAt(CSAT, 16, 50).getIntraday()).isFalse();
    }

    @Test
    @DisplayName("평소 거래일은 종전 경계 그대로 — 15:49 장중, 15:50 부터 아니다")
    void ordinaryDayBoundaryUnchanged() throws Exception {
        assertThat(realtimeRowAt(DAY_BEFORE, 15, 49).getIntraday()).isTrue();
        assertThat(realtimeRowAt(DAY_BEFORE, 15, 50).getIntraday()).isFalse();
    }

    // ---------- (c) 종목 상세 당일 외국인·기관 ----------

    private static ScalpingAnalysisDto scalpingAt(LocalDate day, int hour, int minute) throws Exception {
        KoreaInvestmentService kis = mock(KoreaInvestmentService.class);
        String ymd = day.toString().replace("-", "");
        JsonNode investor = new ObjectMapper().readTree("{\"rt_cd\":\"0\",\"output\":[{\"stck_bsop_date\":\"" + ymd
                + "\",\"frgn_ntby_tr_pbmn\":\"12000\",\"orgn_ntby_tr_pbmn\":\"-3400\"}]}");
        when(kis.getInvestorTrading("005930")).thenReturn(investor);
        ScalpingAnalysisService service = new ScalpingAnalysisService(kis);
        service.marketCalendar = new MarketCalendarService();
        service.clock = at(day, hour, minute);
        return service.getScalpingAnalysis("005930");
    }

    @Test
    @DisplayName("재현(c): 수능일 16:00 엔 당일 외국인·기관 행을 쓰지 않는다 — 장중이다(KIS: 당일 데이터는 장 종료 후 제공)")
    void lateCloseDayIgnoresTodayInvestorRowUntilClose() throws Exception {
        assertThat(scalpingAt(CSAT, 16, 0).getForeignNetBuy()).isNull();
        assertThat(scalpingAt(CSAT, 16, 41).getForeignNetBuy()).isEqualByComparingTo(new BigDecimal("120"));
        assertThat(scalpingAt(DAY_BEFORE, 16, 0).getForeignNetBuy()).as("평소 거래일 16:00 은 종전대로 쓴다")
                .isEqualByComparingTo(new BigDecimal("120"));
    }

    private static StockDetailDto.SupplyDemand detailTimingAt(LocalDate day, int hour, int minute) {
        StockDetailService service = mock(StockDetailService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "marketCalendar", new MarketCalendarService());
        ReflectionTestUtils.setField(service, "clock", at(day, hour, minute));
        StockDetailDto.SupplyDemand supply = new StockDetailDto.SupplyDemand();
        service.applyInvestorDataTiming(supply, false);
        return supply;
    }

    @Test
    @DisplayName("재현(c): 수능일 16:00 엔 당일 집계가 아직 나올 때가 아니고, 안내 시각은 16:40 이다")
    void detailNoteFollowsTheLateClose() {
        StockDetailDto.SupplyDemand csat = detailTimingAt(CSAT, 16, 0);
        assertThat(csat.getInvestorDataExpected()).isFalse();
        assertThat(csat.getInvestorDataReadyAt()).isEqualTo("16:40");

        StockDetailDto.SupplyDemand ordinary = detailTimingAt(DAY_BEFORE, 16, 0);
        assertThat(ordinary.getInvestorDataExpected()).isTrue();
        assertThat(ordinary.getInvestorDataReadyAt()).isEqualTo("15:40");
    }
}
