package com.myplatform.backend.service;

import com.myplatform.backend.dto.RiskAnalysisDto.DartDisclosure;
import com.myplatform.backend.repository.BotTradingPositionRepository;
import com.myplatform.backend.repository.StockWatchlistRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 보유·관심 종목 공시 감시는 최근 7일 접수분만 알리고, 보낸 공시는 10일 기억한다(2026-10-19).
 *
 * <p>재현: 조회는 최근 3개월인데 '이미 보냈다' 표시는 3일만 기억해서, 같은 위험·주요 공시가 3일마다 리스크 채널로 다시 나갔다
 * (3개월이면 약 30번). 서버가 3일 넘게 꺼졌다 켜지면 표시가 전부 풀려 감시 종목(최대 30)의 3개월치 공시가 한꺼번에 다시
 * 나갔다 — 2026-10 이사로 서버가 며칠 꺼졌다 켜진 날이 그 경우다. 관심종목 리스크 감시(893e599a)는 같은 결함을 고쳤는데 이
 * 감시기는 그대로였다. 창(7일)보다 기억(10일)이 길어야 한 번만 나간다 — 꺼져 있던 동안의 공시도 7일 안이면 켜진 뒤 한 번 나간다.
 */
class DartDisclosureMonitorRecencyTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private final DartService dart = mock(DartService.class);
    private final TelegramNotificationService telegram = mock(TelegramNotificationService.class);
    private final RedisCacheService redis = mock(RedisCacheService.class);
    private final DartDisclosureMonitorService monitor = new DartDisclosureMonitorService(
            dart, mock(RealTradeService.class), mock(KoreaInvestmentService.class), telegram, redis,
            mock(SchedulerLockService.class), mock(StockWatchlistRepository.class), mock(BotTradingPositionRepository.class));

    private static DartDisclosure dangerous(LocalDate received, String seq, boolean withDateField) {
        String rceptNo = received.format(YMD) + seq;
        DartDisclosure d = DartDisclosure.builder().rceptNo(rceptNo).reportNm("유상증자결정")
                .rceptDt(withDateField ? received.format(YMD) : null).build();
        d.setDangerous(true);
        return d;
    }

    @Test
    @DisplayName("재현: 한 달 전 접수된 위험 공시는 '이미 보냄' 표시가 풀렸어도 다시 보내지 않는다")
    void oldDisclosureIsNotResent() {
        LocalDate today = LocalDate.now(KST);
        when(dart.searchDisclosuresOrNull("000660", "SK하이닉스")).thenReturn(List.of(dangerous(today.minusDays(30), "000111", true)));

        assertThat(monitor.processStock("SK하이닉스", "000660")).isZero();
        verify(telegram, never()).sendRisk(anyString());
    }

    @Test
    @DisplayName("재현: 보낸 공시는 10일 기억한다 — 창(7일)보다 짧으면 창 안에서 다시 나간다")
    void sentDisclosureIsRememberedLongerThanWindow() {
        LocalDate today = LocalDate.now(KST);
        DartDisclosure d = dangerous(today, "000222", true);
        when(dart.searchDisclosuresOrNull("000660", "SK하이닉스")).thenReturn(List.of(d));

        assertThat(monitor.processStock("SK하이닉스", "000660")).isEqualTo(1);
        verify(redis).put(eq("dartSeen"), eq(d.getRceptNo()), eq("1"), eq(Duration.ofDays(10)));
    }

    @Test
    @DisplayName("꺼져 있던 동안(5일 전) 접수된 공시는 켜진 뒤 한 번 알린다")
    void disclosureDuringOutageIsSentOnce() {
        LocalDate today = LocalDate.now(KST);
        when(dart.searchDisclosuresOrNull("000660", "SK하이닉스")).thenReturn(List.of(dangerous(today.minusDays(5), "000333", true)));

        assertThat(monitor.processStock("SK하이닉스", "000660")).isEqualTo(1);
        verify(telegram).sendRisk(anyString());
    }

    @Test
    @DisplayName("재현: 접수일 칸이 비면 접수번호 앞 8자리(접수일)로 판단한다")
    void receiptDateFallsBackToReceiptNumber() {
        LocalDate today = LocalDate.now(KST);
        when(dart.searchDisclosuresOrNull("000660", "SK하이닉스")).thenReturn(List.of(dangerous(today.minusDays(40), "000444", false)));

        assertThat(monitor.processStock("SK하이닉스", "000660")).isZero();
        verify(telegram, never()).sendRisk(anyString());
    }

    @Test
    @DisplayName("최근 판정(순수) — 7일 경계 포함, 날짜를 모르면 알린다(조용히 빼지 않는다)")
    void recentRule() {
        LocalDate today = LocalDate.of(2026, 10, 19);
        assertThat(DartDisclosureMonitorService.isRecentReceipt("20261012", "20261012000001", today)).isTrue();
        assertThat(DartDisclosureMonitorService.isRecentReceipt("20261011", "20261011000001", today)).isFalse();
        assertThat(DartDisclosureMonitorService.isRecentReceipt(null, "20261011000001", today)).isFalse();
        assertThat(DartDisclosureMonitorService.isRecentReceipt(null, null, today)).isTrue();
        assertThat(DartDisclosureMonitorService.isRecentReceipt("2026-10-1", "abc", today)).isTrue();
    }
}
