package com.myplatform.backend.service;

import com.myplatform.backend.dto.RiskAnalysisDto.DartDisclosure;
import com.myplatform.backend.repository.BotTradingPositionRepository;
import com.myplatform.backend.repository.StockWatchlistRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 보유·관심 종목 중대 공시 감시 — 종목코드로 조회하고, 확인하지 못한 종목은 '알릴 것 없음(0)'과 구분한다(2026-10-03).
 *
 * <p>예전엔 이름만으로 찾아, 이름이 매핑되지 않으면 KOSPI 한정 최근 100건 전체검색으로 흘러 KOSDAQ 보유 종목의 공시가 조용히 빠졌다.
 */
class DartDisclosureMonitorLookupTest {

    private final DartService dart = mock(DartService.class);
    private final TelegramNotificationService telegram = mock(TelegramNotificationService.class);
    private final RedisCacheService redis = mock(RedisCacheService.class);

    private final DartDisclosureMonitorService monitor = new DartDisclosureMonitorService(
            dart, mock(RealTradeService.class), mock(KoreaInvestmentService.class), telegram, redis,
            mock(SchedulerLockService.class), mock(StockWatchlistRepository.class), mock(BotTradingPositionRepository.class));

    @Test
    @DisplayName("재현: 코드로 조회한다 — 이름 단독 경로(KOSPI 한정 폴백)를 쓰지 않는다")
    void looksUpByCode() {
        DartDisclosure d = DartDisclosure.builder().rceptNo("20261002000123").reportNm("상장적격성 실질심사 대상").build();
        d.setDangerous(true);
        when(dart.searchDisclosuresOrNull("086520", "에코프로")).thenReturn(List.of(d));

        int sent = monitor.processStock("에코프로", "086520");

        assertThat(sent).isEqualTo(1);
        verify(telegram).sendRisk(anyString());
        verify(dart, never()).searchDisclosuresByName(anyString());
    }

    @Test
    @DisplayName("공시를 확인하지 못하면 -1(미확인) — 0(새 공시 없음)과 다르다")
    void uncheckedIsMinusOne() {
        when(dart.searchDisclosuresOrNull("123456", "모르는회사")).thenReturn(null);
        assertThat(monitor.processStock("모르는회사", "123456")).isEqualTo(-1);
        verify(telegram, never()).sendRisk(anyString());
    }
}
