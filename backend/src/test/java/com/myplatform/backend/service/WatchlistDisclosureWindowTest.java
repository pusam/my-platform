package com.myplatform.backend.service;

import com.myplatform.backend.dto.RiskAnalysisDto;
import com.myplatform.backend.entity.StockWatchlist;
import com.myplatform.backend.repository.AlertHistoryRepository;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.StockWatchlistRepository;
import com.myplatform.core.util.DateTimeUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관심종목 리스크 감시의 '새 위험 공시' — 최근 7일 접수분, 공시 감시와 같은 '보낸 공시' 기억(10일)을 쓴다(2026-10-19).
 *
 * <p>재현(리뷰 에이전트 지적): 10/7 에 판정 창을 '직전 거래일 다음 날부터 오늘까지'로 좁혀 반복은 막았지만 ① 마지막 감시(19:50)
 * 뒤에 접수된 공시는 다음 날 창 밖이라 영영 안 나갔고 ② DART 조회가 실패한 날·서버가 꺼진 날의 공시도 같았으며 ③ 새 위험 공시가
 * 둘이면 매번 첫 번째만 골라, 첫 번째를 알린 뒤엔 두 번째가 끝내 안 나갔다. 창을 넓히려면 기억이 창보다 길어야 하는데 알림
 * 이력은 24시간만 본다 — 그래서 DART 공시 감시의 기억({@code dartSeen}, 10일)을 같이 쓴다. 두 감시가 같은 리스크 채널로 같은
 * 공시를 따로 보내던 것도 함께 없어진다.
 */
class WatchlistDisclosureWindowTest {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private final StockWatchlistRepository watchlist = mock(StockWatchlistRepository.class);
    private final AlertHistoryRepository history = mock(AlertHistoryRepository.class);
    private final StockPriceService prices = mock(StockPriceService.class);
    private final DartService dart = mock(DartService.class);
    private final TelegramNotificationService telegram = mock(TelegramNotificationService.class);
    private final RedisCacheService redis = mock(RedisCacheService.class);
    private final MarketCalendarService calendar = new MarketCalendarService();

    private WatchlistRiskMonitorService service(List<RiskAnalysisDto.DartDisclosure> dangerous) {
        StockWatchlist w = new StockWatchlist();
        w.setStockCode("000660");
        w.setStockName("SK하이닉스");
        when(watchlist.findByIsActiveTrue()).thenReturn(List.of(w));
        when(prices.getStockPrices(anyList())).thenReturn(Map.of());
        when(dart.isAvailable()).thenReturn(true);
        when(dart.searchDisclosuresByStockCode(anyString(), any())).thenReturn(dangerous);
        when(dart.filterDangerousDisclosures(anyList())).thenReturn(dangerous);
        when(history.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return new WatchlistRiskMonitorService(watchlist, history, prices, dart, mock(InvestorDailyTradeRepository.class),
                telegram, mock(SchedulerLockService.class), calendar, redis);
    }

    private static RiskAnalysisDto.DartDisclosure disclosure(LocalDate received, String seq, String name) {
        return RiskAnalysisDto.DartDisclosure.builder().reportNm(name).rceptNo(received.format(YMD) + seq)
                .rceptDt(received.format(YMD)).isDangerous(true).matchedKeyword("유상증자").build();
    }

    private String sentText() {
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendRisk(msg.capture());
        return msg.getValue();
    }

    @Test
    @DisplayName("재현: 직전 거래일(마지막 감시 뒤) 접수된 위험 공시를 오늘 알린다 — 예전 창 밖이라 영영 안 나갔다")
    void previousTradingDayDisclosureIsAlerted() {
        LocalDate prev = calendar.minusTradingDays(DateTimeUtil.kstNow().toLocalDate(), 1);
        service(List.of(disclosure(prev, "000501", "주요사항보고서(유상증자결정)"))).monitorWatchlistRisks();

        assertThat(sentText()).contains("유상증자결정");
    }

    @Test
    @DisplayName("재현: 새 위험 공시가 둘이면 둘 다 — 첫 번째를 이미 알렸어도 두 번째가 나간다")
    void secondDisclosureIsNotStarved() {
        LocalDate today = DateTimeUtil.kstNow().toLocalDate();
        RiskAnalysisDto.DartDisclosure first = disclosure(today, "000601", "주요사항보고서(유상증자결정)");
        RiskAnalysisDto.DartDisclosure second = disclosure(today, "000602", "주요사항보고서(전환사채권발행결정)");
        when(history.existsRecentAlert(eq("000660_WLD_" + first.getRceptNo()), any())).thenReturn(true);   // 첫 번째는 이미 보냈다

        service(List.of(first, second)).monitorWatchlistRisks();

        String text = sentText();
        assertThat(text).contains("전환사채권발행결정").doesNotContain("유상증자결정");
    }

    @Test
    @DisplayName("재현: DART 공시 감시가 이미 보낸 공시는 다시 보내지 않는다(같은 리스크 채널로 두 번 나갔다)")
    void disclosureAlreadySentByDartMonitorIsSkipped() {
        LocalDate today = DateTimeUtil.kstNow().toLocalDate();
        RiskAnalysisDto.DartDisclosure d = disclosure(today, "000701", "주요사항보고서(유상증자결정)");
        when(redis.get("dartSeen", d.getRceptNo(), String.class)).thenReturn("1");

        service(List.of(d)).monitorWatchlistRisks();

        verify(telegram, never()).sendRisk(anyString());
    }

    @Test
    @DisplayName("보낸 공시는 공용 기억(dartSeen, 10일)에 남긴다 — 7일 창 안에서 다음 날 다시 나가지 않게")
    void sentDisclosureIsRemembered() {
        LocalDate today = DateTimeUtil.kstNow().toLocalDate();
        RiskAnalysisDto.DartDisclosure d = disclosure(today, "000801", "주요사항보고서(유상증자결정)");

        service(List.of(d)).monitorWatchlistRisks();

        verify(telegram).sendRisk(anyString());
        verify(redis).put(eq("dartSeen"), eq(d.getRceptNo()), eq("1"), eq(Duration.ofDays(10)));
    }

    @Test
    @DisplayName("일주일 넘은 공시는 알리지 않는다")
    void oldDisclosureIsNotAlerted() {
        LocalDate today = DateTimeUtil.kstNow().toLocalDate();
        service(List.of(disclosure(today.minusDays(20), "000901", "주요사항보고서(유상증자결정)"))).monitorWatchlistRisks();

        verify(telegram, never()).sendRisk(anyString());
    }
}
