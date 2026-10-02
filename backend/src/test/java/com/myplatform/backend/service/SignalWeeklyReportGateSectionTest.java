package com.myplatform.backend.service;

import com.myplatform.backend.controlroom.BotGateRules;
import com.myplatform.backend.controlroom.TrustGateRules;
import com.myplatform.backend.dto.WeeklySignalAccuracyDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주간 텔레그램 '믿고 맡겨도 되나' 블록 — {@code SignalWeeklyReportService.gateSection}(2026-10-02).
 *
 * <p>관제실을 안 열어도 두 게이트(추천·봇)의 현재 답을 주 1회 받는다. 판정 문장은 게이트가 만든 헤드라인 그대로 —
 * 여기서 다시 판정하지 않는다. 집계가 실패하면 줄을 빼지 않고 "측정 실패"로 적는다(§4c).
 */
class SignalWeeklyReportGateSectionTest {

    private static TrustGateRules.Verdict collectingReco() {
        return TrustGateRules.judge(List.of(), 4, 2, new TrustGateRules.Shape(null, null, null, null), 0);
    }

    private static BotPerformanceService.BotGateSummary botOff() {
        List<BotGateRules.TradeOutcome> trades = new ArrayList<>();
        for (int d = 0; d < 16; d++) {
            for (int i = 0; i < 7; i++) {
                trades.add(new BotGateRules.TradeOutcome(LocalDate.of(2026, 7, 3).plusDays(d), "SCALPING",
                        new BigDecimal(d % 2 == 0 ? "-0.9" : "0.2")));
            }
        }
        BotGateRules.Verdict v = BotGateRules.judge(trades, new BigDecimal("-8.30"), 0);
        BotPerformanceService.BotGateLine line = new BotPerformanceService.BotGateLine("VIRTUAL", true, 25L,
                new BigDecimal("10000000"), new BigDecimal("-799916"),
                LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 18), v, null);
        return new BotPerformanceService.BotGateSummary("VIRTUAL", false,
                LocalDateTime.of(2026, 7, 27, 15, 20, 15), List.of(line), null);
    }

    @Test
    @DisplayName("표본 시작일이 미정이면 추천 줄은 '검증 중' — 이전 산식 성적을 현재 성적처럼 적지 않는다")
    void undeterminedBoundary() {
        String s = SignalWeeklyReportService.gateSection(null,
                new SignalSampleBoundary.Boundary(null, SignalSampleBoundary.Status.UNSET), null, botOff(), null);

        assertThat(s).contains("🚦 <b>믿고 맡겨도 되나</b>");
        assertThat(s).contains("• 추천: 표본 시작일 미정 — 현재 산식 판정 없음(검증 중)");
    }

    @Test
    @DisplayName("시작일이 있으면 게이트 헤드라인 그대로 + 표본 수·시작일, 확정 전이면 '잠정'")
    void recoHeadlineWithBoundary() {
        String provisional = SignalWeeklyReportService.gateSection(collectingReco(),
                new SignalSampleBoundary.Boundary(LocalDate.of(2026, 10, 6), SignalSampleBoundary.Status.PROVISIONAL),
                null, botOff(), null);
        assertThat(provisional).contains("• 추천: 표본 수집 중 — 시그널 표본 4/30건")
                .contains("(표본 4건·고유 0일, 2026-10-06~ 잠정)");

        String confirmed = SignalWeeklyReportService.gateSection(collectingReco(),
                new SignalSampleBoundary.Boundary(LocalDate.of(2026, 10, 6), SignalSampleBoundary.Status.CONFIRMED),
                null, botOff(), null);
        assertThat(confirmed).contains("2026-10-06~)").doesNotContain("잠정");
    }

    @Test
    @DisplayName("봇 줄은 모드·꺼짐 여부를 붙이고 헤드라인과 표본을 그대로 — 7월 모의 성적 모양")
    void botLineSaysOffAndHeadline() {
        String s = SignalWeeklyReportService.gateSection(null,
                new SignalSampleBoundary.Boundary(null, SignalSampleBoundary.Status.UNSET), null, botOff(), null);

        assertThat(s).contains("• 봇(모의, 꺼짐 2026-07-27~): 평가 가능 — 아직 근거 없음: 하루 평균 순수익 -0.35%")
                .contains("(거래 112건·거래일 16일)");
        assertThat(s).contains("최고 단계도 확대 검토이지 실매수 승인 아님");
    }

    @Test
    @DisplayName("집계가 실패하면 줄을 빼지 않고 '측정 실패'로 — 사유는 HTML 이스케이프")
    void failuresAreDeclaredAndEscaped() {
        String s = SignalWeeklyReportService.gateSection(null, null, "집계 실패(<Timeout>)", null, "봇 성과 서비스 미가용");

        assertThat(s).contains("• 추천: 측정 실패 — 집계 실패(&lt;Timeout&gt;)");
        assertThat(s).contains("• 봇: 측정 실패 — 봇 성과 서비스 미가용");
    }

    @Test
    @DisplayName("본문에 끼울 때는 꼬리말 앞에 — 없으면(null) 종전 본문 그대로")
    void insertedBeforeFooter() {
        WeeklySignalAccuracyDto dto = new WeeklySignalAccuracyDto();
        dto.setWeekStart(LocalDate.of(2026, 9, 21));
        dto.setWeekEnd(LocalDate.of(2026, 9, 27));
        dto.setCategoryTrends(List.of());
        dto.setWarnings(List.of());
        SignalWeeklyReportService svc = new SignalWeeklyReportService(null, null, null, null, null, null, null, null, null);

        String with = svc.buildTelegramSummary(dto, List.of(), false, "\n🚦 블록\n");
        String without = svc.buildTelegramSummary(dto, List.of(), false);

        assertThat(with.indexOf("🚦 블록")).isLessThan(with.indexOf("측정 전용 — 종합점수 산식 무변경"));
        assertThat(without).doesNotContain("🚦");
    }
}
