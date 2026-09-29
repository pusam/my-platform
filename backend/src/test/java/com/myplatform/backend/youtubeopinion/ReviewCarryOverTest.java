package com.myplatform.backend.youtubeopinion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재분석 검토 결정 승계 규칙 — {@link ReviewCarryOver} 순수 함수.
 *
 * <p>발언 식별(statementKey)이 같아도 <b>사람이 본 내용</b>이 달라지면 결정을 잇지 않는다. 여기서는 "내용"에 들어가는
 * 항목을 하나씩 바꿔 지문이 달라지는지, 표기만 다른 것(공백·소수 자릿수)은 같은 내용으로 보는지, 그리고 결정별(승인·거절·
 * 수동 종목) 승계 조건을 고정한다. 값은 전부 테스트 전용 가상 값이다.
 */
class ReviewCarryOverTest {

    static final LocalDateTime REVIEWED_AT = LocalDateTime.of(2026, 9, 28, 21, 0);
    static final String SPAN = "테스트운영자: 동명회사는 지금 사도 좋습니다\n";

    /** 검증 결과 — 기본은 '동명회사' 긍정, 종목 모호(검토 필요). 필요한 항목만 바꿔 쓴다. */
    record V(String key, String speakerLabel, Long personId, int startSec, int endSec, String stockNameRaw, String stockCode,
             YtOpinion.MappingStatus mapping, YtOpinion.Stance stance, YtOpinion.StatementType type, String summary,
             String rationale, String conditions, String horizon, BigDecimal target, String evidence,
             YtOpinion.ReviewStatus status, String reasons) {

        static V base() {
            return new V("k1", "테스트운영자", 1L, 90, 100, "동명회사", null, YtOpinion.MappingStatus.AMBIGUOUS,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "지금 사도 좋다", "실적이 좋다",
                    null, "1년", new BigDecimal("100000"), "동명회사는 지금 사도 좋습니다",
                    YtOpinion.ReviewStatus.NEEDS_REVIEW, "STOCK_AMBIGUOUS");
        }

        OpinionValidator.Validated validated() {
            return new OpinionValidator.Validated(key, speakerLabel, personId, YtVideoParticipant.Role.HOST, startSec, endSec,
                    stockNameRaw, stockCode, mapping, stance, type, summary, rationale, conditions, horizon, target, evidence,
                    status, reasons);
        }

        /** 이 검증 결과가 지난 실행에 저장된 뒤 사람이 결정한 행. */
        YtOpinion reviewed(YtOpinion.ReviewStatus decision, String manualCode) {
            return YtOpinion.builder().id(7L).runId(1L).videoId("TESTvid0001").statementKey(key).speakerLabel(speakerLabel)
                    .personId(personId).speakerRole(YtVideoParticipant.Role.HOST).startSec(startSec).endSec(endSec)
                    .stockNameRaw(stockNameRaw).stockCode(manualCode != null ? manualCode : stockCode)
                    .mappingStatus(manualCode != null ? YtOpinion.MappingStatus.MANUAL : mapping).stance(stance)
                    .statementType(type).claimSummary(summary).rationale(rationale).conditions(conditions).horizon(horizon)
                    .targetPrice(target == null ? null : target.setScale(2)).evidenceQuote(evidence)
                    .reviewStatus(decision).reviewReasons(reasons).reviewedBy("test-admin").reviewedAt(REVIEWED_AT)
                    .analyzedAt(REVIEWED_AT.minusHours(1)).build();
        }
    }

    /** 같은 키의 직전 행이 있는 경우 — 그 행은 이번 실행에 후속(같은 키)이 있으니 "남겨진 결정"이 아니다. */
    static ReviewCarryOver.Outcome decide(V now, YtOpinion previous) {
        return ReviewCarryOver.decide(now.validated(), SPAN, previous, SPAN, List.of());
    }

    @Nested
    @DisplayName("내용 지문 — 사람이 본 항목이 하나라도 바뀌면 다른 내용")
    class Fingerprint {

        final V base = V.base();
        final String fp = ReviewCarryOver.fingerprint(base.validated(), SPAN);

        String fp(V v) { return ReviewCarryOver.fingerprint(v.validated(), SPAN); }

        V copy(String summary, String rationale, String conditions, String horizon, BigDecimal target, String evidence,
               YtOpinion.Stance stance, YtOpinion.StatementType type, String speaker, Long person, String stock,
               int start, int end) {
            return new V(base.key(), speaker, person, start, end, stock, base.stockCode(), base.mapping(), stance, type,
                    summary, rationale, conditions, horizon, target, evidence, base.status(), base.reasons());
        }

        @Test
        @DisplayName("저장된 행과 새 검증 결과가 같은 내용이면 지문이 같다(목표가 소수 자릿수·공백 차이는 무시)")
        void sameContentSameFingerprint() {
            assertThat(ReviewCarryOver.fingerprint(base.reviewed(YtOpinion.ReviewStatus.APPROVED, null), SPAN)).isEqualTo(fp);
            V spaced = copy("지금  사도 좋다 ", base.rationale(), null, "1년", new BigDecimal("100000.00"), " 동명회사는 지금 사도 좋습니다",
                    base.stance(), base.type(), base.speakerLabel(), base.personId(), base.stockNameRaw(), 90, 100);
            assertThat(fp(spaced)).isEqualTo(fp);
        }

        @Test
        @DisplayName("의견·유형·요약·근거 설명·조건·기간·목표가·근거 발췌가 바뀌면 다른 내용")
        void opinionFieldsChangeFingerprint() {
            List<V> changed = List.of(
                    copy(base.summary(), base.rationale(), null, "1년", base.target(), base.evidence(), YtOpinion.Stance.NEGATIVE,
                            base.type(), base.speakerLabel(), base.personId(), base.stockNameRaw(), 90, 100),
                    copy(base.summary(), base.rationale(), null, "1년", base.target(), base.evidence(), base.stance(),
                            YtOpinion.StatementType.QUOTE, base.speakerLabel(), base.personId(), base.stockNameRaw(), 90, 100),
                    copy("지금은 비중을 늘릴 때", base.rationale(), null, "1년", base.target(), base.evidence(), base.stance(),
                            base.type(), base.speakerLabel(), base.personId(), base.stockNameRaw(), 90, 100),
                    copy(base.summary(), "수주가 늘었다", null, "1년", base.target(), base.evidence(), base.stance(),
                            base.type(), base.speakerLabel(), base.personId(), base.stockNameRaw(), 90, 100),
                    copy(base.summary(), base.rationale(), "조정이 오면", "1년", base.target(), base.evidence(), base.stance(),
                            base.type(), base.speakerLabel(), base.personId(), base.stockNameRaw(), 90, 100),
                    copy(base.summary(), base.rationale(), null, "3개월", base.target(), base.evidence(), base.stance(),
                            base.type(), base.speakerLabel(), base.personId(), base.stockNameRaw(), 90, 100),
                    copy(base.summary(), base.rationale(), null, "1년", new BigDecimal("120000"), base.evidence(), base.stance(),
                            base.type(), base.speakerLabel(), base.personId(), base.stockNameRaw(), 90, 100),
                    copy(base.summary(), base.rationale(), null, "1년", null, base.evidence(), base.stance(),
                            base.type(), base.speakerLabel(), base.personId(), base.stockNameRaw(), 90, 100),
                    copy(base.summary(), base.rationale(), null, "1년", base.target(), "지금 사도 좋습니다", base.stance(),
                            base.type(), base.speakerLabel(), base.personId(), base.stockNameRaw(), 90, 100));
            for (V v : changed) assertThat(fp(v)).as(v.toString()).isNotEqualTo(fp);
        }

        @Test
        @DisplayName("발언자·종목 표기·위치(근거 구간)가 바뀌면 다른 내용")
        void identityAndSpanFieldsChangeFingerprint() {
            List<V> changed = List.of(
                    copy(base.summary(), base.rationale(), null, "1년", base.target(), base.evidence(), base.stance(), base.type(),
                            "테스트출연자", 2L, base.stockNameRaw(), 90, 100),
                    copy(base.summary(), base.rationale(), null, "1년", base.target(), base.evidence(), base.stance(), base.type(),
                            base.speakerLabel(), null, base.stockNameRaw(), 90, 100),
                    copy(base.summary(), base.rationale(), null, "1년", base.target(), base.evidence(), base.stance(), base.type(),
                            base.speakerLabel(), base.personId(), "동명회사우", 90, 100),
                    copy(base.summary(), base.rationale(), null, "1년", base.target(), base.evidence(), base.stance(), base.type(),
                            base.speakerLabel(), base.personId(), base.stockNameRaw(), 85, 100),
                    copy(base.summary(), base.rationale(), null, "1년", base.target(), base.evidence(), base.stance(), base.type(),
                            base.speakerLabel(), base.personId(), base.stockNameRaw(), 90, 110));
            for (V v : changed) assertThat(fp(v)).as(v.toString()).isNotEqualTo(fp);
        }

        @Test
        @DisplayName("같은 줄·같은 필드라도 그 구간의 자막 원문이 바뀌면 다른 내용(자막 버전)")
        void spanTextChangesFingerprint() {
            assertThat(ReviewCarryOver.fingerprint(base.validated(), "테스트운영자: 동명회사는 지금 사도 좋습니다 다만 이미 팔았어요\n"))
                    .isNotEqualTo(fp);
        }

        @Test
        @DisplayName("구간 원문은 그 시간 안에 드는 줄만 — 앞뒤 줄은 바뀌어도 무관")
        void spanTextCoversOnlyTheStatementWindow() {
            List<TranscriptParser.Cue> v1 = List.of(new TranscriptParser.Cue(0, 9, "테스트운영자", "오늘은 반도체 얘기"),
                    new TranscriptParser.Cue(90, 100, "테스트운영자", "동명회사는 지금 사도 좋습니다"),
                    new TranscriptParser.Cue(100, 110, "테스트출연자", "저는 다르게 봅니다"));
            List<TranscriptParser.Cue> v2 = List.of(new TranscriptParser.Cue(0, 9, "테스트운영자", "오늘은 전지 얘기"),
                    new TranscriptParser.Cue(90, 100, "테스트운영자", "동명회사는 지금 사도 좋습니다"),
                    new TranscriptParser.Cue(100, 110, "테스트출연자", "저도 같습니다"));
            assertThat(ReviewCarryOver.spanText(v1, 90, 100)).isEqualTo(SPAN).isEqualTo(ReviewCarryOver.spanText(v2, 90, 100));
            assertThat(ReviewCarryOver.spanText(null, 90, 100)).as("자막을 모르면 null").isNull();
        }
    }

    @Nested
    @DisplayName("승인 — 같은 내용·새 검토 사유 없음일 때만 잇는다")
    class Approved {

        @Test
        @DisplayName("같은 내용이면 승인·검토자·시각·수동 종목을 잇고 REVIEW_CARRIED 를 앞에 남긴다")
        void identicalCarriesApprovalAndManualCode() {
            V v = V.base();
            ReviewCarryOver.Outcome o = decide(v, v.reviewed(YtOpinion.ReviewStatus.APPROVED, "111111"));
            assertThat(o.reviewStatus()).isEqualTo(YtOpinion.ReviewStatus.APPROVED);
            assertThat(o.reviewedBy()).isEqualTo("test-admin");
            assertThat(o.reviewedAt()).isEqualTo(REVIEWED_AT);
            assertThat(o.stockCode()).isEqualTo("111111");
            assertThat(o.mappingStatus()).isEqualTo(YtOpinion.MappingStatus.MANUAL);
            assertThat(o.reviewReasons()).isEqualTo("REVIEW_CARRIED,STOCK_AMBIGUOUS");
        }

        @Test
        @DisplayName("관리자가 확인된 종목을 다른 코드로 바로잡았으면 같은 내용 재분석에도 그 코드를 잇는다")
        void manualOverrideOfVerifiedCodeCarries() {
            V v = new V("k1", "테스트운영자", 1L, 90, 100, "동명회사", "222222", YtOpinion.MappingStatus.VERIFIED,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "지금 사도 좋다", null, null, null, null,
                    "동명회사는 지금 사도 좋습니다", YtOpinion.ReviewStatus.AUTO_OK, null);
            ReviewCarryOver.Outcome o = decide(v, v.reviewed(YtOpinion.ReviewStatus.APPROVED, "111111"));
            assertThat(o.stockCode()).isEqualTo("111111");
            assertThat(o.mappingStatus()).isEqualTo(YtOpinion.MappingStatus.MANUAL);
        }

        @Test
        @DisplayName("검토 사유가 줄기만 했으면 잇는다 — 사람이 이미 본 사유 안이다")
        void fewerReasonsStillCarry() {
            V before = new V("k1", "테스트운영자", 1L, 90, 100, "동명회사", null, YtOpinion.MappingStatus.AMBIGUOUS,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "지금 사도 좋다", null, null, null, null,
                    "동명회사는 지금 사도 좋습니다", YtOpinion.ReviewStatus.NEEDS_REVIEW, "STOCK_AMBIGUOUS,SPEAKER_CONFLICT");
            V now = new V("k1", "테스트운영자", 1L, 90, 100, "동명회사", null, YtOpinion.MappingStatus.AMBIGUOUS,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "지금 사도 좋다", null, null, null, null,
                    "동명회사는 지금 사도 좋습니다", YtOpinion.ReviewStatus.NEEDS_REVIEW, "STOCK_AMBIGUOUS");
            assertThat(decide(now, before.reviewed(YtOpinion.ReviewStatus.APPROVED, "111111")).reviewStatus())
                    .isEqualTo(YtOpinion.ReviewStatus.APPROVED);
        }

        @Test
        @DisplayName("새 검토 사유가 생기면 잇지 않는다 — 이번 검증 결과(검토 필요) 그대로, 수동 종목도 버린다")
        void newReasonBlocksCarry() {
            V before = V.base();
            V now = new V("k1", "테스트운영자", 1L, 90, 100, "동명회사", null, YtOpinion.MappingStatus.AMBIGUOUS,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "지금 사도 좋다", "실적이 좋다", null, "1년",
                    new BigDecimal("100000"), "동명회사는 지금 사도 좋습니다", YtOpinion.ReviewStatus.NEEDS_REVIEW,
                    "STOCK_AMBIGUOUS,SPEAKER_CONFLICT");
            ReviewCarryOver.Outcome o = decide(now, before.reviewed(YtOpinion.ReviewStatus.APPROVED, "111111"));
            assertThat(o.reviewStatus()).isEqualTo(YtOpinion.ReviewStatus.NEEDS_REVIEW);
            assertThat(o.stockCode()).isNull();
            assertThat(o.mappingStatus()).isEqualTo(YtOpinion.MappingStatus.AMBIGUOUS);
            assertThat(o.reviewedBy()).isNull();
            assertThat(o.reviewReasons()).isEqualTo("REVIEW_NOT_CARRIED,STOCK_AMBIGUOUS,SPEAKER_CONFLICT");
        }

        @Test
        @DisplayName("참고 사유(검토를 부르지 않는 것)의 변화는 새 검토 사유가 아니다")
        void informationalReasonIsNotANewReviewReason() {
            V before = V.base();
            V now = new V("k1", "테스트운영자", 1L, 90, 100, "동명회사", null, YtOpinion.MappingStatus.AMBIGUOUS,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "지금 사도 좋다", "실적이 좋다", null, "1년",
                    new BigDecimal("100000"), "동명회사는 지금 사도 좋습니다", YtOpinion.ReviewStatus.NEEDS_REVIEW,
                    "STOCK_AMBIGUOUS,SOLE_PARTICIPANT");
            assertThat(decide(now, before.reviewed(YtOpinion.ReviewStatus.APPROVED, "111111")).reviewStatus())
                    .isEqualTo(YtOpinion.ReviewStatus.APPROVED);
        }

        @Test
        @DisplayName("승인한 발언이 바뀌면 이번 검증에 걸릴 게 없어도(자동 통과감이어도) NEEDS_REVIEW — 자동 통과는 검토받은 적 없는 발언의 규칙")
        void changedApprovedGoesToReviewEvenIfClean() {
            V before = new V("k1", "테스트운영자", 1L, 90, 100, "동명회사", "222222", YtOpinion.MappingStatus.VERIFIED,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "지금 사도 좋다", null, null, null, null,
                    "동명회사는 지금 사도 좋습니다", YtOpinion.ReviewStatus.NEEDS_REVIEW, "CONDITION_MARKER");
            V now = new V("k1", "테스트운영자", 1L, 90, 100, "동명회사", "222222", YtOpinion.MappingStatus.VERIFIED,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "실적이 좋아 긍정적", null, null, null, null,
                    "동명회사는 지금 사도 좋습니다", YtOpinion.ReviewStatus.AUTO_OK, null);
            ReviewCarryOver.Outcome o = decide(now, before.reviewed(YtOpinion.ReviewStatus.APPROVED, null));
            assertThat(o.reviewStatus()).isEqualTo(YtOpinion.ReviewStatus.NEEDS_REVIEW);
            assertThat(o.reviewedBy()).isNull();
            assertThat(o.reviewReasons()).isEqualTo("REVIEW_NOT_CARRIED");
        }

        @Test
        @DisplayName("승인한 발언의 종목이 바뀌어 키가 달라져도, 그 구간의 결정이 후속 없이 남았으면 NEEDS_REVIEW")
        void orphanedApprovedOverlapGoesToReview() {
            YtOpinion approved = new V("k1", "테스트운영자", 1L, 65, 72, "삼성전자", "005930", YtOpinion.MappingStatus.VERIFIED,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "지금 사도 좋다", null, null, null, null,
                    "삼성전자는 지금 사도 좋다고 봅니다", YtOpinion.ReviewStatus.AUTO_OK, null)
                    .reviewed(YtOpinion.ReviewStatus.APPROVED, null);
            V stockChanged = new V("k9", "테스트운영자", 1L, 65, 72, "SK하이닉스", "000660", YtOpinion.MappingStatus.VERIFIED,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "지금 사도 좋다", null, null, null, null,
                    "삼성전자는 지금 사도 좋다고 봅니다", YtOpinion.ReviewStatus.AUTO_OK, null);

            ReviewCarryOver.Outcome o = ReviewCarryOver.decide(stockChanged.validated(), SPAN, null, null, List.of(approved));

            assertThat(o.reviewStatus()).isEqualTo(YtOpinion.ReviewStatus.NEEDS_REVIEW);
            assertThat(o.reviewReasons()).isEqualTo("REVIEW_NOT_CARRIED");
            assertThat(o.stockCode()).as("새 종목은 이번 검증 결과 그대로 — 사람이 다시 본다").isEqualTo("000660");
            assertThat(o.reviewedBy()).isNull();
        }

        @Test
        @DisplayName("구간 원문을 모르면(직전 자막을 못 읽음) 잇지 않는다 — 모르는 것을 같다고 보지 않는다")
        void unknownSpanDoesNotCarry() {
            V v = V.base();
            YtOpinion previous = v.reviewed(YtOpinion.ReviewStatus.APPROVED, "111111");
            ReviewCarryOver.Outcome o = ReviewCarryOver.decide(v.validated(), SPAN, previous, null, List.of());
            assertThat(o.reviewStatus()).isEqualTo(YtOpinion.ReviewStatus.NEEDS_REVIEW);
            assertThat(o.reviewReasons()).startsWith("REVIEW_NOT_CARRIED");
            assertThat(o.stockCode()).isNull();
        }
    }

    @Test
    @DisplayName("검토받은 적 없는 발언(자동 통과였던 것)은 바뀌어도 새 발언과 같은 규칙 — 걸릴 게 없으면 자동 통과, 표시 없음")
    void unreviewedChangedStaysFresh() {
        V before = new V("k1", "테스트운영자", 1L, 65, 72, "삼성전자", "005930", YtOpinion.MappingStatus.VERIFIED,
                YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "지금 사도 좋다", null, null, null, null,
                "삼성전자는 지금 사도 좋다고 봅니다", YtOpinion.ReviewStatus.AUTO_OK, null);
        V now = new V("k1", "테스트운영자", 1L, 65, 72, "삼성전자", "005930", YtOpinion.MappingStatus.VERIFIED,
                YtOpinion.Stance.NEGATIVE, YtOpinion.StatementType.CURRENT_VIEW, "지금은 판다", null, null, null, null,
                "삼성전자는 지금 사도 좋다고 봅니다", YtOpinion.ReviewStatus.AUTO_OK, null);
        YtOpinion previous = before.reviewed(YtOpinion.ReviewStatus.AUTO_OK, null);
        previous.setReviewedBy(null);
        previous.setReviewedAt(null);

        ReviewCarryOver.Outcome o = decide(now, previous);

        assertThat(o.reviewStatus()).isEqualTo(YtOpinion.ReviewStatus.AUTO_OK);
        assertThat(o.reviewReasons()).isNull();
    }

    @Nested
    @DisplayName("거절 — 재분석만으로 공개되지 않는다")
    class Rejected {

        @Test
        @DisplayName("같은 내용이면 거절을 잇는다")
        void identicalStaysRejected() {
            V v = V.base();
            ReviewCarryOver.Outcome o = decide(v, v.reviewed(YtOpinion.ReviewStatus.REJECTED, null));
            assertThat(o.reviewStatus()).isEqualTo(YtOpinion.ReviewStatus.REJECTED);
            assertThat(o.reviewedBy()).isEqualTo("test-admin");
            assertThat(o.reviewReasons()).startsWith("REVIEW_CARRIED");
        }

        @Test
        @DisplayName("바뀌었으면 자동 통과여도 검토로 — PREVIOUSLY_REJECTED")
        void changedGoesToReviewEvenIfClean() {
            V before = new V("k1", "테스트운영자", 1L, 90, 100, "동명회사", "222222", YtOpinion.MappingStatus.VERIFIED,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "지금 사도 좋다", null, null, null, null,
                    "동명회사는 지금 사도 좋습니다", YtOpinion.ReviewStatus.AUTO_OK, null);
            V now = new V("k1", "테스트운영자", 1L, 90, 100, "동명회사", "222222", YtOpinion.MappingStatus.VERIFIED,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "비중을 늘린다", null, null, null, null,
                    "동명회사는 지금 사도 좋습니다", YtOpinion.ReviewStatus.AUTO_OK, null);
            ReviewCarryOver.Outcome o = decide(now, before.reviewed(YtOpinion.ReviewStatus.REJECTED, null));
            assertThat(o.reviewStatus()).isEqualTo(YtOpinion.ReviewStatus.NEEDS_REVIEW);
            assertThat(o.reviewReasons()).isEqualTo("PREVIOUSLY_REJECTED");
        }

        @Test
        @DisplayName("인용·언급처럼 원래 화면에 안 나가는 유형은 검토 목록을 늘리지 않는다(표시만)")
        void changedNonCurrentViewKeepsStatus() {
            V before = new V("k1", "테스트운영자", 1L, 90, 100, "동명회사", "222222", YtOpinion.MappingStatus.VERIFIED,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.MENTION, "언급", null, null, null, null,
                    "동명회사는 지금 사도 좋습니다", YtOpinion.ReviewStatus.AUTO_OK, null);
            V now = new V("k1", "테스트운영자", 1L, 90, 100, "동명회사", "222222", YtOpinion.MappingStatus.VERIFIED,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.MENTION, "다시 언급", null, null, null, null,
                    "동명회사는 지금 사도 좋습니다", YtOpinion.ReviewStatus.AUTO_OK, null);
            ReviewCarryOver.Outcome o = decide(now, before.reviewed(YtOpinion.ReviewStatus.REJECTED, null));
            assertThat(o.reviewStatus()).isEqualTo(YtOpinion.ReviewStatus.AUTO_OK);
            assertThat(o.reviewReasons()).isEqualTo("PREVIOUSLY_REJECTED");
        }

        @Test
        @DisplayName("키가 달라도(시작 줄이 흔들림) 후속 없이 남은 거절과 시간이 겹치면 검토로")
        void driftedKeyOverlappingRejectedGoesToReview() {
            V rejectedBefore = new V("k1", "테스트운영자", 1L, 65, 80, "삼성전자", "005930", YtOpinion.MappingStatus.VERIFIED,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "지금 사도 좋다", null, null, null, null,
                    "삼성전자는 지금 사도 좋다고 봅니다", YtOpinion.ReviewStatus.AUTO_OK, null);
            YtOpinion rejected = rejectedBefore.reviewed(YtOpinion.ReviewStatus.REJECTED, null);
            V drifted = new V("k2", "테스트운영자", 1L, 72, 80, "삼성전자", "005930", YtOpinion.MappingStatus.VERIFIED,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "지금 사도 좋다", null, null, null, null,
                    "삼성전자는 지금 사도 좋다고 봅니다", YtOpinion.ReviewStatus.AUTO_OK, null);

            ReviewCarryOver.Outcome o = ReviewCarryOver.decide(drifted.validated(), SPAN, null, null, List.of(rejected));

            assertThat(o.reviewStatus()).isEqualTo(YtOpinion.ReviewStatus.NEEDS_REVIEW);
            assertThat(o.reviewReasons()).isEqualTo("PREVIOUSLY_REJECTED");
        }

        static YtOpinion rejected6580() {
            return new V("k1", "테스트운영자", 1L, 65, 80, "삼성전자", "005930", YtOpinion.MappingStatus.VERIFIED,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "지금 사도 좋다", null, null, null, null,
                    "삼성전자는 지금 사도 좋다고 봅니다", YtOpinion.ReviewStatus.AUTO_OK, null)
                    .reviewed(YtOpinion.ReviewStatus.REJECTED, null);
        }

        static V autoOk(String key, String speaker, long person, int start, int end, String stock, String code) {
            return new V(key, speaker, person, start, end, stock, code, YtOpinion.MappingStatus.VERIFIED,
                    YtOpinion.Stance.POSITIVE, YtOpinion.StatementType.CURRENT_VIEW, "요약", null, null, null, null,
                    stock + " 좋아요", YtOpinion.ReviewStatus.AUTO_OK, null);
        }

        @Test
        @DisplayName("후속 없이 남은 거절과 겹치면 종목·발언자가 달라져도 검토로 — 같은 발언이 다른 종목·발언자로 다시 잡혔을 수 있다")
        void overlapWithOrphanedRejectedGoesToReviewWhateverChanged() {
            for (V v : List.of(autoOk("k3", "테스트출연자", 2L, 72, 80, "삼성전자", "005930"),
                    autoOk("k4", "테스트운영자", 1L, 72, 80, "SK하이닉스", "000660"))) {
                ReviewCarryOver.Outcome o = ReviewCarryOver.decide(v.validated(), SPAN, null, null, List.of(rejected6580()));
                assertThat(o.reviewStatus()).as(v.key()).isEqualTo(YtOpinion.ReviewStatus.NEEDS_REVIEW);
                assertThat(o.reviewReasons()).as(v.key()).isEqualTo("PREVIOUSLY_REJECTED");
            }
        }

        @Test
        @DisplayName("겹치지 않거나(경계만 닿는 이웃 포함), 결정에 후속이 있으면 무관 — 새 발언은 자동 통과 그대로")
        void unrelatedToRejectedStaysAutoOk() {
            List<ReviewCarryOver.Outcome> outcomes = List.of(
                    ReviewCarryOver.decide(autoOk("k2", "테스트운영자", 1L, 81, 90, "삼성전자", "005930").validated(),
                            SPAN, null, null, List.of(rejected6580())),
                    ReviewCarryOver.decide(autoOk("k5", "테스트운영자", 1L, 80, 90, "삼성전자", "005930").validated(),
                            SPAN, null, null, List.of(rejected6580())),
                    ReviewCarryOver.decide(autoOk("k3", "테스트출연자", 2L, 72, 80, "삼성전자", "005930").validated(),
                            SPAN, null, null, List.of()));
            for (ReviewCarryOver.Outcome o : outcomes) {
                assertThat(o.reviewStatus()).isEqualTo(YtOpinion.ReviewStatus.AUTO_OK);
                assertThat(o.reviewReasons()).isNull();
            }
        }
    }

    @Test
    @DisplayName("사람 결정이 없던 발언(자동 통과·검토 대기)은 이번 검증 결과 그대로")
    void undecidedPreviousIsIgnored() {
        V v = V.base();
        for (YtOpinion.ReviewStatus s : List.of(YtOpinion.ReviewStatus.AUTO_OK, YtOpinion.ReviewStatus.NEEDS_REVIEW)) {
            YtOpinion previous = v.reviewed(s, null);
            previous.setReviewedBy(null);
            previous.setReviewedAt(null);
            ReviewCarryOver.Outcome o = decide(v, previous);
            assertThat(o.reviewStatus()).isEqualTo(YtOpinion.ReviewStatus.NEEDS_REVIEW);
            assertThat(o.reviewReasons()).isEqualTo("STOCK_AMBIGUOUS");
        }
    }
}
