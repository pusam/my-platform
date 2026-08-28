package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 데이터 이상 판정 규칙 ({@link DataAnomalyRules}) — 2026-08-27 신설.
 *
 * <p><b>각 규칙은 실제로 겪은 사고에서 나왔다.</b> 그래서 테스트도 그날의 실측 수치를 그대로 넣는다 —
 * "그때 이 규칙이 있었다면 울렸을까"가 유일하게 의미 있는 검증이다.
 *
 * <p>그리고 반대 방향을 같이 고정한다: <b>정상일 때 조용한가.</b> 정상에서 시끄러우면
 * 사람이 경고를 무시하게 되고, 그러면 다음 사고도 똑같이 지나간다.
 */
class DataAnomalyRulesTest {

    @Nested
    @DisplayName("분기 누적 판정 쏠림")
    class CumulativeSkew {

        @Test
        @DisplayName("2026-08-27 실측 재현 — 2,618종목 중 95종목만 누적이면 울린다")
        void reproducesTheRealIncident() {
            DataAnomalyRules.Anomaly a = DataAnomalyRules.cumulativeSkew(95, 2618);

            assertThat(a).isNotNull();
            assertThat(a.severity()).isEqualTo(DataAnomalyRules.CRITICAL);
            assertThat(a.title()).contains("4%");           // 95/2618 = 3.63% → 반올림 4%
            assertThat(a.evidence()).isEqualTo("95/2618종목이 누적으로 판정됨");
            // 무엇을 확인해야 하는지까지 담는다 — "이상함"만 알리면 다음 사람이 또 헤맨다
            assertThat(a.detail()).contains("단조 증가");
        }

        @Test
        @DisplayName("대부분 누적이면 조용하다 — KIS 분기 데이터의 정상 상태")
        void healthyIsSilent() {
            assertThat(DataAnomalyRules.cumulativeSkew(2400, 2618)).isNull();
        }

        @Test
        @DisplayName("절반이 경계 — 딱 절반이면 아직 안 울린다")
        void halfIsTheBoundary() {
            assertThat(DataAnomalyRules.cumulativeSkew(500, 1000)).isNull();
            assertThat(DataAnomalyRules.cumulativeSkew(499, 1000)).isNotNull();
        }

        @Test
        @DisplayName("표본이 적으면 판정하지 않는다 — 비율이 요동쳐 오탐이 된다")
        void tooFewStocksIsSilent() {
            assertThat(DataAnomalyRules.cumulativeSkew(0, 50)).isNull();
        }
    }

    @Nested
    @DisplayName("미래 날짜 행")
    class FutureDated {

        @Test
        @DisplayName("2026-08-26 실측 재현 — 342행이면 울린다(단, info 톤)")
        void reproducesTheRealIncident() {
            DataAnomalyRules.Anomaly a = DataAnomalyRules.futureDatedRows(342);

            assertThat(a).isNotNull();
            // 현재는 120일 가드가 막고 있어 무해하다 — 그래서 critical 이 아니라 info
            assertThat(a.severity()).isEqualTo(DataAnomalyRules.INFO);
            assertThat(a.title()).contains("342건");
            assertThat(a.detail()).contains("report_date <= 오늘");
        }

        @Test
        @DisplayName("0건이면 조용하다")
        void noneIsSilent() {
            assertThat(DataAnomalyRules.futureDatedRows(0)).isNull();
        }
    }

    @Nested
    @DisplayName("분기 데이터 노후")
    class StaleQuarterly {

        private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);

        @Test
        @DisplayName("직전 분기(2026-06-30)는 정상 — 공시 주기 안이다")
        void recentQuarterIsSilent() {
            assertThat(DataAnomalyRules.staleQuarterlyData(LocalDate.of(2026, 6, 30), TODAY)).isNull();
        }

        @Test
        @DisplayName("공시 주기(200일)를 넘으면 울린다")
        void staleFires() {
            DataAnomalyRules.Anomaly a =
                    DataAnomalyRules.staleQuarterlyData(LocalDate.of(2025, 9, 30), TODAY);
            assertThat(a).isNotNull();
            assertThat(a.severity()).isEqualTo(DataAnomalyRules.WARNING);
            assertThat(a.detail()).contains("[분기재무]");
        }

        @Test
        @DisplayName("null 은 노후가 아니라 부재 — 여기서 판정하지 않는다")
        void nullIsAbsenceNotStaleness() {
            assertThat(DataAnomalyRules.staleQuarterlyData(null, TODAY)).isNull();
        }

        @Test
        @DisplayName("분기 사이엔 새 기간이 없어도 노후가 아니다 — 배치 생사는 별도 규칙")
        void betweenQuartersIsNormal() {
            // 5월 = 3월말 기준일로부터 약 60일. 아직 정상
            assertThat(DataAnomalyRules.staleQuarterlyData(
                    LocalDate.of(2026, 3, 31), LocalDate.of(2026, 5, 30))).isNull();
        }
    }

    @Nested
    @DisplayName("수집 배치 정체")
    class StaleCollection {

        private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 27, 10, 0);

        @Test
        @DisplayName("오늘 아침 수집이면 조용하다")
        void freshIsSilent() {
            assertThat(DataAnomalyRules.staleCollection(
                    LocalDateTime.of(2026, 8, 27, 9, 2), NOW)).isNull();
        }

        @Test
        @DisplayName("36시간을 넘으면 울린다 — 하루 2회 배치 기준")
        void staleFires() {
            DataAnomalyRules.Anomaly a = DataAnomalyRules.staleCollection(
                    LocalDateTime.of(2026, 8, 25, 9, 0), NOW);
            assertThat(a).isNotNull();
            assertThat(a.severity()).isEqualTo(DataAnomalyRules.WARNING);
            // 회계기간 노후와 다른 신호라는 것을 문장이 말해 줘야 한다
            assertThat(a.detail()).contains("별개 신호");
        }

        @Test
        @DisplayName("어제 오후 수집은 아직 정상 — 주말·연휴 오탐 방지")
        void yesterdayIsSilent() {
            assertThat(DataAnomalyRules.staleCollection(
                    LocalDateTime.of(2026, 8, 26, 15, 40), NOW)).isNull();
        }
    }

    @Nested
    @DisplayName("정렬")
    class Sorting {

        @Test
        @DisplayName("심각도 순 — 컨텍스트 상한에 잘려도 중요한 것이 남아야 한다")
        void sortsBySeverity() {
            List<DataAnomalyRules.Anomaly> sorted = DataAnomalyRules.sortBySeverity(Arrays.asList(
                    new DataAnomalyRules.Anomaly(DataAnomalyRules.INFO, "i", "info", null, null),
                    new DataAnomalyRules.Anomaly(DataAnomalyRules.CRITICAL, "c", "critical", null, null),
                    new DataAnomalyRules.Anomaly(DataAnomalyRules.WARNING, "w", "warning", null, null)));

            assertThat(sorted).extracting(DataAnomalyRules.Anomaly::key)
                    .containsExactly("c", "w", "i");
        }

        @Test
        @DisplayName("null(정상 판정)은 걸러진다 — 규칙이 정상이면 null 을 돌려주기 때문")
        void dropsNulls() {
            assertThat(DataAnomalyRules.sortBySeverity(Arrays.asList(
                    null,
                    new DataAnomalyRules.Anomaly(DataAnomalyRules.WARNING, "w", "warning", null, null),
                    null))).hasSize(1);
            assertThat(DataAnomalyRules.sortBySeverity(null)).isEmpty();
        }
    }
    @Nested
    @DisplayName("배치 심박")
    class StaleBatch {

        private static final java.time.Instant NOW = java.time.Instant.parse("2026-08-28T00:05:00Z");
        private static final java.time.Instant LAST = java.time.Instant.parse("2026-08-16T09:00:00Z");

        @Test
        @DisplayName("2026-08-28 실측 재현 — 주간 리포트 11.6일 경과면 울린다")
        void reproducesTheRealIncident() {
            DataAnomalyRules.Anomaly a = DataAnomalyRules.staleBatch(
                    "weekly-report", "시그널 주간 리포트(일 18:00)", "STALE", LAST, 9, NOW);

            assertThat(a).isNotNull();
            assertThat(a.severity()).isEqualTo(DataAnomalyRules.CRITICAL);
            assertThat(a.title()).contains("11.6일 경과");
            // 주 1회 잡이 왜 매일 울리는지까지 설명해야 다음 사람이 안 헤맨다
            assertThat(a.detail()).contains("주 1회");
        }

        @Test
        @DisplayName("OK 면 조용하다")
        void okIsSilent() {
            assertThat(DataAnomalyRules.staleBatch("k", "L", "OK", NOW, 9, NOW)).isNull();
        }

        @Test
        @DisplayName("MISSING(콜드스타트)·UNKNOWN(감시 시스템 장애)은 이상이 아니다 — 배치 사망으로 위장 금지")
        void missingAndUnknownAreNotAnomalies() {
            assertThat(DataAnomalyRules.staleBatch("k", "L", "MISSING", null, 9, NOW)).isNull();
            assertThat(DataAnomalyRules.staleBatch("k", "L", "UNKNOWN", null, 9, NOW)).isNull();
        }

        @Test
        @DisplayName("키가 잡별로 갈린다 — 두 배치가 동시에 죽으면 두 건으로 보여야 한다")
        void keyIsPerJob() {
            assertThat(DataAnomalyRules.staleBatch("a", "A", "STALE", LAST, 9, NOW).key())
                    .isNotEqualTo(DataAnomalyRules.staleBatch("b", "B", "STALE", LAST, 9, NOW).key());
        }
    }

    @Nested
    @DisplayName("주간 스냅샷 구멍")
    class WeeklyGap {

        private java.time.LocalDate d(String s) { return java.time.LocalDate.parse(s); }

        @Test
        @DisplayName("2026-08-28 실사고 재현 — 8/10 주가 빠진 것을 잡는다")
        void reproducesTheRealIncident() {
            // 8/16(일) 크론이 8/03 주를 만들고, 8/23(일) 크론이 서버 다운으로 빠졌다.
            // 그래서 8/10 주 시작일이 시계열에서 통째로 없다.
            DataAnomalyRules.Anomaly a = DataAnomalyRules.weeklySnapshotGap(
                    List.of(d("2026-08-17"), d("2026-08-03"), d("2026-07-27")));

            assertThat(a).isNotNull();
            assertThat(a.severity()).isEqualTo(DataAnomalyRules.WARNING);
            assertThat(a.title()).contains("1주 구멍");
            assertThat(a.evidence()).contains("2026-08-10");
        }

        @Test
        @DisplayName("7일 간격이 이어지면 조용하다")
        void continuousIsSilent() {
            assertThat(DataAnomalyRules.weeklySnapshotGap(
                    List.of(d("2026-08-17"), d("2026-08-10"), d("2026-08-03")))).isNull();
        }

        @Test
        @DisplayName("여러 주가 빠지면 전부 센다")
        void countsMultipleGaps() {
            DataAnomalyRules.Anomaly a = DataAnomalyRules.weeklySnapshotGap(
                    List.of(d("2026-08-24"), d("2026-08-03")));   // 08-10, 08-17 두 주 결측

            assertThat(a.title()).contains("2주 구멍");
            assertThat(a.evidence()).contains("2026-08-10").contains("2026-08-17");
        }

        @Test
        @DisplayName("스냅샷이 0~1개면 판정 불가 — 간격을 만들 수 없다")
        void tooFewIsSilent() {
            assertThat(DataAnomalyRules.weeklySnapshotGap(null)).isNull();
            assertThat(DataAnomalyRules.weeklySnapshotGap(List.of())).isNull();
            assertThat(DataAnomalyRules.weeklySnapshotGap(List.of(d("2026-08-17")))).isNull();
        }
    }
    @Nested
    @DisplayName("재무 금액 단위")
    class UnitMismatch {

        @Test
        @DisplayName("2026-08-28 실사고 재현 — 100배 작으면 중앙값이 0.005 대로 내려간다")
        void reproducesTheRealIncident() {
            // 정상이면 매출/시총 ≈ 0.5. 매출이 100배 작으면 ≈ 0.005.
            DataAnomalyRules.Anomaly a = DataAnomalyRules.financialUnitMismatch(0.005, 2100);

            assertThat(a).isNotNull();
            assertThat(a.severity()).isEqualTo(DataAnomalyRules.WARNING);
            // 한쪽만 고치면 ROE 가 틀어진다는 함정을 문장이 말해야 한다
            assertThat(a.detail()).contains("ROE");
            assertThat(a.evidence()).contains("2100");
        }

        @Test
        @DisplayName("정상 비율이면 조용하다 — 업종 분산(0.1~5)에 오탐이 없어야 한다")
        void normalRatioIsSilent() {
            assertThat(DataAnomalyRules.financialUnitMismatch(0.5, 2100)).isNull();
            assertThat(DataAnomalyRules.financialUnitMismatch(0.08, 2100)).isNull();
            assertThat(DataAnomalyRules.financialUnitMismatch(3.2, 2100)).isNull();
        }

        @Test
        @DisplayName("표본이 적으면 판정하지 않는다 — 중앙값이 요동친다")
        void tooFewSamplesIsSilent() {
            assertThat(DataAnomalyRules.financialUnitMismatch(0.005, 50)).isNull();
        }

        @Test
        @DisplayName("중앙값이 null 이면 판정 불가 — 0 으로 세지 않는다(§4c)")
        void nullMedianIsSilent() {
            assertThat(DataAnomalyRules.financialUnitMismatch(null, 2100)).isNull();
        }
    }
}
