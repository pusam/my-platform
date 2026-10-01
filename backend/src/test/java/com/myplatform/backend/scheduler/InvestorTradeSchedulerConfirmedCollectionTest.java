package com.myplatform.backend.scheduler;

import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.service.InvestorTradeService;
import com.myplatform.backend.service.KoreaInvestmentService;
import com.myplatform.backend.service.MarketCalendarService;
import com.myplatform.backend.service.SchedulerLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 투자자 일별 수급은 장 마감 뒤 확정치만 그날 기록으로 저장한다(2026-10-01 감사).
 *
 * <p>운영 실측: 9/30 11:12 배포 → 11:13:16 부팅 수집이 "오늘 데이터 없음"으로 그 시각 잠정 집계 156행을 9/30 일별 기록으로
 * 저장했고, 15:50 정규 수집이 159행으로 갈아끼울 때까지 그날 11:30·14:00 종합추천의 수급 축이 잠정치로 계산됐다.
 * 15:50 이 실패하면 16:00·18:00 보완 수집은 "행 있음"만 보고 건너뛰어 잠정치가 영구화된다.
 */
class InvestorTradeSchedulerConfirmedCollectionTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);

    private final InvestorTradeService service = mock(InvestorTradeService.class);
    private final InvestorDailyTradeRepository repo = mock(InvestorDailyTradeRepository.class);
    private final KoreaInvestmentService kis = mock(KoreaInvestmentService.class);
    private final SchedulerLockService lock = mock(SchedulerLockService.class);
    private final MarketCalendarService calendar = mock(MarketCalendarService.class);

    private InvestorTradeScheduler at(int hour, int minute) {
        Clock clock = Clock.fixed(LocalDateTime.of(TODAY, java.time.LocalTime.of(hour, minute)).atZone(KST).toInstant(), KST);
        return new InvestorTradeScheduler(service, repo, kis, lock, calendar, clock);
    }

    @BeforeEach
    void setUp() {
        lenient().when(calendar.isMarketClosed(any(LocalDate.class))).thenReturn(false);
        lenient().when(calendar.isMarketClosed()).thenReturn(false);
        lenient().when(kis.isTokenAvailable()).thenReturn(true);
        lenient().when(lock.tryLock(anyString(), any())).thenReturn(true);
        lenient().when(service.collectInvestorTradeData(any())).thenReturn(Map.of("FOREIGN", 10));
    }

    @Test
    @DisplayName("장중(11:13) 부팅 — 오늘 행이 없어도 잠정 집계를 그날 일별 기록으로 저장하지 않는다")
    void intradayBootDoesNotStoreProvisionalAsDaily() {
        when(repo.existsByTradeDate(TODAY)).thenReturn(false);

        at(11, 13).runStartupCollection();

        verify(service, never()).collectInvestorTradeData(any());
    }

    @Test
    @DisplayName("장 마감 뒤(16:30) 부팅 — 오늘 행이 없으면 확정 수집한다(15:50 을 놓친 날)")
    void afterCloseBootCollectsWhenMissing() {
        when(repo.existsByTradeDate(TODAY)).thenReturn(false);

        at(16, 30).runStartupCollection();

        verify(service).collectInvestorTradeData(TODAY);
    }

    private static List<Object[]> provisional() {
        return List.of(new Object[]{"FOREIGN", TODAY.atTime(11, 13)}, new Object[]{"INSTITUTION", TODAY.atTime(11, 13)});
    }

    private static List<Object[]> confirmed() {
        return List.of(new Object[]{"FOREIGN", TODAY.atTime(15, 50, 1)}, new Object[]{"INSTITUTION", TODAY.atTime(15, 50, 2)});
    }

    @Test
    @DisplayName("장 마감 뒤 부팅 — 오늘 행이 장중 잠정치뿐이면 확정치로 다시 받는다(행 존재만으로 건너뛰지 않는다)")
    void afterCloseBootReplacesProvisionalRows() {
        when(repo.existsByTradeDate(TODAY)).thenReturn(true);   // 11:13 부팅이 남긴 잠정 행
        when(repo.summarizeByInvestorType(TODAY)).thenReturn(provisional());

        at(16, 30).runStartupCollection();

        verify(service).collectInvestorTradeData(TODAY);
    }

    @Test
    @DisplayName("장 마감 뒤 부팅 — 확정 기록(15:50·외국인·기관)이 있으면 건드리지 않는다")
    void afterCloseBootSkipsWhenConfirmed() {
        when(repo.existsByTradeDate(TODAY)).thenReturn(true);
        when(repo.summarizeByInvestorType(TODAY)).thenReturn(confirmed());

        at(16, 30).runStartupCollection();

        verify(service, never()).collectInvestorTradeData(any());
    }

    @Test
    @DisplayName("18:00 보완 — 오늘 행이 잠정치뿐이면 다시 받는다(15:50 실패 뒤 잠정치 영구화 방지)")
    void eveningRetryReplacesProvisionalRows() {
        when(repo.existsByTradeDate(TODAY)).thenReturn(true);
        when(repo.summarizeByInvestorType(TODAY)).thenReturn(provisional());

        at(18, 0).collectEvening();

        verify(service).collectInvestorTradeData(TODAY);
    }

    @Test
    @DisplayName("18:00 보완 — 외국인만 있고 기관이 없으면(부분 수집) 다시 받는다")
    void eveningRetryReplacesPartialRows() {
        when(repo.existsByTradeDate(TODAY)).thenReturn(true);
        when(repo.summarizeByInvestorType(TODAY))
                .thenReturn(List.<Object[]>of(new Object[]{"FOREIGN", TODAY.atTime(15, 50, 1)}));

        at(18, 0).collectEvening();

        verify(service).collectInvestorTradeData(TODAY);
    }

    @Test
    @DisplayName("18:00 보완 — 확정 기록이 있으면 건드리지 않는다")
    void eveningRetrySkipsWhenConfirmed() {
        when(repo.existsByTradeDate(TODAY)).thenReturn(true);
        when(repo.summarizeByInvestorType(TODAY)).thenReturn(confirmed());

        at(18, 0).collectEvening();

        verify(service, never()).collectInvestorTradeData(any());
    }
}
