package com.myplatform.backend.service;

import com.myplatform.backend.service.QuarterlyFinancials.Figures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 분기 재무 순수함수 ({@link QuarterlyFinancials}) — AUDIT 2026-08-21 R1 대응.
 *
 * <p>R1 의 본질은 "일별 스냅샷을 분기라고 부른 것"이었다. 새 경로는 KIS {@code stac_yymm} 을
 * 분기 정체성의 유일한 출처로 삼는데, 그 파싱·누적 환산이 틀리면 <b>R1 보다 나쁜 결함</b>
 * (유령 서프라이즈)이 된다 — 그래서 경계를 전부 못 박는다.
 */
class QuarterlyFinancialsTest {

    private static BigDecimal bd(String v) { return v == null ? null : new BigDecimal(v); }

    private static Figures cum(String period, String rev, String op, String net) {
        YearMonth ym = QuarterlyFinancials.parseFiscalPeriod(period);
        return new Figures(period, QuarterlyFinancials.periodEnd(ym), bd(rev), bd(op), bd(net), true);
    }

    private static Figures ind(String period, String rev, String op, String net) {
        YearMonth ym = QuarterlyFinancials.parseFiscalPeriod(period);
        return new Figures(period, QuarterlyFinancials.periodEnd(ym), bd(rev), bd(op), bd(net), false);
    }

    @Nested
    @DisplayName("stac_yymm 파싱")
    class Parsing {

        @Test
        @DisplayName("정상 YYYYMM 은 YearMonth 로")
        void parsesNormal() {
            assertThat(QuarterlyFinancials.parseFiscalPeriod("202506")).isEqualTo(YearMonth.of(2025, 6));
            assertThat(QuarterlyFinancials.parseFiscalPeriod(" 202512 ")).isEqualTo(YearMonth.of(2025, 12));
        }

        @Test
        @DisplayName("KIS 가 실제로 주는 결측 표기는 예외 아니라 null — 호출부가 try/catch 하지 않게")
        void missingBecomesNull() {
            assertThat(QuarterlyFinancials.parseFiscalPeriod(null)).isNull();
            assertThat(QuarterlyFinancials.parseFiscalPeriod("")).isNull();
            assertThat(QuarterlyFinancials.parseFiscalPeriod("-")).isNull();
            assertThat(QuarterlyFinancials.parseFiscalPeriod("2025")).isNull();
            assertThat(QuarterlyFinancials.parseFiscalPeriod("2025AB")).isNull();
        }

        @Test
        @DisplayName("존재하지 않는 월·상식 밖 연도는 null (쓰레기값이 분기로 저장되면 안 됨)")
        void impossibleValuesAreNull() {
            assertThat(QuarterlyFinancials.parseFiscalPeriod("202513")).isNull();
            assertThat(QuarterlyFinancials.parseFiscalPeriod("202500")).isNull();
            assertThat(QuarterlyFinancials.parseFiscalPeriod("000106")).isNull();
        }

        @Test
        @DisplayName("periodEnd 는 말일 — 윤년 2월 포함")
        void periodEndIsLastDay() {
            assertThat(QuarterlyFinancials.periodEnd(YearMonth.of(2025, 6)))
                    .isEqualTo(LocalDate.of(2025, 6, 30));
            assertThat(QuarterlyFinancials.periodEnd(YearMonth.of(2024, 2)))
                    .isEqualTo(LocalDate.of(2024, 2, 29));
            assertThat(QuarterlyFinancials.periodEnd(null)).isNull();
        }
    }

    @Nested
    @DisplayName("인접 분기 판정")
    class Adjacency {

        @Test
        @DisplayName("정확히 3개월 차이만 인접 — 말일 일수(89~92일)에 흔들리지 않는다")
        void exactlyThreeMonths() {
            assertThat(QuarterlyFinancials.isAdjacentQuarter(
                    LocalDate.of(2025, 3, 31), LocalDate.of(2025, 6, 30))).isTrue();
            // 2월이 낀 구간은 89일뿐이라 '일수' 기준이면 놓친다
            assertThat(QuarterlyFinancials.isAdjacentQuarter(
                    LocalDate.of(2024, 12, 31), LocalDate.of(2025, 3, 31))).isTrue();
        }

        @Test
        @DisplayName("분기 건너뜀·같은 분기·역순은 인접 아님")
        void nonAdjacent() {
            assertThat(QuarterlyFinancials.isAdjacentQuarter(
                    LocalDate.of(2025, 3, 31), LocalDate.of(2025, 9, 30))).isFalse();
            assertThat(QuarterlyFinancials.isAdjacentQuarter(
                    LocalDate.of(2025, 6, 30), LocalDate.of(2025, 6, 30))).isFalse();
            assertThat(QuarterlyFinancials.isAdjacentQuarter(
                    LocalDate.of(2025, 6, 30), LocalDate.of(2025, 3, 31))).isFalse();
            assertThat(QuarterlyFinancials.isAdjacentQuarter(null, LocalDate.of(2025, 6, 30))).isFalse();
        }

        @Test
        @DisplayName("R1 의 원형 — 하루 차이는 인접 분기가 아니다")
        void oneDayApartIsNotAQuarter() {
            // 일별 스냅샷 2행(어제/오늘)을 '전분기 대비'로 읽던 것이 R1 이다.
            assertThat(QuarterlyFinancials.isAdjacentQuarter(
                    LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 26))).isFalse();
        }
    }

    @Nested
    @DisplayName("누적(YTD) 판정")
    class CumulativeDetection {

        @Test
        @DisplayName("매출이 시기 역순으로 단조 증가 + 1.8배 초과면 누적")
        void detectsCumulative() {
            List<Figures> recentFirst = List.of(
                    ind("202509", "3000", "300", "250"),   // 3분기 누적
                    ind("202506", "2000", "200", "160"),   // 반기 누적
                    ind("202503", "1000", "100", "80"));   // 1분기
            assertThat(QuarterlyFinancials.detectCumulative(recentFirst)).isTrue();
        }

        @Test
        @DisplayName("개별 분기(들쭉날쭉)면 누적 아님")
        void individualIsNotCumulative() {
            List<Figures> recentFirst = List.of(
                    ind("202509", "1100", "100", "80"),
                    ind("202506", "1050", "95", "75"),
                    ind("202503", "1000", "90", "70"));
            assertThat(QuarterlyFinancials.detectCumulative(recentFirst)).isFalse();
        }

        @Test
        @DisplayName("3개 미만·매출 결측이면 판정 불가 → false(개별 취급, 종전 동작)")
        void undecidableIsFalse() {
            assertThat(QuarterlyFinancials.detectCumulative(null)).isFalse();
            assertThat(QuarterlyFinancials.detectCumulative(List.of())).isFalse();
            assertThat(QuarterlyFinancials.detectCumulative(List.of(
                    ind("202509", "3000", "300", "250"),
                    ind("202506", "2000", "200", "160")))).isFalse();
            assertThat(QuarterlyFinancials.detectCumulative(List.of(
                    ind("202509", null, "300", "250"),
                    ind("202506", "2000", "200", "160"),
                    ind("202503", "1000", "100", "80")))).isFalse();
        }
    }

    @Nested
    @DisplayName("누적 → 개별 분기 환산")
    class Decumulation {

        @Test
        @DisplayName("이미 개별이면 그대로 통과")
        void individualPassesThrough() {
            List<Figures> in = List.of(ind("202503", "1000", "100", "80"),
                                       ind("202506", "1100", "110", "90"));
            List<Figures> out = QuarterlyFinancials.toIndividualQuarters(in);
            assertThat(out).hasSize(2);
            assertThat(out.get(1).revenue()).isEqualByComparingTo("1100");
        }

        @Test
        @DisplayName("누적은 직전 누적을 빼서 개별로 — 첫 행은 회계연도 첫 분기인지 몰라 제외")
        void subtractsPriorCumulative() {
            List<Figures> in = List.of(
                    cum("202503", "1000", "100", "80"),
                    cum("202506", "2200", "230", "180"),
                    cum("202509", "3600", "390", "300"));

            List<Figures> out = QuarterlyFinancials.toIndividualQuarters(in);

            // 202503 은 직전 누적 행이 없어 제외(§4c — 첫 분기 여부 판별 불가)
            assertThat(out).hasSize(2);
            assertThat(out.get(0).fiscalPeriod()).isEqualTo("202506");
            assertThat(out.get(0).revenue()).isEqualByComparingTo("1200");   // 2200-1000
            assertThat(out.get(0).operatingProfit()).isEqualByComparingTo("130");
            assertThat(out.get(1).fiscalPeriod()).isEqualTo("202509");
            assertThat(out.get(1).revenue()).isEqualByComparingTo("1400");   // 3600-2200
            assertThat(out).allSatisfy(f -> assertThat(f.cumulative()).isFalse());
        }

        @Test
        @DisplayName("누적 매출이 줄면 회계연도가 바뀐 것 — 그 행 자체가 첫 분기 개별값")
        void detectsFiscalYearReset() {
            List<Figures> in = List.of(
                    cum("202509", "3600", "390", "300"),   // 전년 3분기 누적
                    cum("202512", "4800", "500", "400"),   // 전년 연간 누적
                    cum("202603", "1100", "120", "95"));   // 새 회계연도 1분기 (누적 급감)

            List<Figures> out = QuarterlyFinancials.toIndividualQuarters(in);

            Figures q1 = out.stream().filter(f -> "202603".equals(f.fiscalPeriod())).findFirst().orElseThrow();
            // 4800 을 빼서 -3700 이 되면 "적자 전환" 유령 서프라이즈가 된다 — 그러면 안 된다
            assertThat(q1.revenue()).isEqualByComparingTo("1100");
            assertThat(q1.operatingProfit()).isEqualByComparingTo("120");
        }

        @Test
        @DisplayName("중간 분기가 결측이면 건너뛴 차감을 하지 않고 제외")
        void skipsWhenQuarterMissing() {
            List<Figures> in = List.of(
                    cum("202503", "1000", "100", "80"),
                    cum("202509", "3600", "390", "300"));   // 202506 결측

            List<Figures> out = QuarterlyFinancials.toIndividualQuarters(in);

            // 3600-1000=2600 을 "3분기 개별"이라 하면 6개월치를 한 분기로 부풀리는 것
            assertThat(out).isEmpty();
        }

        @Test
        @DisplayName("결측 금액은 0 이 아니라 null 로 남는다 — 없는 이익을 만들어내지 않는다")
        void missingStaysNull() {
            List<Figures> in = List.of(
                    cum("202503", "1000", null, "80"),
                    cum("202506", "2200", "230", "180"));

            List<Figures> out = QuarterlyFinancials.toIndividualQuarters(in);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).revenue()).isEqualByComparingTo("1200");
            assertThat(out.get(0).operatingProfit()).isNull();   // 230-null = null (230 아님)
            assertThat(out.get(0).netIncome()).isEqualByComparingTo("100");
        }
    }

    @Nested
    @DisplayName("비교 대상 2분기 선택")
    class PairSelection {

        @Test
        @DisplayName("최신 + 그 3개월 전이 모두 있어야 짝이 된다")
        void picksAdjacentPair() {
            Figures[] pair = QuarterlyFinancials.latestAdjacentPair(List.of(
                    ind("202503", "1000", "100", "80"),
                    ind("202506", "1200", "130", "100"),
                    ind("202509", "1400", "160", "120")));

            assertThat(pair).isNotNull();
            assertThat(pair[0].fiscalPeriod()).isEqualTo("202509");
            assertThat(pair[1].fiscalPeriod()).isEqualTo("202506");
        }

        @Test
        @DisplayName("최신 직전이 3개월 전이 아니면 비교하지 않는다(건너뛴 비교는 뻥튀기)")
        void refusesNonAdjacent() {
            assertThat(QuarterlyFinancials.latestAdjacentPair(List.of(
                    ind("202503", "1000", "100", "80"),
                    ind("202512", "1400", "160", "120")))).isNull();
        }

        @Test
        @DisplayName("2개 미만이면 null")
        void needsTwo() {
            assertThat(QuarterlyFinancials.latestAdjacentPair(null)).isNull();
            assertThat(QuarterlyFinancials.latestAdjacentPair(List.of())).isNull();
            assertThat(QuarterlyFinancials.latestAdjacentPair(
                    List.of(ind("202509", "1400", "160", "120")))).isNull();
        }
    }
}
