package com.myplatform.backend.bootlog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 장중 재시작 판정 — 순수 함수(2026-11-10, 개선 후보 4번).
 *
 * <p>기준은 CLAUDE.md §7 표본 시작일 규칙이다: <b>장이 열린 날 08:30 부터 그날 마감 10분 뒤까지</b>(평소 08:30~15:50 —
 * 재무 수집 08:30 부터 '마감 10분 뒤 수급 확정' 시각까지, {@code InvestorDailyConfirmation} 과 같은 정의)에 백엔드가 다시
 * 뜨면 그날은 '재시작 없는 거래일'이 아니다. 경계 시각은 둘 다 포함한다. 마감은 달력({@code MarketCalendarService.marketCloseOf})
 * 을 따른다 — 장이 늦게 닫히는 날(2026-11-19 수능일, 마감 16:40)은 16:50 까지 본다. 시작 08:30 은 고정이다.
 * 장이 닫힌 날의 재시작은 세지 않는다(그날은 애초에 표본 날이 아니다).
 */
public final class IntradayRestartRules {

    public static final LocalTime WINDOW_START = LocalTime.of(8, 30);
    /** 평소 마감(MarketCalendarService.MARKET_CLOSE 와 같은 값) — 달력을 모를 때 쓴다. */
    public static final LocalTime NORMAL_CLOSE = LocalTime.of(15, 40);
    /** 마감 뒤 여유 — 수급 확정 시각(마감 + 10분). */
    public static final int MINUTES_AFTER_CLOSE = 10;

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM/dd");
    private static final DateTimeFormatter MD_HM = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private IntradayRestartRules() {}

    /** 부팅 한 번. host 가 같으면 같은 컨테이너의 재시작, 바뀌었으면 재생성(배포). */
    public record Boot(LocalDateTime startedAt, String host, String revision) {}

    /**
     * 달력 — 장이 열리는 날인가와 그날 마감. 둘 다 null 이면 모름(모든 날을 거래일로, 마감은 평소로 본다 — 숨기는 쪽보다 낫다).
     */
    public record Calendar(Predicate<LocalDate> isTradingDay, Function<LocalDate, LocalTime> marketCloseOf) {
        public static final Calendar UNKNOWN = new Calendar(null, null);

        boolean tradingDay(LocalDate d) {
            return isTradingDay == null || isTradingDay.test(d);
        }

        LocalTime windowEnd(LocalDate d) {
            LocalTime close = marketCloseOf == null ? null : marketCloseOf.apply(d);
            return (close == null ? NORMAL_CLOSE : close).plusMinutes(MINUTES_AFTER_CLOSE);
        }
    }

    /**
     * @param tradingDay           오늘 장이 열리는가. null = 달력을 모름
     * @param window               오늘의 판정 시간대 라벨(예: 08:30~15:50)
     * @param todayIntraday        오늘 그 시간대 재시작(오래된 순). 장이 닫힌 날이면 빈 목록
     * @param lastIntraday         조회 창 안 마지막 장중 재시작. null = 기록 시작 이후 없음
     * @param lastBoot             가장 최근 부팅(지금 프로세스)
     * @param currentBootRecorded  지금 프로세스의 부팅이 기록에 있는가 — false 면 저장이 실패했거나 아직 기록 전이라
     *                             다른 재시작도 빠졌을 수 있다(§4c)
     * @param recordedSince        기록 시작일(가장 오래된 행). null = 기록이 하나도 없다
     */
    public record Summary(Boolean tradingDay,
                          String window,
                          List<Boot> todayIntraday,
                          Boot lastIntraday,
                          Boot lastBoot,
                          boolean currentBootRecorded,
                          LocalDate recordedSince) {}

    /** 그날의 판정 시간대 라벨 — 평소 '08:30~15:50', 마감이 밀린 날은 그만큼. */
    public static String windowLabel(LocalDate date, Calendar calendar) {
        Calendar c = calendar == null ? Calendar.UNKNOWN : calendar;
        return WINDOW_START.format(HM) + "~" + c.windowEnd(date).format(HM);
    }

    public static boolean inWindow(LocalDateTime t, Calendar calendar) {
        if (t == null) return false;
        Calendar c = calendar == null ? Calendar.UNKNOWN : calendar;
        LocalDate d = t.toLocalDate();
        LocalTime time = t.toLocalTime();
        if (time.isBefore(WINDOW_START) || time.isAfter(c.windowEnd(d))) return false;
        return c.tradingDay(d);
    }

    /**
     * @param boots               기록된 부팅(순서 무관)
     * @param currentProcessStart 지금 프로세스가 뜬 시각(메모리) — 기록에 없으면 목록에 더하고 누락으로 알린다. null = 모름
     * @param calendar            장이 열린 날·그날 마감. null = 모름
     * @param recordedSince       기록 시작일(조회 창보다 오래됐을 수 있어 따로 받는다)
     */
    public static Summary summarize(List<Boot> boots, LocalDateTime currentProcessStart, LocalDate today,
                                    Calendar calendar, LocalDate recordedSince) {
        Calendar cal = calendar == null ? Calendar.UNKNOWN : calendar;
        List<Boot> all = new ArrayList<>();
        if (boots != null) boots.stream().filter(Objects::nonNull).filter(b -> b.startedAt() != null).forEach(all::add);

        boolean recorded = true;
        if (currentProcessStart != null) {
            recorded = all.stream().anyMatch(b -> sameSecond(b.startedAt(), currentProcessStart));
            if (!recorded) all.add(new Boot(currentProcessStart, null, null));
        }
        all.sort(Comparator.comparing(Boot::startedAt));

        Boolean tradingDay = cal.isTradingDay() == null ? null : cal.isTradingDay().test(today);
        List<Boot> todayIntraday = all.stream()
                .filter(b -> b.startedAt().toLocalDate().equals(today))
                .filter(b -> inWindow(b.startedAt(), cal))
                .toList();
        Boot lastIntraday = null;
        for (Boot b : all) {
            if (inWindow(b.startedAt(), cal)) lastIntraday = b;
        }
        Boot lastBoot = all.isEmpty() ? null : all.get(all.size() - 1);
        return new Summary(tradingDay, windowLabel(today, cal), todayIntraday, lastIntraday, lastBoot, recorded,
                recordedSince);
    }

    /** 카드 표면용 한 줄 — 정상(오늘 장중 재시작 없음·기록 누락 없음)이면 null(카드가 조용하다). */
    public static String note(Summary s) {
        if (s == null) return null;
        if (!s.todayIntraday().isEmpty()) {
            Boot first = s.todayIntraday().get(0);
            int more = s.todayIntraday().size() - 1;
            return "오늘 장중 재시작 " + first.startedAt().format(HM) + (more > 0 ? " 외 " + more + "회" : "")
                    + " — 표본 시작일 후보 아님";
        }
        if (!s.currentBootRecorded()) return "이번 부팅 기록 누락 — 재시작 기록이 빠졌을 수 있다";
        return null;
    }

    /** 툴팁·크루용 긴 설명 — 정상이면 null. */
    public static String noteDetail(Summary s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        if (!s.todayIntraday().isEmpty()) {
            sb.append("오늘 ").append(s.window()).append(" 에 백엔드가 다시 떴다: ");
            for (int i = 0; i < s.todayIntraday().size(); i++) {
                Boot b = s.todayIntraday().get(i);
                if (i > 0) sb.append(", ");
                sb.append(b.startedAt().format(HM)).append(facts(b));
            }
            sb.append(". 표본 시작일은 이 시간대에 재시작이 없던 거래일이어야 해서(CLAUDE.md §7) 오늘은 후보가 아니다. ")
                    .append("재무 수집(08:30~09:12, 15:38~16:11) 도중이었다면 그 회차가 끝까지 돌았는지 확인할 것. ");
        }
        if (!s.currentBootRecorded()) {
            sb.append("지금 프로세스의 부팅이 기록에 없다 — 저장이 실패했거나(백엔드 WARN '[부팅 기록] 저장 실패') 아직 기록 전이다. ")
                    .append("저장이 실패하는 중이면 다른 재시작도 빠졌을 수 있어 '없음'을 그대로 믿으면 안 된다. ");
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    /** '마지막 장중 재시작' 한 줄 — 카드·크루 공용. */
    public static String lastIntradayLine(Summary s) {
        if (s == null) return null;
        if (s.lastIntraday() != null) return "마지막 장중 재시작 " + s.lastIntraday().startedAt().format(MD_HM);
        if (s.recordedSince() != null) return "기록 시작(" + s.recordedSince().format(MD) + ") 이후 장중 재시작 없음";
        return "장중 재시작 기록 없음(기록 전)";
    }

    /**
     * 재시작 종류(배포·OOM 등)를 짐작하지 않고 기록된 사실만 붙인다 — 커밋이 바뀌었으면 배포였을 것이고 컨테이너가 같으면
     * 같은 컨테이너의 재시작이지만, 그 판단은 읽는 사람이 한다.
     */
    static String facts(Boot b) {
        if (b.host() == null && b.revision() == null) return "(기록 누락 — 메모리의 시작 시각)";
        List<String> parts = new ArrayList<>();
        if (b.revision() != null) parts.add("커밋 " + b.revision().substring(0, Math.min(8, b.revision().length())));
        if (b.host() != null) parts.add("컨테이너 " + b.host().substring(0, Math.min(12, b.host().length())));
        return "(" + String.join(" · ", parts) + ")";
    }

    private static boolean sameSecond(LocalDateTime a, LocalDateTime b) {
        if (a == null || b == null) return false;
        return a.withNano(0).equals(b.withNano(0));
    }
}
