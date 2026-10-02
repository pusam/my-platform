package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.MarketTimingDto;
import com.myplatform.backend.entity.MarketDailyStatus;
import com.myplatform.backend.repository.MarketDailyStatusRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 시장 폭 수집·ADR 판단 보류 — {@link MarketTimingService}(2026-10-02).
 *
 * <p>① 장 마감 전엔 저장하지 않는다 ② 등락 수를 못 받은 시장은 행을 만들지도 고치지도 않는다(0 저장 금지)
 * ③ 화면용 ADR 은 저장값이 아니라 등락 수에서 다시 계산하고, 유효일이 모자라면 판단 보류를 문장으로 말한다.
 */
class MarketTimingBreadthCollectionTest {

    private static final ObjectMapper M = new ObjectMapper();

    private final MarketDailyStatusRepository repo = mock(MarketDailyStatusRepository.class);
    private final KoreaInvestmentService kis = mock(KoreaInvestmentService.class);
    private final RedisCacheService redis = mock(RedisCacheService.class);

    /** 네이버 지수 호출은 고정값으로 — 단위 테스트가 네트워크를 타지 않게. */
    private final MarketTimingService service = new MarketTimingService(repo, mock(TelegramNotificationService.class),
            redis, new MarketCalendarService(), kis) {
        @Override
        BigDecimal[] crawlIndexInfo(String marketType) {
            return new BigDecimal[]{new BigDecimal("6985.62"), new BigDecimal("0.20"), null};
        }

        @Override
        BigDecimal[] fetchIndexFromNaverApi(String marketType) {
            return new BigDecimal[]{new BigDecimal("6985.62"), new BigDecimal("0.20"), null};
        }
    };

    private static JsonNode kisBreadth(int adv, int dec, int unch) throws Exception {
        return M.readTree("{\"rt_cd\":\"0\",\"output\":{\"ascn_issu_cnt\":\"" + adv + "\",\"down_issu_cnt\":\"" + dec
                + "\",\"stnr_issu_cnt\":\"" + unch + "\",\"uplm_issu_cnt\":\"2\",\"lslm_issu_cnt\":\"0\"}}");
    }

    @Test
    @DisplayName("장 마감(15:40) 전이면 아무것도 저장하지 않고 이유를 예외로 알린다 — 수동 버튼이 '완료'로 속지 않게")
    void refusesBeforeClose() {
        assertThatThrownBy(() -> service.collectMarketData(LocalTime.of(11, 30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("장 마감");

        verifyNoInteractions(repo, kis);
    }

    @Test
    @DisplayName("한 시장의 등락 수를 못 받으면 그 시장 행은 만들지 않는다(0 저장 금지) — 실패는 예외로 드러낸다")
    void failedMarketIsNotSavedAsZero() throws Exception {
        when(kis.getIndexPrice("0001")).thenReturn(null);                     // 코스피 실패
        when(kis.getIndexPrice("1001")).thenReturn(kisBreadth(700, 900, 80)); // 코스닥 정상

        assertThatThrownBy(() -> service.collectMarketData(LocalTime.of(16, 30, 20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KOSPI");

        ArgumentCaptor<MarketDailyStatus> saved = ArgumentCaptor.forClass(MarketDailyStatus.class);
        verify(repo, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(r -> assertThat(r.getMarketType()).isEqualTo("KOSDAQ"));
        verify(repo, never()).findByMarketTypeAndTradeDate(eq("KOSPI"), any());
    }

    @Test
    @DisplayName("마감 뒤 정상 응답이면 등락 수·당일 등락비·지수를 저장한다")
    void savesSettledCounts() throws Exception {
        when(kis.getIndexPrice(anyString())).thenReturn(kisBreadth(528, 1302, 74));

        service.collectMarketData(LocalTime.of(16, 30, 20));

        ArgumentCaptor<MarketDailyStatus> saved = ArgumentCaptor.forClass(MarketDailyStatus.class);
        verify(repo, atLeastOnce()).save(saved.capture());
        MarketDailyStatus kospi = saved.getAllValues().stream()
                .filter(r -> "KOSPI".equals(r.getMarketType())).findFirst().orElseThrow();
        assertThat(kospi.getAdvancingCount()).isEqualTo(528);
        assertThat(kospi.getDecliningCount()).isEqualTo(1302);
        assertThat(kospi.getUnchangedCount()).isEqualTo(74);
        assertThat(kospi.getTotalCount()).isEqualTo(1904);
        assertThat(kospi.getDailyRatio()).isEqualByComparingTo("41.00");   // 528/1302 = 0.41 → ×100
        assertThat(kospi.getIndexClose()).isEqualByComparingTo("6985.62");
    }

    @Test
    @DisplayName("화면용 ADR 은 저장된 adr20(85.0)을 믿지 않는다 — 유효 6일이면 판단 보류와 그 이유를 말한다")
    void currentTimingSuspendsAdrWithReason() {
        LocalDate today = LocalDate.now();
        MarketDailyStatus kospiToday = new MarketDailyStatus();
        kospiToday.setMarketType("KOSPI");
        kospiToday.setTradeDate(today);
        kospiToday.setAdvancingCount(0);
        kospiToday.setDecliningCount(0);
        kospiToday.setUnchangedCount(0);
        kospiToday.setAdr20(new BigDecimal("86.60"));   // 크롤 사망 기간에 저장된 '정상처럼 보이는' 값
        kospiToday.setIndexClose(new BigDecimal("6985.62"));
        kospiToday.setIndexChangeRate(new BigDecimal("0.20"));
        MarketDailyStatus kosdaqToday = new MarketDailyStatus();
        kosdaqToday.setMarketType("KOSDAQ");
        kosdaqToday.setTradeDate(today);
        kosdaqToday.setAdvancingCount(0);
        kosdaqToday.setDecliningCount(0);
        kosdaqToday.setUnchangedCount(0);
        kosdaqToday.setAdr20(new BigDecimal("83.39"));
        kosdaqToday.setIndexClose(new BigDecimal("894.85"));
        kosdaqToday.setIndexChangeRate(new BigDecimal("0.06"));
        when(repo.findLatestAll()).thenReturn(List.of(kospiToday, kosdaqToday));

        List<MarketDailyStatus> window = new ArrayList<>();
        for (int i = 0; i < 14; i++) window.add(zero(today.minusDays(i)));
        for (int i = 0; i < 6; i++) window.add(counted(today.minusDays(20 + i), 1000, 1150));
        when(repo.findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc(anyString(), any(), any())).thenReturn(window);

        MarketTimingDto t = service.getCurrentMarketTiming();

        assertThat(t.getCombinedAdr()).isNull();
        assertThat(t.getOverallCondition()).isNull();
        assertThat(t.getKospi().getAdr20()).isNull();
        assertThat(t.getKospi().getAdrValidDays()).isEqualTo(6);
        assertThat(t.getKospi().getAdvancingCount()).isNull();   // 0/0/0 행은 '모름'으로 내보낸다
        assertThat(t.getDiagnosis()).contains("ADR 판단을 보류합니다").contains("코스피 6일");
        assertThat(t.getStrategy()).contains("ADR 기반 판단은 쓰지 않습니다");
    }

    @Test
    @DisplayName("관제실 규칙 입력 — 마지막으로 등락 수가 있는 날과 창 유효일")
    void breadthHealthReportsLatestCountedDay() {
        LocalDate today = LocalDate.of(2026, 10, 2);
        List<MarketDailyStatus> rows = new ArrayList<>();
        for (int i = 0; i < 14; i++) rows.add(zero(today.minusDays(i)));
        rows.add(counted(LocalDate.of(2026, 9, 10), 991, 1302));
        when(repo.findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc(eq("KOSPI"), any(), eq(today))).thenReturn(rows);

        MarketTimingService.BreadthHealth h = service.breadthHealth(today);

        assertThat(h.latestCountedDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(h.validDays()).isEqualTo(1);
    }

    private static MarketDailyStatus zero(LocalDate d) {
        return counted(d, 0, 0);
    }

    private static MarketDailyStatus counted(LocalDate d, int adv, int dec) {
        MarketDailyStatus r = new MarketDailyStatus();
        r.setMarketType("KOSPI");
        r.setTradeDate(d);
        r.setAdvancingCount(adv);
        r.setDecliningCount(dec);
        r.setUnchangedCount(0);
        return r;
    }
}
