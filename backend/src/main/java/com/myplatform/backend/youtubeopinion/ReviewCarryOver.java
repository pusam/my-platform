package com.myplatform.backend.youtubeopinion;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * 재분석 때 사람 검토 결정(승인·거절·수동 종목)을 이어받을지 — 순수 함수(2026-09-29).
 *
 * <p><b>발언 식별과 결정 승계는 다른 질문이다.</b> {@code statementKey}(영상·종목·발언자·시작 줄·유형)는 "같은 발언인가"만
 * 답한다 — 실행 사이 이력을 잇는 데 쓴다. 사람의 결정은 그 사람이 <b>본 내용</b>에 대한 것이라, 키가 같아도 내용이 다르면
 * 잇지 않는다. 내용은 {@link #fingerprint} 로 비교한다: 화면에 나가는 문구(요약·근거 설명·조건·기간·목표가·근거 발췌),
 * 의견·유형, 발언자, 종목 표기, 위치(초), 그리고 <b>그 구간의 자막 원문</b>. 자막 원문을 넣는 이유는 자막이 고쳐지면 같은
 * 줄 번호가 다른 말을 가리킬 수 있어서다 — 버전 번호가 아니라 원문으로 비교하므로 무관한 줄만 고친 자막은 다시 검토시키지 않는다.
 *
 * <ul>
 *   <li><b>승인</b> — 내용이 같고 <b>새 검토 사유가 없을 때만</b> 잇는다(수동 종목 포함). 아니면 <b>NEEDS_REVIEW</b> +
 *       {@value #NOT_CARRIED} — 이번 검증에 걸릴 게 없어도 자동 통과시키지 않는다(사람이 본 발언이 바뀌었다). 수동 종목도 버린다.</li>
 *   <li><b>거절</b> — 내용이 같으면 잇는다. 바뀌었으면 <b>NEEDS_REVIEW</b> + {@value #PREVIOUSLY_REJECTED}.</li>
 *   <li><b>키가 달라진 경우</b>(모델이 시작 줄을 다르게 잡음·종목이나 발언자가 바뀜) — 직전 실행의 사람 결정 중 이번 실행에
 *       같은 키의 후속이 없는 것("남겨진 결정")과 시간이 겹치면 NEEDS_REVIEW(거절이 있으면 {@value #PREVIOUSLY_REJECTED},
 *       아니면 {@value #NOT_CARRIED}). 종목·발언자는 따지지 않는다 — 바로 그게 바뀌어 키가 달라졌을 수 있다.
 *       경계 한 점만 닿는 이웃 발언은 겹침이 아니다.</li>
 *   <li><b>자동 통과(AUTO_OK)는 사람 검토를 받은 적 없는 발언의 규칙이다</b> — 새 발언이거나 직전에 자동 통과·검토 대기였던
 *       발언은 바뀌었어도 이번 검증 결과 그대로다.</li>
 * </ul>
 * 인용·언급처럼 원래 화면에 안 나가는 유형은 상태를 바꾸지 않고 표시만 남긴다(검토 목록을 늘리지 않는다).
 * 지난 실행의 행은 건드리지 않는다 — 결정은 새 실행의 새 행에만 쓰고, 이전 결정은 그 행에 이력으로 남는다.
 */
final class ReviewCarryOver {

    static final String CARRIED = "REVIEW_CARRIED";
    static final String NOT_CARRIED = "REVIEW_NOT_CARRIED";
    static final String PREVIOUSLY_REJECTED = "PREVIOUSLY_REJECTED";

    private ReviewCarryOver() {}

    /** 새 행에 쓸 검토 상태 — 이번 검증 결과에서 시작해 승계 규칙을 적용한 값. */
    record Outcome(YtOpinion.ReviewStatus reviewStatus, String reviewedBy, LocalDateTime reviewedAt,
                   String stockCode, YtOpinion.MappingStatus mappingStatus, String reviewReasons) {}

    /**
     * @param current         이번 실행이 검증한 발언
     * @param currentSpan     이번 자막에서 그 발언 구간의 원문({@link #spanText}) — 모르면 null(잇지 않는다)
     * @param sameKey         직전 현재 실행에서 같은 발언 키의 행 — 없으면 null
     * @param previousSpan    직전 실행의 자막에서 {@code sameKey} 구간의 원문 — 모르면 null(잇지 않는다)
     * @param orphanedDecided 직전 현재 실행의 사람 결정(승인·거절) 중 이번 실행에 같은 키의 후속이 없는 행
     *                        ({@link #orphanedDecided}) — 키가 바뀐 발언이 사람 결정을 조용히 벗어나지 못하게
     */
    static Outcome decide(OpinionValidator.Validated current, String currentSpan, YtOpinion sameKey, String previousSpan,
                          List<YtOpinion> orphanedDecided) {
        YtOpinion.ReviewStatus status = current.reviewStatus();
        boolean displayable = current.statementType() == YtOpinion.StatementType.CURRENT_VIEW;
        List<String> markers = new ArrayList<>();

        if (sameKey != null && isDecided(sameKey)) {
            boolean sameContent = currentSpan != null && previousSpan != null
                    && fingerprint(sameKey, previousSpan).equals(fingerprint(current, currentSpan));
            if (sameKey.getReviewStatus() == YtOpinion.ReviewStatus.REJECTED) {
                if (sameContent) return carried(current, sameKey, current.stockCode(), current.mappingStatus());
                markers.add(PREVIOUSLY_REJECTED);
            } else {
                boolean noNewReason = reviewReasons(sameKey.getReviewReasons()).containsAll(reviewReasons(current.reviewReasons()));
                if (sameContent && noNewReason) {
                    boolean manual = sameKey.getMappingStatus() == YtOpinion.MappingStatus.MANUAL;
                    return carried(current, sameKey, manual ? sameKey.getStockCode() : current.stockCode(),
                            manual ? YtOpinion.MappingStatus.MANUAL : current.mappingStatus());
                }
                markers.add(NOT_CARRIED);
            }
            // 사람이 본 발언이 바뀌었다 — 검토받은 적 없는 발언의 자동 통과 규칙을 쓰지 않는다
            if (displayable) status = YtOpinion.ReviewStatus.NEEDS_REVIEW;
        } else if (sameKey == null) {
            // 같은 키가 없다 — 새 발언이거나, 키가 바뀌어(시작 줄·종목·발언자) 이어지지 못한 사람 결정의 후속일 수 있다
            String marker = overlapMarker(current, orphanedDecided);
            if (marker != null) {
                markers.add(marker);
                if (displayable) status = YtOpinion.ReviewStatus.NEEDS_REVIEW;
            }
        }
        // sameKey 가 사람 결정 없는 행(자동 통과·검토 대기)이면 검토받은 적 없는 발언 — 이번 검증 결과 그대로
        return new Outcome(status, null, null, current.stockCode(), current.mappingStatus(),
                reasons(markers, current.reviewReasons()));
    }

    static boolean isDecided(YtOpinion row) {
        return row.getReviewStatus() == YtOpinion.ReviewStatus.APPROVED || row.getReviewStatus() == YtOpinion.ReviewStatus.REJECTED;
    }

    /** 직전 행 중 사람 결정이 있고 이번 실행에 같은 키의 후속이 없는 것. */
    static List<YtOpinion> orphanedDecided(List<YtOpinion> previousRows, java.util.Set<String> currentKeys) {
        if (previousRows == null) return List.of();
        return previousRows.stream().filter(ReviewCarryOver::isDecided)
                .filter(p -> !currentKeys.contains(p.getStatementKey())).toList();
    }

    private static Outcome carried(OpinionValidator.Validated current, YtOpinion previous, String stockCode,
                                   YtOpinion.MappingStatus mapping) {
        return new Outcome(previous.getReviewStatus(), previous.getReviewedBy(), previous.getReviewedAt(), stockCode, mapping,
                reasons(List.of(CARRIED), current.reviewReasons()));
    }

    /** 겹치는 남겨진 결정의 표시 — 거절이 하나라도 있으면 {@value #PREVIOUSLY_REJECTED}, 승인뿐이면 {@value #NOT_CARRIED}, 없으면 null. */
    static String overlapMarker(OpinionValidator.Validated v, List<YtOpinion> orphanedDecided) {
        if (orphanedDecided == null) return null;
        boolean approved = false;
        for (YtOpinion p : orphanedDecided) {
            if (!overlaps(p.getStartSec(), p.getEndSec(), v.startSec(), v.endSec())) continue;
            if (p.getReviewStatus() == YtOpinion.ReviewStatus.REJECTED) return PREVIOUSLY_REJECTED;
            if (p.getReviewStatus() == YtOpinion.ReviewStatus.APPROVED) approved = true;
        }
        return approved ? NOT_CARRIED : null;
    }

    /** 반열린 구간 겹침 — 경계 한 점만 닿는 이웃 발언(앞 발언 끝 = 다음 발언 시작)은 겹침이 아니다. 같은 시작은 겹침(길이 0 포함). */
    static boolean overlaps(int aStart, int aEnd, int bStart, int bEnd) {
        return aStart == bStart || (aStart < bEnd && bStart < aEnd);
    }

    /** 사람이 검토한 내용의 지문 — 이 값이 같으면 "같은 내용". 구간 원문을 모르면 쓰지 않는다(호출부가 null 을 거른다). */
    static String fingerprint(OpinionValidator.Validated v, String span) {
        return fingerprint(v.statementType(), v.stance(), v.personId(), v.speakerLabel(), v.stockNameRaw(), v.startSec(),
                v.endSec(), v.claimSummary(), v.rationale(), v.conditions(), v.horizon(), v.targetPrice(), v.evidenceQuote(), span);
    }

    static String fingerprint(YtOpinion o, String span) {
        return fingerprint(o.getStatementType(), o.getStance(), o.getPersonId(), o.getSpeakerLabel(), o.getStockNameRaw(),
                o.getStartSec(), o.getEndSec(), o.getClaimSummary(), o.getRationale(), o.getConditions(), o.getHorizon(),
                o.getTargetPrice(), o.getEvidenceQuote(), span);
    }

    private static String fingerprint(YtOpinion.StatementType type, YtOpinion.Stance stance, Long personId, String speakerLabel,
                                      String stockNameRaw, Integer startSec, Integer endSec, String summary, String rationale,
                                      String conditions, String horizon, BigDecimal targetPrice, String evidence, String span) {
        StringJoiner j = new StringJoiner("\u001F");
        j.add(String.valueOf(type)).add(String.valueOf(stance)).add(String.valueOf(personId)).add(norm(speakerLabel))
                .add(String.valueOf(NameKeys.key(stockNameRaw))).add(String.valueOf(startSec)).add(String.valueOf(endSec))
                .add(norm(summary)).add(norm(rationale)).add(norm(conditions)).add(norm(horizon))
                .add(targetPrice == null ? "-" : targetPrice.stripTrailingZeros().toPlainString())
                .add(norm(evidence)).add(norm(span));
        return OpinionValidator.sha256(j.toString());
    }

    /** 발언 구간 [startSec, endSec] 안에 드는 자막 줄의 원문(화자 표기 포함). 자막을 모르면 null. */
    static String spanText(List<TranscriptParser.Cue> cues, int startSec, int endSec) {
        if (cues == null) return null;
        StringBuilder sb = new StringBuilder();
        for (TranscriptParser.Cue c : cues) {
            if (c.startSec() < startSec || c.endSec() > endSec) continue;
            if (c.speaker() != null) sb.append(c.speaker()).append(": ");
            sb.append(c.text()).append('\n');
        }
        return sb.toString();
    }

    /** 저장된 사유 문자열에서 검토를 부르는 사유만 — 승계 표시(REVIEW_CARRIED 등)·참고 사유는 뺀다. */
    static Set<String> reviewReasons(String csv) {
        Set<String> out = new TreeSet<>();
        if (csv == null) return out;
        for (String r : csv.split(",")) {
            String t = r.trim();
            if (OpinionValidator.REVIEW_REASONS.contains(t)) out.add(t);
        }
        return out;
    }

    /** 승계 표시를 앞에 둔다 — 사유 칸(300자)이 잘려도 표시는 남게. */
    private static String reasons(List<String> markers, String validated) {
        StringJoiner j = new StringJoiner(",");
        markers.forEach(j::add);
        if (validated != null) j.add(validated);
        return j.length() == 0 ? null : OpinionValidator.cut(j.toString(), 300);
    }

    private static String norm(String s) {
        return s == null ? "-" : s.replaceAll("\\s+", " ").trim();
    }
}
