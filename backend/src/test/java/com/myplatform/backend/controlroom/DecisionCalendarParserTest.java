package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 판정 캘린더 파서 — {@link DecisionCalendarParser}.
 *
 * <p>핵심은 "조용한 실패 금지"다. 파싱이 깨진 항목을 건너뛰면 캘린더가 멀쩡해 보이는데 실제로는
 * 판정이 누락된다(§4c). 그래서 깨진 항목은 반드시 {@code parseErrors} 로 올라와야 한다.
 */
class DecisionCalendarParserTest {

    /** 실제 파일 기준 "오늘" — OVERDUE 검산 기준일. */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);

    private static String yaml(String body) {
        return "```yaml\ncalendar:\n" + body + "\n```\n";
    }

    @Test
    @DisplayName("정상 항목을 읽고 기본 kind 는 decision 이다")
    void parsesEntryWithDefaultKind() {
        DecisionCalendarParser.Result r = DecisionCalendarParser.parse(yaml("""
                  - id: a-item
                    title: 안건 A
                    due: 2026-09-16
                    status: pending
                """));

        assertThat(r.dataAvailable()).isTrue();
        assertThat(r.parseErrors()).isEmpty();
        assertThat(r.entries()).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo("a-item");
            assertThat(e.due()).isEqualTo(LocalDate.of(2026, 9, 16));
            assertThat(e.kind()).isEqualTo(DecisionCalendarParser.KIND_DECISION);
            assertThat(e.isMilestone()).isFalse();
            assertThat(e.hasTrigger()).isFalse();
        });
    }

    @Test
    @DisplayName("OVERDUE = due 가 지났고 status 가 pending/deferred 인 것만")
    void overdueCountsPendingAndDeferredOnly() {
        DecisionCalendarParser.Result r = DecisionCalendarParser.parse(yaml("""
                  - id: past-pending
                    title: 지난 pending
                    due: 2026-07-22
                    status: pending
                  - id: past-deferred
                    title: 지난 deferred
                    due: 2026-08-11
                    status: deferred
                  - id: past-decided
                    title: 지난 decided
                    due: 2026-07-01
                    status: decided
                    decided_on: 2026-07-02
                  - id: future-pending
                    title: 미래 pending
                    due: 2026-09-16
                    status: pending
                """));

        assertThat(r.overdue(TODAY))
                .extracting(DecisionCalendarParser.Entry::id)
                .containsExactly("past-pending", "past-deferred");
    }

    @Test
    @DisplayName("근사 표현(경/중/수시) 이 든 due 는 조용히 넘기지 않고 파싱 오류로 보고한다")
    void reportsApproximateDueAsParseError() {
        DecisionCalendarParser.Result r = DecisionCalendarParser.parse(yaml("""
                  - id: fuzzy-due
                    title: 근사 날짜
                    due: 2026-07-22 경
                    status: pending
                  - id: good-one
                    title: 정상
                    due: 2026-09-16
                    status: pending
                """));

        assertThat(r.entries()).extracting(DecisionCalendarParser.Entry::id).containsExactly("good-one");
        assertThat(r.parseErrors()).singleElement().asString()
                .contains("파싱 오류: fuzzy-due")
                .contains("due");
    }

    @Test
    @DisplayName("status 오타도 파싱 오류로 보고한다")
    void reportsInvalidStatus() {
        DecisionCalendarParser.Result r = DecisionCalendarParser.parse(yaml("""
                  - id: bad-status
                    title: 상태 오타
                    due: 2026-09-16
                    status: waiting
                """));

        assertThat(r.entries()).isEmpty();
        assertThat(r.parseErrors()).singleElement().asString().contains("파싱 오류: bad-status");
    }

    @Test
    @DisplayName("trigger 가 있으면 조건 대기로 분류된다 (due 는 판정일이 아니라 확인일)")
    void triggerEntriesAreConditionWaiting() {
        DecisionCalendarParser.Result r = DecisionCalendarParser.parse(yaml("""
                  - id: cond
                    title: 조건 트리거 안건
                    due: 2026-09-07
                    status: pending
                    trigger: 양쪽 각 n>=30
                  - id: plain
                    title: 일반 안건
                    due: 2026-09-16
                    status: pending
                """));

        assertThat(r.conditionWaiting())
                .extracting(DecisionCalendarParser.Entry::id)
                .containsExactly("cond");
    }

    @Test
    @DisplayName("milestone 은 로스터 매칭 대상이 아니다 — 표에 없어도 미등록으로 세지 않는다")
    void milestoneIsExcludedFromRoster() {
        String md = yaml("""
                  - id: only-milestone
                    title: NXT 개시
                    due: 2026-09-14
                    status: pending
                    kind: milestone
                """)
                + "\n| 판정일 | 안건 | 결정 |\n|---|---|---|\n"
                + "| 2026-__-__ | 표에만 있는 안건 | |\n";

        DecisionCalendarParser.Result r = DecisionCalendarParser.parse(md);

        assertThat(r.entries()).singleElement()
                .satisfies(e -> assertThat(e.isMilestone()).isTrue());
        assertThat(r.rosterSize()).isEqualTo(1);
        assertThat(r.undecidedCount()).isEqualTo(1);
        // milestone 은 로스터를 채우지 않으므로 표의 안건은 여전히 '미등록'이다.
        assertThat(r.unregisteredTitles()).containsExactly("표에만 있는 안건");
    }

    @Test
    @DisplayName("YAML 블록이 없으면 '판정 0건'이 아니라 데이터 없음이다")
    void missingBlockIsNotZero() {
        DecisionCalendarParser.Result r = DecisionCalendarParser.parse("# 제목만 있는 문서\n본문\n");

        assertThat(r.dataAvailable()).isFalse();
        assertThat(r.recordTableFound()).isFalse();
        assertThat(r.entries()).isEmpty();
    }

    @Test
    @DisplayName("실제 docs/SCHEDULE_DECISIONS.md — 파싱 무결성과 로스터 정합")
    void realDocumentParsesCleanly() throws IOException {
        Path path = Path.of("..", "docs", "SCHEDULE_DECISIONS.md");
        assumeTrue(Files.exists(path), "레포 루트 기준 실행이 아닐 때는 건너뛴다");

        DecisionCalendarParser.Result r =
                DecisionCalendarParser.parse(Files.readString(path, StandardCharsets.UTF_8));

        assertThat(r.dataAvailable()).isTrue();
        assertThat(r.recordTableFound()).isTrue();
        assertThat(r.parseErrors()).isEmpty();

        // 판정 기록 표 8행이 전부 미기입 상태
        assertThat(r.rosterSize()).isEqualTo(8);
        assertThat(r.undecidedCount()).isEqualTo(8);

        // 표의 8건이 모두 YAML 에 등록돼 있어야 한다 (표↔블록 title 드리프트 감지)
        assertThat(r.unregisteredTitles()).isEmpty();

        // 조건 트리거 항목은 due 가 '확인일'이라 화면에서 판정일과 구분돼야 한다.
        // ⚠ 기한(due)은 재판정 때마다 바뀌므로 특정 날짜·OVERDUE 건수를 단언하지 않는다 —
        //    날짜를 박으면 판정을 내릴 때마다 이 테스트가 깨진다(2026-08-26 실제로 깨졌다).
        //    OVERDUE 판정 로직 자체는 위의 합성 데이터 테스트가 검증한다.
        assertThat(r.conditionWaiting())
                .extracting(DecisionCalendarParser.Entry::id)
                .contains("control-group-first");

        // 모든 항목이 스키마를 지킨다
        assertThat(r.entries()).allSatisfy(e -> {
            assertThat(e.id()).isNotBlank();
            assertThat(e.title()).isNotBlank();
            assertThat(e.due()).isNotNull();
            assertThat(e.status()).isIn("pending", "decided", "deferred");
        });
    }
}
