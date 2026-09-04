package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.WeeklySignalAccuracyDto;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.SignalWeeklyAccuracyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주간 리포트 텔레그램 본문은 parse_mode=HTML — 자유 텍스트 경고는 이스케이프해야 한다.
 *
 * <p>고치려는 결함(2026-09-03 18:30 실발생): {@code WeeklyAccuracyAggregator} 가 만든 경고
 * "이번 주 표본부족(n=3<10) …" 이 그대로 붙어 텔레그램이 {@code <10)} 을 시작 태그로 읽었다 —
 * {@code 400 Bad Request: can't parse entities: Unsupported start tag "10)"}. 평문 폴백으로 내용은
 * 갔지만 굵게·기울임이 전부 깨진 채였다. 8/24~8/30 주 따라잡기의 첫 발송이 그 케이스였다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SignalWeeklyReportTelegramEscapeTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private SignalOutcomeRepository outcomeRepository;
    @Mock private SignalWeeklyAccuracyRepository weeklyRepository;
    @Mock private SchedulerLockService schedulerLockService;
    @Mock private ObjectProvider<TelegramNotificationService> telegramProvider;

    private SignalWeeklyReportService service() {
        Clock clock = Clock.fixed(LocalDate.of(2026, 9, 3).atStartOfDay(KST).toInstant(), KST);
        return new SignalWeeklyReportService(outcomeRepository, weeklyRepository,
                schedulerLockService, telegramProvider, new ObjectMapper(), clock, null);
    }

    private static WeeklySignalAccuracyDto dtoWithWarning(String warning) {
        return WeeklySignalAccuracyDto.builder()
                .weekStart(LocalDate.of(2026, 8, 24))
                .weekEnd(LocalDate.of(2026, 8, 30))
                .weeklyN(3)
                .cumulativeN(153)
                .categoryTrends(List.of())
                .warnings(List.of(warning))
                .build();
    }

    @Test
    @DisplayName("경고의 '<' 는 &lt; 로 — 'n=3<10)' 이 태그로 읽히지 않는다")
    void warningAngleBracketIsEscaped() {
        String text = service().buildTelegramSummary(
                dtoWithWarning("이번 주 표본부족(n=3<10) — 주간 수치는 참고만, 누적 추세 우선"), List.of(), false);

        assertThat(text).contains("n=3&lt;10)");
        assertThat(text).as("수정 전엔 원문 '<10)' 이 그대로 나가 텔레그램 400").doesNotContain("<10)");
    }

    @Test
    @DisplayName("본문 자체의 HTML 태그(<b>, <i>)는 살아 있다 — 이스케이프는 경고 텍스트에만")
    void ownMarkupSurvives() {
        String text = service().buildTelegramSummary(dtoWithWarning("S&T모티브 & <검증> 경고"), List.of(), false);

        assertThat(text).contains("<b>주간 시그널 예측력 측정</b>").contains("<b>경고</b>").contains("<i>측정 전용");
        assertThat(text).contains("S&amp;T모티브 &amp; &lt;검증&gt; 경고");
    }
}
