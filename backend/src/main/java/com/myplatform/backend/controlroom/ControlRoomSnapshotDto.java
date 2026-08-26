package com.myplatform.backend.controlroom;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관제실 좌측 패널 전체 스냅샷 — <b>계산은 전부 백엔드</b>에서 끝낸다.
 *
 * <p>프론트가 %·건수·통과 수를 다시 계산하지 않게 하는 것이 이 DTO 의 목적이다. 크루(FIREWALL)도
 * 숫자를 새로 만들지 않고 여기 담긴 값만 교차확인한다.
 *
 * <p><b>§4c 규약</b>: 모든 블록에 {@code dataAvailable} 이 있다. false 는 "0건"이 아니라
 * "못 읽었다"는 뜻이며 화면은 반드시 그렇게 표시해야 한다. 값이 없는 필드는 0 이 아니라 null 이다.
 */
public record ControlRoomSnapshotDto(
        LocalDate today,
        LocalDateTime generatedAt,
        String month,
        Kpis kpis,
        Calendar calendar,
        Flagged flagged,
        Invariants invariants,
        CrewStatus crew
) {

    public record Kpis(
            Candidates candidates,
            Gates gates,
            LossBreaker lossBreaker,
            VolRegime volRegime,
            Undecided undecided
    ) {}

    /**
     * 종합판단 보드 후보 등급 분포.
     *
     * <p>등급 컷은 CLAUDE.md §4 기준(STRONG_BUY 75 / BUY 55). 표시 전용이며 산식에 관여하지 않는다.
     *
     * <p><b>total=0 은 그 자체로 정보가 부족하다</b>(2026-08-24 실측에서 드러남): "정말 후보가 없다" ·
     * "보드 조회가 실패했다"({@code JudgmentBoardService} 가 예외를 삼키고 빈 목록을 돌려준다) ·
     * "입력이 노후라 노후 가드가 채점을 거부했다" 가 화면에서 전부 같은 0 으로 보인다.
     * 그래서 추천 스냅샷 신선도를 함께 실어 보내고 {@code note} 로 그 사실을 명시한다.
     *
     * @param latestSnapshotAt 추천 스냅샷 최신 시각. null = 스냅샷 자체가 없음
     * @param snapshotStale    최신 스냅샷이 직전 거래일보다 오래됐는지. null = 판정 불가(스냅샷 없음/조회 실패)
     * @param asOf             이 숫자의 기준 시각 라벨({@code Top5Response.dataTime})
     * @param realtime         <b>false = 어제 스냅샷 폴백</b>. 캐시가 비었거나 장외면 getTop5 가 DB 스냅샷을
     *                         돌려주는데, 화면이 그걸 실시간처럼 보여주면 "어제 1건"을 "오늘 1건"으로 읽는다
     *                         (2026-08-26 실제로 발생 — 09:00 화면 1종목 / 09:30 재계산 0건)
     * @param note             <b>카드 표면용 짧은 사유</b>(한 줄). 1/5 폭 카드에 문단을 넣으면 벽돌이 된다
     * @param noteDetail       전체 설명 — 화면은 툴팁으로만 보여준다. 짧게 줄이느라 근거를 잃지 않기 위함
     */
    public record Candidates(
            boolean dataAvailable,
            int total,
            int strongBuy,
            int buy,
            int watch,
            LocalDateTime latestSnapshotAt,
            Boolean snapshotStale,
            String asOf,
            Boolean realtime,
            String note,
            String noteDetail
    ) {}

    /** 게이트 1개. state = OPEN(진입 허용) / CLOSED(진입 차단) / UNKNOWN(판정 불가). */
    public record Gate(String key, String label, String state, String detail) {}

    public record Gates(boolean dataAvailable, int open, int total, List<Gate> items) {}

    /**
     * 일일 손실 서킷브레이커. 목업은 %였지만 <b>실제 브레이커는 원(KRW) 단위</b>다 — 자산 대비 %
     * 킬스위치(-3%/-1.5%)는 별개 장치이므로 섞지 않는다.
     *
     * @param realizedPnlKrw 당일 봇 실현손익 합. null = 조회 실패(0 으로 위장하지 않는다)
     */
    public record LossBreaker(
            boolean dataAvailable,
            Long realizedPnlKrw,
            Long limitKrw,
            Boolean enabled,
            Boolean trippedToday,
            String mode,
            String note
    ) {}

    /**
     * VKOSPI 변동성 국면. 목업의 "streak 3d" 는 소스가 없어 담지 않는다(§4c — 없는 값 지어내기 금지).
     *
     * @param regime NORMAL / HIGH_VOL / UNKNOWN
     */
    public record VolRegime(boolean dataAvailable, String regime, String gateMode, String note) {}

    /**
     * 미판정 건수 — {@code SCHEDULE_DECISIONS.md} 판정 기록 표에서 판정일이 미기입인 행 수.
     *
     * @param dataAvailable 판정 기록 표 자체를 못 찾았으면 false. 그때 count 는 0 이 아니라 의미 없음
     */
    public record Undecided(boolean dataAvailable, int count, int rosterSize) {}

    /**
     * 판정 캘린더 1건.
     *
     * @param trigger  조건 트리거. 있으면 {@code due} 는 판정일이 아니라 <b>확인일</b>이며 화면은
     *                 "확인(조건: …)" 으로 판정일과 구분 표기한다
     * @param kind     decision / milestone. milestone 은 로스터·미판정 집계 제외, 캘린더 핀만
     * @param overdue  {@code due < 오늘 && status ∈ (pending, deferred)}
     */
    public record CalendarEntry(
            String id,
            String title,
            LocalDate due,
            String status,
            LocalDate decidedOn,
            String result,
            String trigger,
            String kind,
            boolean overdue
    ) {}

    /**
     * 주간 신호 정확도 피드백 — 판정이 아니라 크론({@code 0 0 18 * * SUN})에서 유도한 일정이다.
     *
     * @param state SCHEDULED(미래) / RAN(리포트 실존) / MISSED(그날 리포트 없음) /
     *              UNKNOWN(조회 창 밖이라 실행 여부를 알 수 없음 — §4c, MISSED 로 단정하지 않는다)
     */
    public record WeeklyFeedback(LocalDate date, String state) {}

    /**
     * @param nextDue 오늘 이후 가장 가까운 due. null = 예정된 판정 없음
     * @param dDay    {@code nextDue} 까지 남은 일수. null = nextDue 없음
     */
    public record Calendar(
            boolean dataAvailable,
            List<CalendarEntry> entries,
            List<CalendarEntry> overdue,
            List<CalendarEntry> conditionWaiting,
            List<WeeklyFeedback> weeklyFeedback,
            LocalDate nextDue,
            Integer dDay
    ) {}

    /**
     * @param ageDays 기록 후 경과일 — 오래된 플래그의 신선도를 화면이 표시할 수 있게
     * @param derived true = 파일에 적힌 게 아니라 시스템이 유도한 항목(파싱 오류·미등록 판정)
     */
    public record FlagItem(
            String id,
            String severity,
            String title,
            String key,
            String body,
            LocalDate recordedOn,
            Integer ageDays,
            String ref,
            boolean derived
    ) {}

    public record Flagged(boolean dataAvailable, List<FlagItem> flags, long criticalCount) {}

    /** 크루가 초안을 대조할 기준. dataAvailable=false 면 크루에게 넘기지 않는다(무제약 승인 방지). */
    public record Invariants(boolean dataAvailable, List<String> items) {}

    /**
     * 크루 가용 상태 — 화면이 "왜 못 쓰는지"를 명시할 수 있게 사유를 함께 담는다.
     *
     * @param disabledReason enabled=false 인 이유. null = 정상
     * @param usedToday      당일 시작된 세션 수(실패 세션 포함 — 호출은 이미 나갔으므로)
     * @param running        진행 중 세션 존재 여부(동시 1건 가드)
     */
    public record CrewStatus(
            boolean enabled,
            String disabledReason,
            String model,
            int dailyLimit,
            long usedToday,
            boolean running
    ) {}
}
