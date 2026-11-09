package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myplatform.backend.dto.InvestorSurgeDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.repository.AlertHistoryRepository;
import com.myplatform.backend.repository.InvestorIntradaySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 장중 수급 급증 카드의 '현재가'는 실시간일 때만(2026-11-10).
 *
 * <p>재현: 11/10 08:18 '오늘' 탭 — 효성중공업 카드가 '현재가 1,510,000원 · −4.25%'였는데 그건 11/09 19:50 스냅샷 가격이었다
 * (같은 시각 시장 탭은 +6.1%). 시세 캐시가 비면 스냅샷 가격을 그대로 두는데 그 사실을 실어 보내지 않아 화면이 '현재가'라 불렀다.
 */
class InvestorSurgePriceRealtimeTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private StockPriceService prices;
    private InvestorSurgeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        prices = mock(StockPriceService.class);
        Clock clock = Clock.fixed(LocalDateTime.of(2026, 11, 10, 8, 18).atZone(KST).toInstant(), KST);
        service = new InvestorSurgeService(mock(InvestorIntradaySnapshotRepository.class), mock(AlertHistoryRepository.class),
                mock(KoreaInvestmentService.class), mock(TelegramNotificationService.class), prices,
                mock(RedisCacheService.class), mock(SchedulerLockService.class), mock(ObjectProvider.class), clock,
                new MarketCalendarService());
    }

    private static InvestorSurgeDto snapshotRow(String code, String price, String rate) {
        return InvestorSurgeDto.builder().stockCode(code).investorType("FOREIGN")
                .snapshotDate(LocalDate.of(2026, 11, 9)).snapshotTime(LocalTime.of(19, 50))
                .currentPrice(new BigDecimal(price)).changeRate(new BigDecimal(rate)).build();
    }

    @Test
    @DisplayName("재현: 캐시에 없는 종목은 스냅샷 가격이 남고 '실시간 아님'으로 표시된다 — 있는 종목만 실시간")
    void flagsWhetherThePriceIsRealtime() throws Exception {
        InvestorSurgeDto cached = snapshotRow("005930", "100000", "-1.00");
        InvestorSurgeDto missed = snapshotRow("298040", "1510000", "-4.25");
        StockPriceDto live = new StockPriceDto();
        live.setCurrentPrice(new BigDecimal("102000"));
        live.setChangeRate(new BigDecimal("1.50"));
        when(prices.getStockPricesFromCacheOnly(anyList())).thenReturn(Map.of("005930", live));

        Map<String, List<InvestorSurgeDto>> all = Map.of("FOREIGN", new ArrayList<>(List.of(cached, missed)));
        ReflectionTestUtils.invokeMethod(service, "enrichWithRealTimePrices", all);

        assertThat(cached.isPriceRealtime()).isTrue();
        assertThat(cached.getCurrentPrice()).isEqualByComparingTo("102000");
        assertThat(missed.isPriceRealtime()).isFalse();                       // 고치기 전엔 이 사실을 실어 보내지 않았다
        assertThat(missed.getCurrentPrice()).isEqualByComparingTo("1510000"); // 스냅샷 가격 유지(지어내지 않는다)

        // 화면이 읽는 이름 — 원시 boolean 은 'is' 를 뗀 이름으로 나간다(BooleanJsonKeyContractTest 와 같은 규칙)
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(missed);
        assertThat(json).contains("\"priceRealtime\":false");
    }
}
