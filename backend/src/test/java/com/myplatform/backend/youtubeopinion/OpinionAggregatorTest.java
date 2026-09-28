package com.myplatform.backend.youtubeopinion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.myplatform.backend.youtubeopinion.YtOpinion.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 집계 규칙 — 발언 수 ≠ 인물 수, 혼재, 미상 제외, 창 밖 분리, 재업로드·인용·검토 대기 제외. 테스트 전용 가상 데이터. */
class OpinionAggregatorTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 20, 0);
    static long seq = 1;

    static OpinionAggregator.OpinionItem item(Long person, Stance stance, LocalDateTime published) {
        return item(person, stance, published, StatementType.CURRENT_VIEW, ReviewStatus.AUTO_OK, true, false);
    }

    static OpinionAggregator.OpinionItem item(Long person, Stance stance, LocalDateTime published, StatementType type,
                                              ReviewStatus review, boolean currentRun, boolean duplicate) {
        long id = seq++;
        return new OpinionAggregator.OpinionItem(id, "vid" + String.format("%08d", id), "제목", "테스트채널", published,
                published.plusHours(1), person, person == null ? null : "인물" + person,
                person == null ? null : YtVideoParticipant.Role.GUEST, stance, type, review, currentRun, duplicate,
                "주장", null, null, null, null, "근거", 10, 20, "https://www.youtube.com/watch?v=x&t=10s", "m", "v1");
    }

    @Test
    @DisplayName("같은 사람이 여러 영상에서 3번 말해도 인물 수는 1 — 발언 수(3)와 섞지 않는다")
    void statementsVersusPersons() {
        OpinionAggregator.View v = OpinionAggregator.aggregate(List.of(
                item(1L, Stance.POSITIVE, NOW.minusDays(1)),
                item(1L, Stance.POSITIVE, NOW.minusDays(2)),
                item(1L, Stance.POSITIVE, NOW.minusDays(3))), NOW, 7);
        assertThat(v.summary().statementCounts().get("POSITIVE")).isEqualTo(3);
        assertThat(v.summary().personCounts().get("POSITIVE")).isEqualTo(1);
        assertThat(v.summary().totalPersons()).isEqualTo(1);
        assertThat(v.summary().totalStatements()).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 사람이 창 안에서 긍정→부정이면 MIXED 로 두고 순서를 남긴다(한쪽으로 합치지 않는다)")
    void mixed() {
        OpinionAggregator.View v = OpinionAggregator.aggregate(List.of(
                item(2L, Stance.POSITIVE, NOW.minusDays(5)),
                item(2L, Stance.NEGATIVE, NOW.minusDays(1)),
                item(3L, Stance.NEGATIVE, NOW.minusDays(2))), NOW, 7);
        OpinionAggregator.PersonStance p2 = v.persons().stream().filter(p -> p.personId() == 2L).findFirst().orElseThrow();
        assertThat(p2.stance()).isEqualTo(OpinionAggregator.MIXED);
        assertThat(p2.sequence()).containsExactly("POSITIVE", "NEGATIVE");
        assertThat(v.summary().personCounts()).containsEntry("MIXED", 1).containsEntry("NEGATIVE", 1).containsEntry("POSITIVE", 0);
        assertThat(v.summary().statementCounts()).containsEntry("NEGATIVE", 2).containsEntry("POSITIVE", 1);
    }

    @Test
    @DisplayName("발언자 미상은 인물 수에서 빼고 따로 센다")
    void unknownSpeaker() {
        OpinionAggregator.View v = OpinionAggregator.aggregate(List.of(
                item(null, Stance.POSITIVE, NOW.minusDays(1)),
                item(1L, Stance.POSITIVE, NOW.minusDays(1))), NOW, 7);
        assertThat(v.summary().totalPersons()).isEqualTo(1);
        assertThat(v.summary().unknownSpeakerStatements()).isEqualTo(1);
        assertThat(v.summary().statementCounts().get("POSITIVE")).isEqualTo(2);
    }

    @Test
    @DisplayName("창(7일) 밖은 현재 집계에서 빼고 날짜와 함께 older 로 — 최신순·최대 10건")
    void window() {
        List<OpinionAggregator.OpinionItem> items = new ArrayList<>();
        items.add(item(1L, Stance.POSITIVE, NOW.minusDays(6)));
        for (int i = 0; i < 12; i++) items.add(item(4L, Stance.NEGATIVE, NOW.minusDays(8 + i)));
        OpinionAggregator.View v = OpinionAggregator.aggregate(items, NOW, 7);
        assertThat(v.summary().totalStatements()).isEqualTo(1);
        assertThat(v.summary().statementCounts().get("NEGATIVE")).isZero();
        assertThat(v.older()).hasSize(OpinionAggregator.MAX_OLDER);
        assertThat(v.older().get(0).publishedAt()).isEqualTo(NOW.minusDays(8));
        assertThat(v.windowFrom()).isEqualTo(NOW.minusDays(7));
    }

    @Test
    @DisplayName("재업로드·편집본, 지난 실행, 인용·회고·언급, 검토 대기·거절은 집계하지 않는다 — 승인은 센다")
    void eligibility() {
        OpinionAggregator.View v = OpinionAggregator.aggregate(List.of(
                item(1L, Stance.POSITIVE, NOW.minusDays(1), StatementType.CURRENT_VIEW, ReviewStatus.AUTO_OK, true, true),
                item(1L, Stance.POSITIVE, NOW.minusDays(1), StatementType.CURRENT_VIEW, ReviewStatus.AUTO_OK, false, false),
                item(1L, Stance.POSITIVE, NOW.minusDays(1), StatementType.QUOTE, ReviewStatus.AUTO_OK, true, false),
                item(1L, Stance.POSITIVE, NOW.minusDays(1), StatementType.PAST_REVIEW, ReviewStatus.AUTO_OK, true, false),
                item(1L, Stance.POSITIVE, NOW.minusDays(1), StatementType.MENTION, ReviewStatus.AUTO_OK, true, false),
                item(1L, Stance.POSITIVE, NOW.minusDays(1), StatementType.CURRENT_VIEW, ReviewStatus.NEEDS_REVIEW, true, false),
                item(1L, Stance.POSITIVE, NOW.minusDays(1), StatementType.CURRENT_VIEW, ReviewStatus.REJECTED, true, false),
                item(5L, Stance.CONDITIONAL, NOW.minusDays(1), StatementType.CURRENT_VIEW, ReviewStatus.APPROVED, true, false)),
                NOW, 7);
        assertThat(v.summary().totalStatements()).isEqualTo(1);
        assertThat(v.summary().personCounts().get("CONDITIONAL")).isEqualTo(1);
        assertThat(v.summary().totalPersons()).isEqualTo(1);
    }

    @Test
    @DisplayName("의견이 없으면 빈 집계 — 0 을 '의견 없음'으로 정직하게")
    void empty() {
        OpinionAggregator.View v = OpinionAggregator.aggregate(List.of(), NOW, 7);
        assertThat(v.hasCurrent()).isFalse();
        assertThat(v.summary().latestPublishedAt()).isNull();
        assertThat(v.summary().statementCounts().values()).allMatch(n -> n == 0);
    }
}
