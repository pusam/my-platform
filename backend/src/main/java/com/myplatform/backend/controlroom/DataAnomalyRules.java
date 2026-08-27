package com.myplatform.backend.controlroom;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 데이터 이상 판정 규칙 — 순수 함수. <b>전부 실제로 겪은 사고에서 나온 기준</b>이다.
 *
 * <h3>왜 AI 가 아니라 규칙인가</h3>
 * 관제실 크루는 <b>툴이 하나도 없다</b>(§7). DB·파일·API 어디에도 못 닿으므로
 * "이상한 데이터를 찾아와라"를 시킬 수 없고, 시켜서도 안 된다 —
 * "틀려도 아무 일이 안 일어난다"가 그 화면의 존재 이유다.
 *
 * <p>그래서 역할을 나눈다: <b>탐지는 결정적 규칙이 하고, 크루는 그 결과를 읽고 우선순위를 논한다.</b>
 * 2026-08-26 에 만든 '재무 입력층' KPI 가 그 첫 사례였고 하루 만에 실제 장애를 잡았다.
 * 이 클래스는 그 패턴을 사고별로 확장한 것이다.
 *
 * <h3>규칙의 계약</h3>
 * <ul>
 *   <li><b>정상이면 아무것도 내지 않는다.</b> 정상에서 시끄러우면 사람이 무시하게 되고,
 *       그러면 다음 사고도 똑같이 지나간다.</li>
 *   <li>조회 실패는 "이상 없음"이 아니다 — 호출부가 {@code dataAvailable=false} 로 구분한다(§4c).</li>
 *   <li>각 규칙은 <b>무엇을 확인하라</b>까지 문장에 담는다. "이상함"만 알리면 다음 사람이 또 헤맨다.</li>
 * </ul>
 */
public final class DataAnomalyRules {

    private DataAnomalyRules() {}

    /**
     * @param severity  {@code critical} | {@code warning} | {@code info} — FLAGGED 와 같은 어휘
     * @param title     한 줄 요약(카드 표면)
     * @param detail    무엇을 확인해야 하는지까지(툴팁·크루 컨텍스트)
     * @param evidence  판정 근거 수치 — 사람이 재확인할 수 있게 원자료를 남긴다
     */
    public record Anomaly(String severity, String key, String title, String detail, String evidence) {}

    public static final String CRITICAL = "critical";
    public static final String WARNING = "warning";
    public static final String INFO = "info";

    // ==================== ① 분기 누적 판정 쏠림 ====================

    /** 누적 판정 비율이 이 값 미만이면 쏠림으로 본다. KRX 상장사 분기 데이터는 대부분 누적이다. */
    static final double CUMULATIVE_EXPECTED_MIN_SHARE = 0.5;

    /** 쏠림 판정에 필요한 최소 종목 수 — 표본이 적으면 비율이 요동친다. */
    static final long CUMULATIVE_MIN_STOCKS = 100;

    /**
     * 분기 데이터의 누적(YTD) 판정이 한쪽으로 쏠렸는지 — <b>2026-08-27 사고 그대로</b>.
     *
     * <p>그날 2,523/2,618종목(96%)이 {@code cumulative=0} 으로 저장됐는데 실제로는 전부 누적이었다.
     * 판정 휴리스틱이 최신 3개만 보다 회계연도 경계에 걸린 것이다. 파급은 TTM 2배 부풀림과
     * 서프라이즈 변화율 오염이었고, <b>어느 화면에도 안 보였다.</b>
     *
     * <p>KRX 상장사의 KIS 분기 손익계산서는 대부분 누적이므로, 누적 비율이 절반을 밑돌면
     * 판정 로직을 의심해야 한다. 반대로 100% 누적도 정상이 아닐 수 있으나(개별 보고 기업이 0),
     * 그건 이 규칙의 범위 밖이다 — 실제로 겪은 방향만 잡는다.
     */
    public static Anomaly cumulativeSkew(long cumulativeStocks, long totalStocks) {
        if (totalStocks < CUMULATIVE_MIN_STOCKS) return null;
        double share = (double) cumulativeStocks / totalStocks;
        if (share >= CUMULATIVE_EXPECTED_MIN_SHARE) return null;

        long pct = Math.round(share * 100);
        return new Anomaly(CRITICAL, "quarterly-cumulative-skew",
                "분기 누적 판정이 " + pct + "% — 판정 로직 의심",
                "KIS 분기 손익계산서는 대부분 누적(YTD)이라 절반을 밑돌면 판정이 틀렸을 가능성이 높다. "
                        + "2026-08-27 에 96%가 오판돼 TTM 이 2.09배 부풀고 서프라이즈 변화율이 오염된 적이 있다"
                        + "(휴리스틱이 최신 3개만 보다 회계연도 경계에 걸림). "
                        + "확인 — 한 종목의 분기 매출을 시기순으로 뽑아 회계연도 안에서 단조 증가하는지 볼 것. "
                        + "그렇다면 누적인데 개별로 판정된 것이다.",
                cumulativeStocks + "/" + totalStocks + "종목이 누적으로 판정됨");
    }

    // ==================== ② 미래 날짜 행 ====================

    /**
     * 기준일이 미래인 재무 행 — 2026-08-26 실측 342행(연간 12-31 행).
     *
     * <p>어닝 서프라이즈는 인접분기 가드가 막아 무해하지만, <b>"최신 행"을 집는 소비자가
     * 새로 생기면</b> 미래 연간 행을 오늘 값으로 쓰게 된다. 조용히 틀리는 부류라 눈에 띄게 남긴다.
     */
    public static Anomaly futureDatedRows(long futureRows) {
        if (futureRows <= 0) return null;
        return new Anomaly(INFO, "future-dated-financial-rows",
                "기준일이 미래인 재무 행 " + futureRows + "건",
                "report_date 가 오늘 이후인 행이다(연간 12-31 행으로 보인다). 어닝 서프라이즈는 "
                        + "120일 인접분기 가드가 막아 현재는 무해하나, '최신 행'을 집는 소비자를 새로 붙이면 "
                        + "미래 값을 오늘 값으로 쓰게 된다. 새 쿼리를 쓸 때 report_date <= 오늘 조건을 확인할 것.",
                futureRows + "행");
    }

    // ==================== ③ 분기 데이터 노후 ====================

    /** 분기 말일이 이보다 오래되면 수집 정체로 본다. 분기 공시는 기말 + 45일 내외. */
    static final int QUARTER_STALE_DAYS = 200;

    /**
     * 분기 원본의 최신 회계기간이 너무 오래됐는지 — 수집이 멈춰도 테이블엔 옛 행이 남아
     * "데이터 있음"처럼 보이는 것을 잡는다.
     *
     * <p>{@code maxPeriodEnd} 가 null 이면 <b>행 자체가 없다는 뜻</b>이라 노후가 아니라 부재다 —
     * 다른 규칙(커버리지)이 다룰 일이므로 여기선 판정하지 않는다.
     */
    public static Anomaly staleQuarterlyData(LocalDate maxPeriodEnd, LocalDate today) {
        if (maxPeriodEnd == null || today == null) return null;
        long days = java.time.temporal.ChronoUnit.DAYS.between(maxPeriodEnd, today);
        if (days <= QUARTER_STALE_DAYS) return null;
        return new Anomaly(WARNING, "quarterly-data-stale",
                "분기 원본 최신 기간이 " + days + "일 전 (" + maxPeriodEnd + ")",
                "분기 공시는 기말 + 45일 내외라 " + QUARTER_STALE_DAYS + "일을 넘으면 수집이 멈춘 것이다. "
                        + "테이블에 옛 행이 남아 '데이터 있음'처럼 보이므로 커버리지 숫자만으론 안 드러난다. "
                        + "확인 — 배치 로그의 [분기재무] 적재 줄과 lastCollectedAt 을 볼 것.",
                maxPeriodEnd + " (" + days + "일 경과)");
    }

    // ==================== ④ 수집 배치 정체 ====================

    /** 마지막 수집이 이 시간을 넘으면 배치 정체로 본다(하루 2회 배치 기준 여유값). */
    static final long COLLECT_STALE_HOURS = 36;

    /**
     * 마지막 수집 시각이 너무 오래됐는지 — 회계 기간과 무관하게 <b>배치 자체의 생사</b>를 본다.
     *
     * <p>{@code staleQuarterlyData} 와 짝이다. 저쪽은 "데이터가 옛것", 이쪽은 "배치가 안 돈다" —
     * 분기 사이엔 새 회계기간이 없어 저쪽이 안 울려도 배치는 멈춰 있을 수 있다.
     */
    public static Anomaly staleCollection(java.time.LocalDateTime lastCollectedAt,
                                          java.time.LocalDateTime now) {
        if (lastCollectedAt == null || now == null) return null;
        long hours = java.time.temporal.ChronoUnit.HOURS.between(lastCollectedAt, now);
        if (hours <= COLLECT_STALE_HOURS) return null;
        return new Anomaly(WARNING, "quarterly-collection-stale",
                "분기 재무 수집이 " + hours + "시간째 멈춤",
                "재무 배치는 평일 08:30 / 15:38 이라 " + COLLECT_STALE_HOURS + "시간을 넘으면 정체다"
                        + "(연휴면 정상일 수 있다). 회계기간 노후와 별개 신호 — 분기 사이엔 새 기간이 없어도 "
                        + "배치는 돌아야 한다. 확인 — docker compose logs backend | grep 재무수집.",
                lastCollectedAt + " (" + hours + "시간 경과)");
    }

    /**
     * 규칙 전부를 한 번에 — null(정상)은 빼고 <b>심각도 순</b>으로 돌려준다.
     *
     * <p>화면과 크루 컨텍스트가 같은 순서를 보게 하려는 것이다. 컨텍스트 상한에 걸려 잘릴 때
     * 중요한 것이 남아야 한다(FLAGGED 와 같은 원칙).
     */
    public static List<Anomaly> sortBySeverity(List<Anomaly> found) {
        List<Anomaly> out = new ArrayList<>();
        if (found == null) return out;
        for (Anomaly a : found) if (a != null) out.add(a);
        out.sort(java.util.Comparator.comparingInt(a -> switch (a.severity()) {
            case CRITICAL -> 0;
            case WARNING -> 1;
            default -> 2;
        }));
        return out;
    }
}
