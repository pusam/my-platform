package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 크루 컨텍스트 빌더 — {@link CrewContextBuilder}.
 *
 * <p>두 가지가 핵심이다.
 * <ol>
 *   <li><b>생략을 숨기지 않는다</b> — 상한으로 FLAGGED 를 잘랐으면 본문에 "N건 생략"이 남아야 한다.
 *       안 그러면 FIREWALL 이 "플래그에 없으니 문제 없다"고 판단한다.</li>
 *   <li><b>데이터 없음을 0 으로 위장하지 않는다</b> — 못 읽은 블록은 "없음"이 아니라 "데이터 없음"이다.</li>
 * </ol>
 */
class CrewContextBuilderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);

    @Test
    @DisplayName("상한을 넘으면 중요도 낮은 FLAGGED 부터 잘라내고 생략 건수를 본문에 남긴다")
    void trimsLowSeverityFlagsAndDeclaresOmission() {
        List<ControlRoomSnapshotDto.FlagItem> flags = new ArrayList<>();
        flags.add(flag("critical-one", ControlRoomFlagParser.SEVERITY_CRITICAL, "치명적 항목"));
        for (int i = 0; i < 40; i++) {
            flags.add(flag("info-" + i, ControlRoomFlagParser.SEVERITY_INFO, "덜 중요한 항목 " + i));
        }

        CrewContextBuilder.Context ctx = CrewContextBuilder.build(snapshot(flags), 2048);

        assertThat(ctx.bytes()).isLessThanOrEqualTo(2048);
        assertThat(ctx.omittedFlags()).isGreaterThan(0);
        assertThat(ctx.text())
                .contains("치명적 항목")                       // critical 은 살아남는다
                .contains(ctx.omittedFlags() + "건 생략됨")
                .contains("전부가 아니다");
    }

    @Test
    @DisplayName("상한이 넉넉하면 아무것도 자르지 않는다")
    void keepsAllFlagsWhenBudgetIsEnough() {
        List<ControlRoomSnapshotDto.FlagItem> flags = List.of(
                flag("a", ControlRoomFlagParser.SEVERITY_CRITICAL, "가"),
                flag("b", ControlRoomFlagParser.SEVERITY_WARNING, "나"));

        CrewContextBuilder.Context ctx = CrewContextBuilder.build(snapshot(flags), 8192);

        assertThat(ctx.omittedFlags()).isZero();
        assertThat(ctx.text()).contains("가").contains("나").doesNotContain("생략됨");
    }

    @Test
    @DisplayName("플래그 문서를 못 읽으면 '없음'이 아니라 '데이터 없음'으로 적는다")
    void missingFlagDocumentIsNotZero() {
        ControlRoomSnapshotDto s = new ControlRoomSnapshotDto(
                TODAY, LocalDateTime.of(2026, 8, 24, 10, 0), "2026-08",
                kpis(), calendar(), new ControlRoomSnapshotDto.Flagged(false, List.of(), 0),
                new ControlRoomSnapshotDto.Invariants(true, List.of("1. 시세는 단일 경로")), null);

        String text = CrewContextBuilder.build(s, 8192).text();

        assertThat(text).contains("데이터 없음").contains("'이상 없음'이 아니다");
    }

    @Test
    @DisplayName("불변식을 못 읽으면 그 사실과 함께 '승인에 신중하라'를 적는다")
    void missingInvariantsIsFlaggedInPrompt() {
        ControlRoomSnapshotDto s = new ControlRoomSnapshotDto(
                TODAY, LocalDateTime.of(2026, 8, 24, 10, 0), "2026-08",
                kpis(), calendar(), new ControlRoomSnapshotDto.Flagged(true, List.of(), 0),
                new ControlRoomSnapshotDto.Invariants(false, List.of()), null);

        assertThat(CrewContextBuilder.build(s, 8192).text())
                .contains("불변식 문서를 읽지 못함")
                .contains("승인에 신중");
    }

    @Test
    @DisplayName("조건 트리거 항목은 판정일이 아니라 '확인일'로 적는다")
    void triggerEntryIsMarkedAsCheckDate() {
        assertThat(CrewContextBuilder.build(snapshot(List.of()), 8192).text())
                .contains("확인일 — 실제 트리거는 조건: 양쪽 각 n>=30");
    }

    @Test
    @DisplayName("KPI 의 '데이터 없음'이 0 으로 바뀌지 않는다")
    void unavailableKpiStaysUnavailable() {
        ControlRoomSnapshotDto s = new ControlRoomSnapshotDto(
                TODAY, LocalDateTime.of(2026, 8, 24, 10, 0), "2026-08",
                new ControlRoomSnapshotDto.Kpis(
                        new ControlRoomSnapshotDto.Candidates(false, 0, 0, 0, 0, null, null, null, null,
                                "보드 조회 실패", "보드 조회가 예외로 실패했다"),
                        new ControlRoomSnapshotDto.Gates(false, 0, 0, List.of()),
                        new ControlRoomSnapshotDto.LossBreaker(false, null, null, null, null, null, null),
                        new ControlRoomSnapshotDto.VolRegime(false, null, null, "VKOSPI 미수집"),
                        new ControlRoomSnapshotDto.Undecided(false, 0, 0)),
                calendar(), new ControlRoomSnapshotDto.Flagged(true, List.of(), 0),
                new ControlRoomSnapshotDto.Invariants(true, List.of("1. 시세는 단일 경로")), null);

        String text = CrewContextBuilder.build(s, 8192).text();

        assertThat(text)
                .contains("종합판단 후보: 데이터 없음")
                .contains("봇 게이트: 데이터 없음")
                .contains("일일손실 서킷: 데이터 없음")
                .contains("VKOSPI 국면: 데이터 없음")
                .contains("미판정: 데이터 없음");
    }

    // ==================== fixture ====================

    private static ControlRoomSnapshotDto snapshot(List<ControlRoomSnapshotDto.FlagItem> flags) {
        long critical = flags.stream()
                .filter(f -> ControlRoomFlagParser.SEVERITY_CRITICAL.equals(f.severity())).count();
        return new ControlRoomSnapshotDto(
                TODAY, LocalDateTime.of(2026, 8, 24, 10, 0), "2026-08",
                kpis(), calendar(),
                new ControlRoomSnapshotDto.Flagged(true, flags, critical),
                new ControlRoomSnapshotDto.Invariants(true, List.of("1. 시세는 단일 경로")),
                null);
    }

    private static ControlRoomSnapshotDto.Kpis kpis() {
        return new ControlRoomSnapshotDto.Kpis(
                new ControlRoomSnapshotDto.Candidates(true, 3, 1, 0, 2,
                        LocalDateTime.of(2026, 8, 24, 11, 30), false, "11:30 기준", true, null, null),
                new ControlRoomSnapshotDto.Gates(true, 3, 5, List.of(
                        new ControlRoomSnapshotDto.Gate("kill-switch", "킬스위치", "OPEN", "정상"),
                        new ControlRoomSnapshotDto.Gate("nxt-routing", "NXT 주문 라우팅", "CLOSED", "flag OFF"))),
                new ControlRoomSnapshotDto.LossBreaker(true, -60000L, 300000L, true, false, "VIRTUAL", null),
                new ControlRoomSnapshotDto.VolRegime(true, "NORMAL", "OFF", null),
                new ControlRoomSnapshotDto.Undecided(true, 8, 8));
    }

    private static ControlRoomSnapshotDto.Calendar calendar() {
        ControlRoomSnapshotDto.CalendarEntry overdue = new ControlRoomSnapshotDto.CalendarEntry(
                "supply-cap-10", "수급 캡10 사후검증", LocalDate.of(2026, 7, 22), "pending",
                null, null, null, "decision", true);
        ControlRoomSnapshotDto.CalendarEntry trigger = new ControlRoomSnapshotDto.CalendarEntry(
                "control-group-first", "무작위 대조군 첫 판정", LocalDate.of(2026, 9, 7), "pending",
                null, null, "양쪽 각 n>=30", "decision", false);
        return new ControlRoomSnapshotDto.Calendar(true, List.of(overdue, trigger),
                List.of(overdue), List.of(trigger), List.of(), LocalDate.of(2026, 9, 7), 14);
    }

    private static ControlRoomSnapshotDto.FlagItem flag(String id, String severity, String title) {
        return new ControlRoomSnapshotDto.FlagItem(id, severity, title, "KEY",
                "본문 설명이 어느 정도 길이를 가진다고 가정한다. 실제 파일의 body 도 두세 줄이다.",
                LocalDate.of(2026, 8, 21), 3, "ref", false);
    }

    @SuppressWarnings("unused")
    private static int utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    @DisplayName("어제 스냅샷 폴백이면 크루 컨텍스트에 그 사실을 박는다")
    void snapshotFallbackIsDeclaredToCrew() {
        ControlRoomSnapshotDto s = new ControlRoomSnapshotDto(
                TODAY, LocalDateTime.of(2026, 8, 26, 9, 0), "2026-08",
                new ControlRoomSnapshotDto.Kpis(
                        new ControlRoomSnapshotDto.Candidates(true, 1, 0, 1, 0,
                                LocalDateTime.of(2026, 8, 25, 17, 0), false,
                                "08-25 17:00 스냅샷", false, null, null),
                        new ControlRoomSnapshotDto.Gates(true, 3, 5, List.of()),
                        new ControlRoomSnapshotDto.LossBreaker(true, 0L, 300000L, true, false, "VIRTUAL", null),
                        new ControlRoomSnapshotDto.VolRegime(true, "NORMAL", "OFF", null),
                        new ControlRoomSnapshotDto.Undecided(true, 8, 8)),
                calendar(), new ControlRoomSnapshotDto.Flagged(true, List.of(), 0),
                new ControlRoomSnapshotDto.Invariants(true, List.of("1. 시세는 단일 경로")), null);

        String text = CrewContextBuilder.build(s, 8192).text();

        assertThat(text)
                .contains("어제 스냅샷 폴백")
                .contains("오늘 실시간 계산값이 아님");
    }
}
