package com.myplatform.backend.service;

import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 모닝 브리핑 연속 매수 TOP 5 는 어느 장 마감까지의 집계인가(2026-10-19, 리뷰 에이전트 지적).
 *
 * <p>재현: 연속 매수는 투자자 일별 표의 마지막 거래일에서 끝나는데, 브리핑은 그 날짜를 적지 않았다 — 전날 장 마감 수집이
 * 빠지면(수집 정체·장애) 며칠 전 연속 매수가 "오늘 아침의 TOP 5"로 나갔다. 이제 머리줄에 'MM/dd 장 마감까지'를 붙이고,
 * 07:30 기준 마지막 장 마감 거래일 집계가 아니면 그 사실을 한 줄로 말한다. 07:30 크론만 이 브리핑을 보낸다.
 */
class MorningBriefingConsecutiveBasisTest {

    private final InvestorTradeService investor = mock(InvestorTradeService.class);
    private LocalDateTime now;

    private final MorningBriefingService service = new MorningBriefingService(investor, mock(WatchlistService.class),
            mock(MarketTimingService.class), mock(QuantScreenerService.class), mock(TelegramNotificationService.class),
            mock(RecommendationSnapshotRepository.class), mock(StockCatalystService.class)) {
        @Override
        LocalDateTime now() {
            return now;
        }
    };

    {
        service.marketCalendar = new MarketCalendarService();
    }

    private static ConsecutiveBuyDto streak(String code, String name, int days, LocalDate end) {
        return ConsecutiveBuyDto.builder().stockCode(code).stockName(name).consecutiveDays(days)
                .totalNetBuyAmount(new BigDecimal("812.5")).startDate(end.minusDays(days)).endDate(end).build();
    }

    private String section() {
        StringBuilder sb = new StringBuilder();
        service.appendConsecutiveBuys(sb, "FOREIGN", "외국인", "F");
        return sb.toString();
    }

    @Test
    @DisplayName("재현: 화요일 07:30 인데 집계가 금요일 장 마감까지 — 날짜를 적고 '최신 수집분이 아니다'를 말한다")
    void staleStreakIsLabelled() {
        now = LocalDate.of(2026, 10, 20).atTime(7, 30);   // 화
        when(investor.getConsecutiveBuyStocks("FOREIGN", 3))
                .thenReturn(List.of(streak("005930", "삼성전자", 4, LocalDate.of(2026, 10, 16))));   // 금

        String s = section();

        assertThat(s).contains("외국인 연속 매수 TOP 5</b> (10/16 가집계까지)");
        assertThat(s).contains("⚠ 최신 수집분이 아닙니다 — 10/19 수급 집계가 없습니다");
        assertThat(s).contains("1. 삼성전자 (005930) - 4일 연속");
    }

    @Test
    @DisplayName("재현: 월요일 07:30 · 금요일 장 마감까지 = 최신 — 날짜만 붙이고 경고는 없다")
    void freshStreakShowsOnlyTheDate() {
        now = LocalDate.of(2026, 10, 19).atTime(7, 30);   // 월
        when(investor.getConsecutiveBuyStocks("FOREIGN", 3))
                .thenReturn(List.of(streak("005930", "삼성전자", 4, LocalDate.of(2026, 10, 16))));

        String s = section();

        assertThat(s).contains("외국인 연속 매수 TOP 5</b> (10/16 가집계까지)");
        assertThat(s).doesNotContain("⚠");
    }

    @Test
    @DisplayName("목록이 비면 날짜 없이 '해당 종목 없음'")
    void emptyListHasNoDate() {
        now = LocalDate.of(2026, 10, 20).atTime(7, 30);
        when(investor.getConsecutiveBuyStocks("FOREIGN", 3)).thenReturn(List.of());

        String s = section();

        assertThat(s).contains("외국인 연속 매수 TOP 5</b>\n").contains("해당 종목 없음").doesNotContain("가집계까지");
    }
}
