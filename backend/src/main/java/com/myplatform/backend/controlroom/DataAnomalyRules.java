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

    // ==================== ⑤ 배치 심박 ====================

    /**
     * 크론이 죽었는지 — dead-man switch 결과를 <b>화면으로</b> 옮긴다(2026-08-28).
     *
     * <p><b>왜 필요한가</b>: 심박 판정은 이미 있었지만 <b>텔레그램으로만</b> 갔다.
     * 2026-08-28 주간 리포트 경보가 4일 연속 왔는데 원인(8/23 일요일 크론 미실행)을 알아내려면
     * 코드·로그·DB 를 뒤져야 했다 — 관제실에 있었으면 한눈에 끝났을 일이다.
     * "알림은 오는데 화면엔 없다"가 이 규칙이 없앤 상태다.
     *
     * <p>{@code MISSING}(콜드스타트)과 {@code UNKNOWN}(Redis 조회 실패)은 <b>이상이 아니다</b> —
     * 전자는 아직 한 번도 안 돈 것이고 후자는 감시 시스템 자신의 장애다. 배치 사망으로 위장하지 않는다(§4c).
     *
     * @param verdict     {@code OK} | {@code STALE} | {@code MISSING} | {@code UNKNOWN}
     * @param lastSuccess null 가능(MISSING/UNKNOWN)
     */
    public static Anomaly staleBatch(String jobKey, String label, String verdict,
                                     java.time.Instant lastSuccess, long maxAgeDays,
                                     java.time.Instant now) {
        if (!"STALE".equals(verdict)) return null;

        String elapsed = (lastSuccess != null && now != null)
                ? String.format("%.1f일 경과", java.time.Duration.between(lastSuccess, now).toHours() / 24.0)
                : "경과 불명";
        return new Anomaly(CRITICAL, "batch-stale-" + jobKey,
                label + " 심박이 끊겼다 (" + elapsed + ")",
                "마지막 성공 이후 임계(" + maxAgeDays + "일)를 넘겼다. 에러 알림이 없었다는 것은 성공의 근거가 아니다"
                        + "(크론 스레드가 죽으면 실패 알림조차 안 난다). "
                        + "⚠ 주 1회 잡은 한 번만 놓쳐도 임계를 넘고 다음 주기까지 매일 경보가 반복된다 — "
                        + "2026-08-28 주간 리포트가 그랬다(8/20~24 서버 다운으로 8/23 일요일 실행 소실). "
                        + "확인 — docker compose logs backend | grep 해당 배치명.",
                (lastSuccess != null ? "마지막 성공 " + lastSuccess + " · " : "") + elapsed
                        + " (임계 " + maxAgeDays + "일)");
    }

    // ==================== ⑥ 주간 스냅샷 구멍 ====================

    /**
     * 주간 예측력 스냅샷 시계열에 <b>빠진 주</b>가 있는지 — 2026-08-28 실사고.
     *
     * <p>주간 리포트는 주 1회 크론이라 그 시각에 서버가 죽으면 그 주가 통째로 빠진다.
     * 따라잡기({@code weeklyReportCatchUp})는 <b>"지금 기준 직전 완료 주"</b>만 채우므로
     * 이미 생긴 과거 구멍은 메우지 못한다 — 그래서 <b>보이게 만든다.</b>
     *
     * <p>구멍이 있으면 12주 추세(findTop12)가 실제보다 짧은 기간을 보게 되고,
     * 주차 비교가 인접 주가 아닌 것을 인접으로 읽는다.
     *
     * @param weekStartsDesc 스냅샷 weekStart 목록(최신순). 7일 간격이 아니면 구멍이다
     */
    public static Anomaly weeklySnapshotGap(List<java.time.LocalDate> weekStartsDesc) {
        if (weekStartsDesc == null || weekStartsDesc.size() < 2) return null;

        List<java.time.LocalDate> missing = new ArrayList<>();
        for (int i = 0; i < weekStartsDesc.size() - 1; i++) {
            java.time.LocalDate newer = weekStartsDesc.get(i);
            java.time.LocalDate older = weekStartsDesc.get(i + 1);
            if (newer == null || older == null) continue;
            long days = java.time.temporal.ChronoUnit.DAYS.between(older, newer);
            // 7일이 정상. 14·21일이면 그 사이 주가 빠진 것이다.
            for (long d = 7; d < days; d += 7) missing.add(older.plusDays(d));
        }
        if (missing.isEmpty()) return null;

        return new Anomaly(WARNING, "weekly-snapshot-gap",
                "주간 예측력 스냅샷에 " + missing.size() + "주 구멍",
                "주 1회 크론이 그 시각에 못 돌면 그 주가 통째로 빠진다. 따라잡기는 '지금 기준 직전 완료 주'만 "
                        + "채우므로 이미 생긴 과거 구멍은 안 메워진다. 12주 추세를 볼 때 실제보다 짧은 기간을 "
                        + "보게 되고, 주차 비교가 인접하지 않은 주를 인접으로 읽는다. "
                        + "메우려면 generateWeeklyReport 에 대상 주를 넘길 수 있어야 하는데 그때 cumulative 를 "
                        + "'그 주 시점'으로 할지 '오늘 시점'으로 할지가 측정 의미를 바꾼다 — 산식 판단이다.",
                "빠진 주 시작일: " + missing.stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(", ")));
    }

    // ==================== ⑦ 재무 금액 단위 ====================

    /** 매출/시총 비율 중앙값이 이 값보다 작으면 단위가 어긋난 것으로 본다. */
    static final double UNIT_RATIO_MIN = 0.02;

    /** 판정에 필요한 최소 표본 — 적으면 중앙값이 요동친다. */
    static final int UNIT_MIN_SAMPLES = 100;

    /**
     * 재무 금액 단위가 어긋났는지 — <b>매출/시총 비율의 중앙값</b>으로 본다(2026-08-28).
     *
     * <p><b>왜 이 방법인가</b>: 시가총액은 <b>다른 API</b>에서 오고 원 단위가 확실하다.
     * 매출과 시총의 비율은 업종마다 0.1~5 로 흩어지지만, <b>100배 어긋나면 중앙값이
     * 0.001 대로 내려간다</b> — 업종 분산으로는 절대 나올 수 없는 값이라 오탐이 없다.
     * 종목 하나로는 못 잡지만 2,000종목 중앙값이면 확실하다.
     *
     * <p><b>실제 사고</b>: 2026-08-28 실측에서 SK하이닉스 2019 매출이 DB 에 2,699 인데
     * 실제는 26.99조(269,907억)였다. 수집기가 "백만원 → 억원"이라며 /100 을 하는데
     * KIS 손익계산서 원본이 이미 억원이었다.
     *
     * <p>⚠ <b>고칠 때 한쪽만 고치면 안 된다</b> — ROE 는 {@code netIncome / totalEquity} 라
     * 둘 다 100배 작으면 <b>비율은 우연히 맞다</b>. 손익계산서만 고치면 ROE 가 100배 틀어진다.
     * 이 규칙은 "어긋났다"까지만 말하고 어느 쪽을 고칠지는 사람이 정한다.
     *
     * @param medianRevenueToMarketCap 매출 TTM ÷ 시가총액 의 중앙값(같은 단위 가정)
     * @param samples                  집계에 쓴 종목 수
     */
    public static Anomaly financialUnitMismatch(Double medianRevenueToMarketCap, int samples) {
        if (medianRevenueToMarketCap == null || samples < UNIT_MIN_SAMPLES) return null;
        if (medianRevenueToMarketCap >= UNIT_RATIO_MIN) return null;

        return new Anomaly(WARNING, "financial-unit-mismatch",
                String.format("매출/시총 중앙값 %.4f — 재무 금액 단위 어긋남 의심", medianRevenueToMarketCap),
                "시가총액은 다른 API 에서 오고 단위가 확실하다. 매출/시총 비율은 업종마다 0.1~5 로 "
                        + "흩어지지만 중앙값이 " + UNIT_RATIO_MIN + " 아래로 내려가는 것은 업종 분산으로 "
                        + "설명되지 않는다 — 재무 금액이 배수만큼 작게 저장된 것이다. "
                        + "2026-08-28 실측: SK하이닉스 2019 매출이 DB 2,699 vs 실제 269,907억(100배). "
                        + "원인 후보는 수집기의 '백만원 → 억원' /100 인데 KIS 원본이 이미 억원인 경우다. "
                        + "⚠ 고칠 때 손익계산서만 고치면 ROE(=순이익/자본총계)가 100배 틀어진다 — "
                        + "두 API 의 단위를 [손익계산서 RAW]·[재무상태표] 로그로 각각 확인한 뒤 함께 고칠 것.",
                String.format("중앙값 %.4f (표본 %d종목, 임계 %.2f)",
                        medianRevenueToMarketCap, samples, UNIT_RATIO_MIN));
    }

    // ==================== ⑧ KRX 종목상태 동기화 ====================

    /** 마지막 동기화가 이보다 오래면 게이트가 옛 목록으로 도는 것으로 본다(StockStatusService 와 동일). */
    static final long STOCK_STATUS_STALE_HOURS = 48;

    /**
     * 거래정지/상폐 제외 게이트의 원천이 노후인지 — <b>2026-08-31 실사고</b>.
     *
     * <p>KRX OTP 획득이 양쪽 시장 다 실패해 종목 목록이 0건이었다. 설계상 기존 목록은
     * 유지되지만(전면 fail-open 방지), <b>거래정지·상폐 종목이 추천·발굴·봇 유니버스에
     * 옛 목록 기준으로 남는다.</b>
     *
     * <p><b>왜 화면에 필요한가</b>: 이 판정은 이미 있었지만 <b>텔레그램으로만</b> 갔다.
     * 주간 리포트(8/28)와 완전히 같은 구조다 — "알림은 오는데 화면엔 없다".
     *
     * <p>⚠ {@code lastSyncTime} 은 <b>메모리(volatile)</b> 라 재기동마다 리셋된다.
     * 배포가 잦으면 "부팅 후 성공 0회"가 계속 나와 <b>언제부터 깨졌는지 알 수 없다</b> —
     * 그래서 detail 에 "재기동 이력을 같이 볼 것"을 적는다.
     *
     * @param lastSyncTime null = 이 프로세스에서 한 번도 성공 못 함(고장일 수도, 아직 크론 전일 수도)
     * @param bootedAt     프로세스 기동 시각 — null 이면 "한 번도 성공"의 의미를 판정하지 않는다
     */
    public static Anomaly stockStatusStale(java.time.LocalDateTime lastSyncTime,
                                           java.time.LocalDateTime bootedAt,
                                           java.time.LocalDateTime now) {
        if (now == null) return null;

        if (lastSyncTime == null) {
            // 기동 직후면 아직 08:30 크론을 못 만난 것일 수 있다 — 고장으로 단정하지 않는다(§4c).
            if (bootedAt == null || java.time.Duration.between(bootedAt, now).toHours() < 24) return null;
            return new Anomaly(CRITICAL, "stock-status-never-synced",
                    "상장종목 목록 동기화가 기동 후 한 번도 성공하지 못했다",
                    "거래정지·상폐 제외 게이트(추천·발굴·봇)가 옛 목록으로 돌고 있다. "
                            + "동기화는 부팅 직후 1회 + 평일 08:30 이며 소스는 KIS 종목마스터 파일(2026-08-31 교체 — KRX 경로는 死). "
                            + "확인 — docker compose logs backend | grep 종목상태 에서 실패 사유를 볼 것(기대 수집량 KOSPI 2,110/KOSDAQ 1,824)"
                            + "(2026-08-31 부터 status/본문/예외를 WARN 으로 남긴다). "
                            + "⚠ lastSyncTime 은 메모리라 재기동마다 리셋된다 — 배포가 잦았으면 "
                            + "'한 번도'가 실제 고장 기간을 뜻하지 않을 수 있다.",
                    "기동 " + bootedAt + " 이후 성공 0회");
        }

        long hours = java.time.Duration.between(lastSyncTime, now).toHours();
        if (hours <= STOCK_STATUS_STALE_HOURS) return null;
        return new Anomaly(CRITICAL, "stock-status-stale",
                "상장종목 목록이 " + hours + "시간째 갱신되지 않았다",
                "거래정지·상폐 제외 게이트가 " + hours + "시간 전 목록으로 돌고 있다. "
                        + "그 사이 정지·상폐된 종목이 추천·발굴·봇 유니버스에 그대로 남는다. "
                        + "확인 — docker compose logs backend | grep 종목상태 의 실패 사유.",
                "마지막 성공 " + lastSyncTime + " (" + hours + "시간 경과)");
    }

    /**
     * 규칙 전부를 한 번에 — null(정상)은 빼고 <b>심각도 순</b>으로 돌려준다.
     *
     * <p>화면과 크루 컨텍스트가 같은 순서를 보게 하려는 것이다. 컨텍스트 상한에 걸려 잘릴 때
     * 중요한 것이 남아야 한다(FLAGGED 와 같은 원칙).
     */
    // ==================== ⑨ 재료(catalyst) 파이프라인 정지 ====================

    /** 유입 정지 판정 임계(달력일) — 주말+공휴일 연휴(3일)를 정상으로 흡수하는 여유값. */
    static final long CATALYST_STALE_DAYS = 4;
    /** 전-NONE 판정에 참여하려면 그날 최소 이만큼 분류가 있어야 한다(소표본 잡음 제거). */
    static final long CATALYST_MIN_ROWS = 5;
    /** 이 비율 이상이 연속되면 분류가 죽은 것으로 본다. 건강 실측(2026-08-31) 최대 50% — 여유 큼. */
    static final double CATALYST_NONE_RATIO = 0.9;

    /** 하루치 재료 분류 집계 — date 는 catalyst_date, noneCount 는 type=NONE 행 수. */
    public record CatalystDayStat(LocalDate date, long total, long noneCount) {}

    /**
     * 재료 분류 파이프라인이 죽었는지 — 실사고 2026-07-01(7일 연속 100% NONE) 재발 감지.
     *
     * <p>그 사고는 세 원인(네이버 키 배선·URL 이중 인코딩·소스다운을 NONE 으로 캐시)이 겹쳐
     * <b>모든 분류가 NONE</b> 이 됐는데, 행은 매일 쌓여서 "데이터 있음"처럼 보였다 — 커버리지
     * 숫자로는 안 드러나는 부류다. 두 가지 죽음의 모양을 본다:
     *
     * <ul>
     *   <li><b>전-NONE 정지</b>: 최근 유효일({@code total ≥ 5}) 2일 이상이 전부 NONE ≥ 90% —
     *       건강 기준선(prod 실측 19~50%)과 겹칠 수 없는 값이다. 하루만으로는 안 울린다
     *       (진짜 뉴스 없는 날이 있을 수 있다).</li>
     *   <li><b>유입 정지</b>: 최신 catalyst_date 가 4일보다 오래 — 분류 실패는 캐시하지 않는
     *       설계(§4b)라 Gemini/네이버가 죽으면 행 자체가 안 쌓인다. 주말+공휴일 3일은 정상.</li>
     * </ul>
     *
     * <p>목록이 비어 있으면 null — 콜드스타트(부트스트랩)를 사망으로 위장하지 않는다(§4c).
     *
     * @param recentDesc 최근 일별 집계, 날짜 내림차순
     */
    public static Anomaly catalystStall(List<CatalystDayStat> recentDesc, LocalDate today) {
        if (recentDesc == null || recentDesc.isEmpty() || today == null) return null;

        LocalDate latest = recentDesc.get(0).date();
        long silentDays = java.time.temporal.ChronoUnit.DAYS.between(latest, today);
        if (silentDays > CATALYST_STALE_DAYS) {
            return new Anomaly(WARNING, "catalyst-inflow-stale",
                    "재료 분류 유입이 " + silentDays + "일째 없음 (최신 " + latest + ")",
                    "분류 실패는 캐시하지 않는 설계(§4b)라 네이버/Gemini 가 죽으면 행 자체가 안 쌓인다. "
                            + "주말+공휴일 연휴는 " + CATALYST_STALE_DAYS + "일까지 정상. "
                            + "확인 — docker compose logs backend | grep -E \"재료|Gemini|Naver\" 로 "
                            + "429/circuit open/키 미배선 여부를 볼 것(2026-07-01 장애의 세 원인 참조).",
                    "최신 catalyst_date=" + latest + " (" + silentDays + "일 경과)");
        }

        List<CatalystDayStat> valid = recentDesc.stream()
                .filter(d -> d.total() >= CATALYST_MIN_ROWS)
                .limit(3)
                .toList();
        if (valid.size() < 2) return null;   // 유효일 부족 — 판단 보류(침묵), 사망 단정 금지

        boolean allNone = valid.stream()
                .allMatch(d -> (double) d.noneCount() / d.total() >= CATALYST_NONE_RATIO);
        if (!allNone) return null;

        String days = valid.stream()
                .map(d -> d.date() + " " + d.noneCount() + "/" + d.total())
                .reduce((a, b) -> a + ", " + b).orElse("");
        return new Anomaly(WARNING, "catalyst-all-none",
                "재료 분류가 " + valid.size() + "일 연속 사실상 전부 NONE",
                "건강 기준선은 NONE 19~50%(2026-08-31 실측) — 90% 이상이 이틀 넘게 이어지는 건 "
                        + "분류가 죽고 껍데기 행만 쌓이는 상태다(2026-07-01 7일 장애의 모양). "
                        + "행이 매일 쌓여서 커버리지로는 안 드러난다. "
                        + "확인 — 네이버 키 배선(recreate 필요 여부)·URL 인코딩(%25 이중 인코딩)·"
                        + "소스다운 시 NONE 캐시 여부(§4b 함정 3종)를 순서대로 볼 것.",
                days);
    }

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
