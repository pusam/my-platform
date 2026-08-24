package com.myplatform.backend.controlroom;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 스냅샷 → 크루 시스템 프롬프트에 넣을 컨텍스트 문자열 — 순수 함수.
 *
 * <p><b>상한 처리</b>: UTF-8 바이트 상한(기본 8KB)을 넘으면 FLAGGED 를 <b>중요도 낮은 것부터</b>
 * 잘라낸다. 잘라낸 사실은 반드시 본문에 "N건 생략"으로 남긴다 — 생략을 숨기면 FIREWALL 이
 * "플래그에 없으니 문제 없다"고 판단해 버린다(§4c 를 프롬프트 레벨에서 지키는 것).
 *
 * <p>다른 블록(KPI·캘린더·불변식)은 자르지 않는다. 그것들이 잘리면 판단 근거 자체가 사라지고,
 * 애초에 크기가 예측 가능한 고정 블록이다.
 */
public final class CrewContextBuilder {

    private CrewContextBuilder() {}

    /**
     * @param omittedFlags 상한 때문에 뺀 FLAGGED 건수 (0 = 생략 없음)
     * @param bytes        최종 컨텍스트의 UTF-8 바이트 수
     */
    public record Context(String text, int omittedFlags, int bytes) {}

    public static Context build(ControlRoomSnapshotDto s, int limitBytes) {
        String head = head(s);

        List<ControlRoomSnapshotDto.FlagItem> flags = new ArrayList<>(
                s.flagged() == null || s.flagged().flags() == null ? List.of() : s.flagged().flags());
        // 잘림은 뒤에서 일어나므로 중요한 것을 앞으로 — critical > warning > info.
        flags.sort(Comparator.comparingInt(f -> severityRank(f.severity())));

        // 생략 안내 문구가 나중에 붙어도 상한을 넘지 않게 여유를 남긴다.
        int reserve = 120;
        int budget = Math.max(0, limitBytes - utf8(head) - reserve);

        StringBuilder flagText = new StringBuilder();
        int included = 0;
        for (ControlRoomSnapshotDto.FlagItem f : flags) {
            String line = flagLine(f);
            if (utf8(flagText.toString() + line) > budget) break;
            flagText.append(line);
            included++;
        }
        int omitted = flags.size() - included;

        StringBuilder sb = new StringBuilder(head);
        sb.append("\n[FLAGGED — 열려 있는 이상 항목]\n");
        if (s.flagged() != null && !s.flagged().dataAvailable() && flags.isEmpty()) {
            sb.append("- 데이터 없음 (플래그 문서를 읽지 못함. '이상 없음'이 아니다)\n");
        } else if (flags.isEmpty()) {
            sb.append("- 없음\n");
        } else {
            sb.append(flagText);
            if (omitted > 0) {
                sb.append("- ⚠ 컨텍스트 상한으로 중요도 낮은 순 ")
                        .append(omitted)
                        .append("건 생략됨 — 아래 목록이 전부가 아니다.\n");
            }
        }

        String text = sb.toString();
        return new Context(text, omitted, utf8(text));
    }

    private static String head(ControlRoomSnapshotDto s) {
        StringBuilder sb = new StringBuilder();
        sb.append("오늘: ").append(s.today()).append('\n');

        sb.append("\n[KPI]\n");
        ControlRoomSnapshotDto.Kpis k = s.kpis();
        if (k != null) {
            appendCandidates(sb, k.candidates());
            appendGates(sb, k.gates());
            appendLossBreaker(sb, k.lossBreaker());
            appendVolRegime(sb, k.volRegime());
            appendUndecided(sb, k.undecided());
        }

        sb.append("\n[판정 캘린더]\n");
        appendCalendar(sb, s.calendar());

        sb.append("\n[불변식 — 초안이 이걸 깨면 반려]\n");
        if (s.invariants() == null || !s.invariants().dataAvailable()) {
            sb.append("- 데이터 없음 (불변식 문서를 읽지 못함. 제약이 없다는 뜻이 아니므로 승인에 신중할 것)\n");
        } else {
            for (String inv : s.invariants().items()) {
                sb.append("- ").append(inv).append('\n');
            }
        }
        return sb.toString();
    }

    private static void appendCandidates(StringBuilder sb, ControlRoomSnapshotDto.Candidates c) {
        if (c == null || !c.dataAvailable()) {
            sb.append("- 종합판단 후보: 데이터 없음\n");
            return;
        }
        sb.append("- 종합판단 후보: 총 ").append(c.total())
                .append("종목 (STRONG_BUY ").append(c.strongBuy())
                .append(" / BUY ").append(c.buy())
                .append(" / 관망·미채점 ").append(c.watch()).append(')');
        if (c.note() != null) sb.append(" — ").append(c.note());
        sb.append('\n');
    }

    private static void appendGates(StringBuilder sb, ControlRoomSnapshotDto.Gates g) {
        if (g == null || !g.dataAvailable()) {
            sb.append("- 봇 게이트: 데이터 없음\n");
            return;
        }
        sb.append("- 봇 게이트: ").append(g.open()).append('/').append(g.total()).append(" 열림");
        List<String> closed = g.items().stream()
                .filter(i -> !"OPEN".equals(i.state()))
                .map(i -> i.label() + "=" + i.state() + "(" + i.detail() + ")")
                .toList();
        if (!closed.isEmpty()) sb.append(" · 막힌 것: ").append(String.join(" / ", closed));
        sb.append('\n');
    }

    private static void appendLossBreaker(StringBuilder sb, ControlRoomSnapshotDto.LossBreaker b) {
        if (b == null || !b.dataAvailable()) {
            sb.append("- 일일손실 서킷: 데이터 없음\n");
            return;
        }
        sb.append("- 일일손실 서킷(원 단위, 자산 % 킬스위치와 별개): ");
        if (b.realizedPnlKrw() == null) {
            sb.append("당일 실현손익 조회 실패");
        } else {
            sb.append("당일 실현손익 ").append(String.format("%,d", b.realizedPnlKrw())).append("원");
        }
        if (b.limitKrw() != null) {
            sb.append(" / 한도 -").append(String.format("%,d", b.limitKrw())).append("원");
        }
        sb.append(" · ").append(Boolean.TRUE.equals(b.trippedToday()) ? "오늘 발동" : "미발동");
        if (b.mode() != null) sb.append(" · 모드 ").append(b.mode());
        if (b.note() != null) sb.append(" — ").append(b.note());
        sb.append('\n');
    }

    private static void appendVolRegime(StringBuilder sb, ControlRoomSnapshotDto.VolRegime v) {
        if (v == null || !v.dataAvailable()) {
            sb.append("- VKOSPI 국면: 데이터 없음");
            if (v != null && v.note() != null) sb.append(" (").append(v.note()).append(')');
            sb.append('\n');
            return;
        }
        sb.append("- VKOSPI 국면: ").append(v.regime())
                .append(" · 게이트 mode=").append(v.gateMode()).append('\n');
    }

    private static void appendUndecided(StringBuilder sb, ControlRoomSnapshotDto.Undecided u) {
        if (u == null || !u.dataAvailable()) {
            sb.append("- 미판정: 데이터 없음 (판정 기록 표를 읽지 못함)\n");
            return;
        }
        sb.append("- 미판정: ").append(u.count()).append("건 / 전체 안건 ")
                .append(u.rosterSize()).append("건\n");
    }

    private static void appendCalendar(StringBuilder sb, ControlRoomSnapshotDto.Calendar c) {
        if (c == null || !c.dataAvailable()) {
            sb.append("- 데이터 없음 (판정 캘린더 블록을 읽지 못함)\n");
            return;
        }
        if (!c.overdue().isEmpty()) {
            sb.append("- OVERDUE ").append(c.overdue().size()).append("건: ");
            sb.append(c.overdue().stream()
                    .map(e -> e.title() + "(기한 " + e.due() + ", " + e.status() + ")")
                    .reduce((a, b) -> a + " / " + b).orElse(""));
            sb.append('\n');
        }
        for (ControlRoomSnapshotDto.CalendarEntry e : c.entries()) {
            if (e.overdue()) continue;
            sb.append("- ").append(e.due()).append(' ').append(e.title());
            if (e.trigger() != null && !e.trigger().isBlank()) {
                // 날짜가 판정 근거가 아니라는 점을 크루에게 분명히 알린다.
                sb.append(" [확인일 — 실제 트리거는 조건: ").append(e.trigger()).append(']');
            }
            if ("milestone".equals(e.kind())) sb.append(" [마일스톤 — 판정 아님]");
            if (e.result() != null) sb.append(" · 기록: ").append(e.result());
            sb.append('\n');
        }
        if (c.nextDue() != null) {
            sb.append("- 다음 예정일: ").append(c.nextDue())
                    .append(" (D-").append(c.dDay()).append(")\n");
        }
    }

    private static String flagLine(ControlRoomSnapshotDto.FlagItem f) {
        StringBuilder sb = new StringBuilder();
        sb.append("- [").append(f.severity()).append("] ").append(f.title());
        if (f.key() != null) sb.append(" (").append(f.key()).append(')');
        sb.append(": ").append(oneLine(f.body()));
        if (f.recordedOn() != null) {
            sb.append(" (기록 ").append(f.recordedOn());
            if (f.ageDays() != null) sb.append(", ").append(f.ageDays()).append("일 경과");
            sb.append(')');
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static int severityRank(String severity) {
        return switch (severity == null ? "" : severity) {
            case ControlRoomFlagParser.SEVERITY_CRITICAL -> 0;
            case ControlRoomFlagParser.SEVERITY_WARNING -> 1;
            default -> 2;
        };
    }

    private static int utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
