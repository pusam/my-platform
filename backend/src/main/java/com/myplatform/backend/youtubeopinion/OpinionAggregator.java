package com.myplatform.backend.youtubeopinion;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 한 종목의 유튜브 의견 집계. 순수 함수 — 집계 규칙의 단일 출처.
 *
 * <ul>
 *   <li>집계 창은 <b>영상 게시 시각</b> 기준 최근 N일(기본 7일). 창 밖은 {@code older} 로 날짜와 함께 따로 — 현재 집계에 넣지 않는다.</li>
 *   <li><b>발언 수와 인물 수를 섞지 않는다.</b> 인물 수는 식별된 사람만 한 번씩 센다 — 같은 사람의 여러 영상·재업로드가
 *       여러 사람처럼 부풀지 않는다. 재업로드·편집본으로 표시된 영상({@code duplicateVideo})은 발언 수에서도 뺀다.</li>
 *   <li>같은 사람이 창 안에서 다른 판단을 했으면 <b>MIXED</b> 로 두고 순서를 남긴다 — 한쪽으로 합치지 않는다.</li>
 *   <li>발언자 미상은 인물 수에서 빼고 따로 센다.</li>
 *   <li>본인의 현재 의견(CURRENT_VIEW)·검토 통과(AUTO_OK/APPROVED)·현재 실행 행만 센다.</li>
 * </ul>
 */
public final class OpinionAggregator {

    private OpinionAggregator() {}

    public static final int DEFAULT_WINDOW_DAYS = 7;
    public static final int MAX_OLDER = 10;
    public static final String MIXED = "MIXED";

    public record OpinionItem(long id, String videoId, String videoTitle, String channelName,
                              LocalDateTime publishedAt, LocalDateTime analyzedAt,
                              Long personId, String speakerName, YtVideoParticipant.Role speakerRole,
                              YtOpinion.Stance stance, YtOpinion.StatementType statementType,
                              YtOpinion.ReviewStatus reviewStatus, boolean currentRun, boolean duplicateVideo,
                              String claimSummary, String rationale, String conditions, String horizon,
                              BigDecimal targetPrice, String evidenceQuote, int startSec, int endSec,
                              String sourceUrl, String model, String promptVersion) {}

    public record PersonStance(long personId, String name, YtVideoParticipant.Role role, String stance,
                               List<String> sequence, int statements, LocalDateTime lastPublishedAt) {}

    public record Summary(Map<String, Integer> statementCounts, Map<String, Integer> personCounts,
                          int totalPersons, int totalStatements, int unknownSpeakerStatements,
                          LocalDateTime latestPublishedAt) {}

    public record View(LocalDateTime windowFrom, LocalDateTime windowTo, Summary summary,
                       List<PersonStance> persons, List<OpinionItem> current, List<OpinionItem> older) {
        public boolean hasCurrent() { return !current.isEmpty(); }
    }

    /** 집계에 들어갈 자격 — 본인 현재 의견 · 검토 통과 · 현재 실행 · 원본 영상. */
    public static boolean counts(OpinionItem o) {
        return o.statementType() == YtOpinion.StatementType.CURRENT_VIEW
                && (o.reviewStatus() == YtOpinion.ReviewStatus.AUTO_OK || o.reviewStatus() == YtOpinion.ReviewStatus.APPROVED)
                && o.currentRun() && !o.duplicateVideo();
    }

    public static View aggregate(List<OpinionItem> items, LocalDateTime now, int windowDays) {
        LocalDateTime from = now.minusDays(windowDays);
        Comparator<OpinionItem> newestFirst = Comparator.comparing(OpinionItem::publishedAt).reversed()
                .thenComparingInt(OpinionItem::startSec);
        List<OpinionItem> eligible = items.stream().filter(OpinionAggregator::counts).toList();
        List<OpinionItem> current = eligible.stream()
                .filter(o -> !o.publishedAt().isBefore(from) && !o.publishedAt().isAfter(now))
                .sorted(newestFirst).toList();
        List<OpinionItem> older = eligible.stream()
                .filter(o -> o.publishedAt().isBefore(from))
                .sorted(newestFirst).limit(MAX_OLDER).toList();

        Map<String, Integer> statementCounts = emptyCounts(false);
        for (OpinionItem o : current) statementCounts.merge(o.stance().name(), 1, Integer::sum);

        Map<Long, List<OpinionItem>> byPerson = current.stream().filter(o -> o.personId() != null)
                .collect(Collectors.groupingBy(OpinionItem::personId, LinkedHashMap::new, Collectors.toList()));
        List<PersonStance> persons = new ArrayList<>();
        Map<String, Integer> personCounts = emptyCounts(true);
        for (Map.Entry<Long, List<OpinionItem>> en : byPerson.entrySet()) {
            List<OpinionItem> seq = en.getValue().stream()
                    .sorted(Comparator.comparing(OpinionItem::publishedAt).thenComparingInt(OpinionItem::startSec)).toList();
            List<String> changes = new ArrayList<>();
            for (OpinionItem o : seq) {
                String st = o.stance().name();
                if (changes.isEmpty() || !changes.get(changes.size() - 1).equals(st)) changes.add(st);
            }
            Set<String> distinct = new LinkedHashSet<>(changes);
            String consolidated = distinct.size() == 1 ? distinct.iterator().next() : MIXED;
            personCounts.merge(consolidated, 1, Integer::sum);
            OpinionItem last = seq.get(seq.size() - 1);
            persons.add(new PersonStance(en.getKey(), last.speakerName(), last.speakerRole(), consolidated,
                    List.copyOf(changes), seq.size(), last.publishedAt()));
        }
        persons.sort(Comparator.comparing(PersonStance::lastPublishedAt).reversed());

        int unknown = (int) current.stream().filter(o -> o.personId() == null).count();
        LocalDateTime latest = current.isEmpty() ? null : current.get(0).publishedAt();
        Summary summary = new Summary(statementCounts, personCounts, persons.size(), current.size(), unknown, latest);
        return new View(from, now, summary, List.copyOf(persons), current, older);
    }

    private static Map<String, Integer> emptyCounts(boolean withMixed) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (YtOpinion.Stance s : YtOpinion.Stance.values()) m.put(s.name(), 0);
        if (withMixed) m.put(MIXED, 0);
        return m;
    }
}
