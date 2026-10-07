package com.myplatform.backend.service;

import com.myplatform.backend.dto.RiskAnalysisDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.AlertHistory;
import com.myplatform.backend.entity.StockWatchlist;
import com.myplatform.backend.repository.AlertHistoryRepository;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.StockWatchlistRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관심종목 리스크 알림 — 새 위험만 알린다(2026-10-07 운영).
 *
 * <p>재현: SK하이닉스 관심종목에 대해 '🔴 리스크 감지 — 부정적 공시: …유상증자…' 텔레그램이 거래일 매시간 나갔다(10/6 8번, 10/7
 * 10:10·11:10 …). 공시 판정은 최근 3개월 공시를 보고 쿨다운은 1시간뿐이라, 위험 공시가 한 번 나오면 석 달 동안 매시간이었다.
 * 알림 이력은 24시간 뒤 지워지므로(InvestorSurgeService.cleanupExpiredAlerts) '공시 하나당 한 번'은 이력만으론 못 지킨다 —
 * 그래서 공시는 직전 거래일 이후 접수분(새 공시)만, 가격·수급 위험은 종류별로 하루 한 번.
 */
class WatchlistRiskAlertDedupTest {

    @Test
    @DisplayName("새 공시 = 직전 거래일 다음 날부터 오늘까지 접수 — 석 달 전 공시는 새 위험이 아니다")
    void newDisclosureWindow() {
        LocalDate today = LocalDate.of(2026, 10, 7), prev = LocalDate.of(2026, 10, 6);
        assertThat(WatchlistRiskMonitorService.isNewDisclosure("20261007", prev, today)).isTrue();
        assertThat(WatchlistRiskMonitorService.isNewDisclosure("20261006", prev, today)).isFalse();   // 어제 이미 봤다
        assertThat(WatchlistRiskMonitorService.isNewDisclosure("20260915", prev, today)).isFalse();
        // 월요일(10/12) — 직전 거래일 10/8(목, 10/9 한글날 휴장): 토요일 접수는 새 공시
        assertThat(WatchlistRiskMonitorService.isNewDisclosure("20261010", LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 12))).isTrue();
        assertThat(WatchlistRiskMonitorService.isNewDisclosure(null, prev, today)).isFalse();
        assertThat(WatchlistRiskMonitorService.isNewDisclosure("bad", prev, today)).isFalse();
    }

    @Test
    @DisplayName("알림 키 — 공시는 접수번호, 그 밖은 종류·날짜(이력 칸 50자 안)")
    void alertKeys() {
        LocalDate d = LocalDate.of(2026, 10, 7);
        WatchlistRiskMonitorService.RiskDetail dart = WatchlistRiskMonitorService.RiskDetail.builder()
                .type("DART_DANGER").sourceId("20261007000123").message("부정적 공시: 유상증자결정").build();
        WatchlistRiskMonitorService.RiskDetail drop = WatchlistRiskMonitorService.RiskDetail.builder()
                .type("PRICE_DROP").message("하락 -3.5%").build();
        assertThat(WatchlistRiskMonitorService.alertKey("000660", dart, d)).isEqualTo("000660_WLD_20261007000123");
        assertThat(WatchlistRiskMonitorService.alertKey("000660", drop, d)).isEqualTo("000660_WL_PRICE_DROP_1007");
        assertThat(WatchlistRiskMonitorService.alertKey("000660", drop, d).length()).isLessThanOrEqualTo(50);
    }

    @Test
    @DisplayName("재현: 석 달 전 위험 공시는 매 감시마다 다시 알리지 않고, 하락은 하루 한 번")
    void sameRiskIsNotRepeated() {
        StockWatchlistRepository watchlist = mock(StockWatchlistRepository.class);
        StockWatchlist w = new StockWatchlist();
        w.setStockCode("000660");
        w.setStockName("SK하이닉스");
        when(watchlist.findByIsActiveTrue()).thenReturn(List.of(w));

        StockPriceService prices = mock(StockPriceService.class);
        StockPriceDto p = new StockPriceDto();
        p.setCurrentPrice(new BigDecimal("1747000"));
        p.setChangeRate(new BigDecimal("-3.5"));
        when(prices.getStockPrices(anyList())).thenReturn(Map.of("000660", p));

        DartService dart = mock(DartService.class);
        when(dart.isAvailable()).thenReturn(true);
        RiskAnalysisDto.DartDisclosure old = RiskAnalysisDto.DartDisclosure.builder()
                .reportNm("[기재정정]주요사항보고서(유상증자결정)").rceptNo("20260915000777").rceptDt("20260915")
                .isDangerous(true).matchedKeyword("유상증자").build();
        when(dart.searchDisclosuresByStockCode(anyString(), any())).thenReturn(List.of(old));
        when(dart.filterDangerousDisclosures(anyList())).thenReturn(List.of(old));

        // 알림 이력 — 키와 보낸 시각. '1시간 뒤'는 저장 시각을 61분 앞당겨 흉내 낸다(서비스는 실제 시계를 쓴다)
        AlertHistoryRepository history = mock(AlertHistoryRepository.class);
        Map<String, java.time.LocalDateTime> sent = new java.util.HashMap<>();
        when(history.existsRecentAlert(anyString(), any())).thenAnswer(inv -> {
            java.time.LocalDateTime at = sent.get((String) inv.getArgument(0));
            return at != null && at.isAfter(inv.getArgument(1));
        });
        when(history.save(any())).thenAnswer(inv -> {
            AlertHistory h = inv.getArgument(0);
            sent.put(h.getAlertKey(), h.getSentAt());
            return h;
        });
        Runnable anHourLater = () -> sent.replaceAll((k, at) -> at.minusMinutes(61));

        TelegramNotificationService telegram = mock(TelegramNotificationService.class);
        MarketCalendarService calendar = new MarketCalendarService();
        WatchlistRiskMonitorService svc = new WatchlistRiskMonitorService(watchlist, history, prices, dart,
                mock(InvestorDailyTradeRepository.class), telegram, mock(SchedulerLockService.class), calendar);

        svc.monitorWatchlistRisks();   // 10:00 — 하락 -3.5% 는 오늘 처음이라 알림(석 달 전 공시는 새 위험 아님)
        svc.monitorWatchlistRisks();   // 10:10
        anHourLater.run();
        svc.monitorWatchlistRisks();   // 11:10 — 예전엔 1시간 쿨다운이 풀려 다시 나갔다
        anHourLater.run();
        svc.monitorWatchlistRisks();   // 12:10

        verify(telegram, times(1)).sendRisk(anyString());
        assertThat(sent.keySet()).noneMatch(k -> k.contains("_WLD_"));   // 옛 공시로는 알리지 않았다
    }

    @Test
    @DisplayName("새 위험 공시는 알린다 — 같은 날 다음 감시에선 되풀이하지 않는다")
    void newDisclosureAlertsOnce() {
        StockWatchlistRepository watchlist = mock(StockWatchlistRepository.class);
        StockWatchlist w = new StockWatchlist();
        w.setStockCode("000660");
        w.setStockName("SK하이닉스");
        when(watchlist.findByIsActiveTrue()).thenReturn(List.of(w));
        StockPriceService prices = mock(StockPriceService.class);
        when(prices.getStockPrices(anyList())).thenReturn(Map.of());

        String todayDt = com.myplatform.core.util.DateTimeUtil.kstNow().toLocalDate()
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        DartService dart = mock(DartService.class);
        when(dart.isAvailable()).thenReturn(true);
        RiskAnalysisDto.DartDisclosure fresh = RiskAnalysisDto.DartDisclosure.builder()
                .reportNm("주요사항보고서(유상증자결정)").rceptNo(todayDt + "000123").rceptDt(todayDt)
                .isDangerous(true).matchedKeyword("유상증자").build();
        when(dart.searchDisclosuresByStockCode(anyString(), any())).thenReturn(List.of(fresh));
        when(dart.filterDangerousDisclosures(anyList())).thenReturn(List.of(fresh));

        AlertHistoryRepository history = mock(AlertHistoryRepository.class);
        Map<String, java.time.LocalDateTime> sentAt = new java.util.HashMap<>();
        Set<String> sent = new HashSet<>();
        when(history.existsRecentAlert(anyString(), any())).thenAnswer(inv -> {
            java.time.LocalDateTime at = sentAt.get((String) inv.getArgument(0));
            return at != null && at.isAfter(inv.getArgument(1));
        });
        when(history.save(any())).thenAnswer(inv -> {
            AlertHistory h = inv.getArgument(0);
            sentAt.put(h.getAlertKey(), h.getSentAt());
            sent.add(h.getAlertKey());
            return h;
        });
        TelegramNotificationService telegram = mock(TelegramNotificationService.class);

        WatchlistRiskMonitorService svc = new WatchlistRiskMonitorService(watchlist, history, prices, dart,
                mock(InvestorDailyTradeRepository.class), telegram, mock(SchedulerLockService.class), new MarketCalendarService());
        svc.monitorWatchlistRisks();
        sentAt.replaceAll((k, at) -> at.minusMinutes(61));   // 1시간 뒤
        svc.monitorWatchlistRisks();

        verify(telegram, times(1)).sendRisk(anyString());
        assertThat(sent).contains("000660_WLD_" + todayDt + "000123");
    }

    @Test
    @DisplayName("위험이 없으면 보내지 않는다")
    void noRiskNoAlert() {
        StockWatchlistRepository watchlist = mock(StockWatchlistRepository.class);
        when(watchlist.findByIsActiveTrue()).thenReturn(List.of());
        TelegramNotificationService telegram = mock(TelegramNotificationService.class);
        new WatchlistRiskMonitorService(watchlist, mock(AlertHistoryRepository.class), mock(StockPriceService.class),
                mock(DartService.class), mock(InvestorDailyTradeRepository.class), telegram,
                mock(SchedulerLockService.class), new MarketCalendarService()).monitorWatchlistRisks();
        verify(telegram, never()).sendRisk(anyString());
    }
}
