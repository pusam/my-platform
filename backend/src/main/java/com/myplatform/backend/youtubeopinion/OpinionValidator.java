package com.myplatform.backend.youtubeopinion;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 모델이 뽑은 발언 → 저장할 의견. <b>모델 출력을 그대로 믿지 않는 결정적 규칙</b>. 순수 함수(종목 조회만 주입).
 *
 * <ul>
 *   <li>근거 발췌가 해당 자막 줄에 실제로 없으면 검토(EVIDENCE_NOT_IN_TRANSCRIPT) — 화면에 안 나간다.</li>
 *   <li>조건이 붙은 긍정·부정은 CONDITIONAL 로 낮춘다. 반대 방향(조건부→지금 매수)으로는 절대 바꾸지 않는다.</li>
 *   <li>발언자는 자막 줄에 표시된 출연자, 또는 출연자가 한 명뿐일 때만 확정한다. 모델이 댄 이름으로 정하지 않는다.</li>
 *   <li>종목은 마스터로 확인된 것만 붙인다. 모호·미확인·코드 불일치는 검토.</li>
 *   <li>목표가격·투자 기간은 자막 원문에 있을 때만 남기고, 없으면 null(모델 보충 금지).</li>
 *   <li>인용·과거 회고·단순 언급은 기록만 하고 집계하지 않는다(statementType 으로 구분).</li>
 * </ul>
 */
public final class OpinionValidator {

    private OpinionValidator() {}

    public static final int MAX_ACCEPTED_PER_CHUNK = 20;

    public record ParticipantRef(long personId, String displayName, YtVideoParticipant.Role role) {}

    public record Context(String videoId, List<TranscriptParser.Cue> cues, int firstCue, int lastCue,
                          List<ParticipantRef> participants, StockMentionResolver.Lookup stockLookup) {}

    public record Validated(String statementKey, String speakerLabel, Long personId, YtVideoParticipant.Role speakerRole,
                            int startSec, int endSec, String stockNameRaw, String stockCode,
                            YtOpinion.MappingStatus mappingStatus, YtOpinion.Stance stance,
                            YtOpinion.StatementType statementType, String claimSummary, String rationale,
                            String conditions, String horizon, BigDecimal targetPrice, String evidenceQuote,
                            YtOpinion.ReviewStatus reviewStatus, String reviewReasons) {}

    public record Result(List<Validated> accepted, int dropped) {}

    /** 이 사유가 하나라도 있으면 NEEDS_REVIEW — 화면에 나가지 않고 검토 목록으로 간다. */
    static final Set<String> REVIEW_REASONS = Set.of(
            "EVIDENCE_NOT_IN_TRANSCRIPT", "STOCK_NOT_IN_WINDOW", "STOCK_AMBIGUOUS", "STOCK_UNMATCHED", "STOCK_CODE_MISMATCH",
            "SPEAKER_CONFLICT", "CONDITION_MARKER");

    /** 가격·사건 조건 표현 — 긍정·부정에 이게 붙어 있으면 조건부로 낮추고 사람이 확인한다(부풀리는 쪽으로는 안 바꾼다). */
    static final Pattern CONDITION_MARKER = Pattern.compile(
            "(조정|눌림|하락|떨어지|빠지|내려오|깨지|이탈|돌파|올라오|넘어서|넘으|도달|확인되)[\\s가-힣]{0,4}?(면|시|때|경우)"
                    + "|[0-9][0-9,.]*\\s*(원|만\\s*원|만)?\\s*(까지|이하|이상|선|아래|위)[\\s가-힣]{0,4}?(면|시|때)");

    private static final Pattern CUE_MARK = Pattern.compile("\\[c\\d+\\s+[\\d:]+\\]");

    public static Result validate(List<OpinionResponseParser.RawStatement> raws, Context ctx) {
        List<Validated> accepted = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        int dropped = 0;
        Map<String, ParticipantRef> byKey = ctx.participants().stream()
                .collect(Collectors.toMap(p -> NameKeys.key(p.displayName()), Function.identity(), (a, b) -> a));

        for (OpinionResponseParser.RawStatement raw : raws) {
            if (accepted.size() >= MAX_ACCEPTED_PER_CHUNK) { dropped++; continue; }
            Validated v = validateOne(raw, ctx, byKey);
            if (v == null || !keys.add(v.statementKey())) { dropped++; continue; }
            accepted.add(v);
        }
        return new Result(List.copyOf(accepted), dropped);
    }

    private static Validated validateOne(OpinionResponseParser.RawStatement raw, Context ctx,
                                         Map<String, ParticipantRef> participantsByKey) {
        YtOpinion.StatementType type = parseEnum(YtOpinion.StatementType.class, raw.statementType());
        YtOpinion.Stance stance = parseEnum(YtOpinion.Stance.class, raw.stance());
        String stockName = cut(raw.stockName(), 60);
        String summary = cut(raw.claimSummary(), 300);
        String evidence = raw.evidenceQuote() == null ? null
                : cut(CUE_MARK.matcher(raw.evidenceQuote()).replaceAll(" ").replaceAll("\\s+", " ").trim(), 300);
        Integer s = raw.startCue(), e = raw.endCue();
        if (type == null || stance == null || stockName == null || summary == null || evidence == null
                || s == null || e == null) return null;
        if (s > e) { int t = s; s = e; e = t; }
        if (s < ctx.firstCue() || e > ctx.lastCue()) return null;

        List<String> reasons = new ArrayList<>();
        String window = windowText(ctx.cues(), Math.max(ctx.firstCue(), s - 1), Math.min(ctx.lastCue(), e + 1));

        // 1) 근거 발췌는 해당 자막 줄의 원문이어야 한다
        String compactWindow = NameKeys.compact(window);
        if (!compactWindow.contains(NameKeys.compact(evidence))) reasons.add("EVIDENCE_NOT_IN_TRANSCRIPT");
        // 1b) 종목 표기도 그 발언 근처(앞뒤 한 줄) 자막에 있어야 한다 — 없으면 모델이 다른 문맥의 종목을 끌어왔을 수 있다
        if (!compactWindow.contains(NameKeys.compact(stockName))) reasons.add("STOCK_NOT_IN_WINDOW");

        // 2) 발언자 — 자막 표기 또는 단독 출연자만. 모델 이름은 충돌 검출에만 쓴다.
        TranscriptParser.Cue startCue = ctx.cues().get(s);
        ParticipantRef speaker = null;
        if (startCue.speaker() != null) {
            speaker = participantsByKey.get(NameKeys.key(startCue.speaker()));
            String modelKey = NameKeys.key(raw.speaker());
            if (speaker != null && modelKey != null && !modelKey.equals(NameKeys.key(speaker.displayName()))) {
                reasons.add("SPEAKER_CONFLICT");
            }
        } else if (ctx.participants().size() == 1) {
            speaker = ctx.participants().get(0);
            reasons.add("SOLE_PARTICIPANT");
        } else {
            reasons.add("SPEAKER_UNKNOWN");
        }

        // 3) 종목 — 마스터로 확인된 것만
        StockMentionResolver.Result stock = StockMentionResolver.resolve(stockName, raw.stockCode(), ctx.stockLookup());
        switch (stock.status()) {
            case AMBIGUOUS -> reasons.add("STOCK_AMBIGUOUS");
            case UNMATCHED -> reasons.add("STOCK_UNMATCHED");
            case CODE_MISMATCH -> reasons.add("STOCK_CODE_MISMATCH");
            default -> { }
        }

        // 4) 조건부는 조건부로 — 반대 방향 변환 없음
        String conditions = cut(raw.conditions(), 300);
        boolean directional = stance == YtOpinion.Stance.POSITIVE || stance == YtOpinion.Stance.NEGATIVE;
        if (directional && conditions != null) {
            stance = YtOpinion.Stance.CONDITIONAL;
            reasons.add("STANCE_TO_CONDITIONAL");
        } else if (directional && CONDITION_MARKER.matcher(evidence).find()) {
            stance = YtOpinion.Stance.CONDITIONAL;
            reasons.add("CONDITION_MARKER");
        }
        if (stance != YtOpinion.Stance.CONDITIONAL) conditions = null;

        // 5) 목표가격·기간은 원문에 있을 때만
        BigDecimal target = raw.targetPrice();
        if (target != null && !KoreanAmounts.appearsIn(target, window)) {
            target = null;
            reasons.add("TARGET_NOT_IN_TRANSCRIPT");
        }
        String horizon = cut(raw.horizon(), 40);
        if (horizon != null && !NameKeys.compact(window).contains(NameKeys.compact(horizon))) {
            horizon = null;
            reasons.add("HORIZON_NOT_IN_TRANSCRIPT");
        }

        // 집계 대상(본인의 현재 의견)만 검토로 보낸다 — 인용·회고·언급은 어차피 화면·집계에 안 나간다
        boolean needsReview = type == YtOpinion.StatementType.CURRENT_VIEW
                && reasons.stream().anyMatch(REVIEW_REASONS::contains);

        String stockIdentity = stock.verified() ? stock.stockCode() : "raw:" + NameKeys.key(stockName);
        String speakerIdentity = speaker != null ? "p" + speaker.personId() : "unknown";
        String key = sha256(ctx.videoId() + "|" + stockIdentity + "|" + speakerIdentity + "|" + s + "|" + type);

        return new Validated(key,
                speaker != null ? speaker.displayName() : null,
                speaker != null ? speaker.personId() : null,
                speaker != null ? speaker.role() : null,
                startCue.startSec(), ctx.cues().get(e).endSec(),
                stockName, stock.verified() ? stock.stockCode() : null, stock.status(),
                stance, type, summary, cut(raw.rationale(), 500), conditions, horizon, target, evidence,
                needsReview ? YtOpinion.ReviewStatus.NEEDS_REVIEW : YtOpinion.ReviewStatus.AUTO_OK,
                reasons.isEmpty() ? null : cut(String.join(",", reasons), 300));
    }

    /** 대조 창 — 화자 표기까지 포함(모델이 "이름: 발언" 째로 옮겨도 맞게). */
    static String windowText(List<TranscriptParser.Cue> cues, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            TranscriptParser.Cue c = cues.get(i);
            if (c.speaker() != null) sb.append(c.speaker()).append(": ");
            sb.append(c.text()).append(' ');
        }
        return sb.toString();
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
