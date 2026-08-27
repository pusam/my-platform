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
        @DisplayName("분기가 더해지며 크게 뛰면 누적 — 입력 순서와 무관하다")
        void detectsCumulative() {
            // 일부러 뒤섞어 넣는다. 이전 구현은 '최신 3개'를 순서대로 봐서 순서에 의존했다.
            List<Figures> shuffled = List.of(
                    ind("202512", "4000", "400", "330"),   // 연간 누적
                    ind("202503", "1000", "100", "80"),    // 1분기
                    ind("202509", "3000", "300", "250"),   // 3분기 누적
                    ind("202506", "2000", "200", "160"));  // 반기 누적
            assertThat(QuarterlyFinancials.detectCumulative(shuffled)).isTrue();
        }

        @Test
        @DisplayName("성장하는 개별 분기는 누적 아님 — 증가 '비율'만 보면 여기서 오판한다")
        void risingIndividualIsNotCumulative() {
            // 5쌍 중 4쌍이 증가(80%)라 비율만 보면 누적으로 오판된다.
            // 배율 중앙값이 약 1.09 라 누적(약 1.75)과 갈린다.
            List<Figures> rows = List.of(
                    ind("202503", "1000", "100", "80"), ind("202506", "1090", "110", "88"),
                    ind("202509", "1190", "120", "96"), ind("202512", "1300", "130", "104"),
                    ind("202603", "1250", "125", "100"), ind("202606", "1360", "136", "109"));
            assertThat(QuarterlyFinancials.detectCumulative(rows)).isFalse();
        }

        @Test
        @DisplayName("들쭉날쭉한 개별 분기도 누적 아님")
        void individualIsNotCumulative() {
            List<Figures> rows = List.of(
                    ind("202503", "1000", "90", "70"), ind("202506", "1050", "95", "75"),
                    ind("202509", "980", "88", "68"), ind("202512", "1100", "100", "80"));
            assertThat(QuarterlyFinancials.detectCumulative(rows)).isFalse();
        }

        @Test
        @DisplayName("인접쌍 3개 미만·매출 결측이면 판정 불가 → false(개별 취급, 종전 동작)")
        void undecidableIsFalse() {
            assertThat(QuarterlyFinancials.detectCumulative(null)).isFalse();
            assertThat(QuarterlyFinancials.detectCumulative(List.of())).isFalse();
            // 3행 = 인접쌍 2개 → 판정 불가. 이전 구현은 3행으로 단정했고 그게 결함이었다.
            assertThat(QuarterlyFinancials.detectCumulative(List.of(
                    ind("202503", "1000", "100", "80"),
                    ind("202506", "2000", "200", "160"),
                    ind("202509", "3000", "300", "250")))).isFalse();
            // 매출 결측 행은 비교에서 빠져 인접쌍이 모자라게 된다
            assertThat(QuarterlyFinancials.detectCumulative(List.of(
                    ind("202503", "1000", "100", "80"), ind("202506", null, "200", "160"),
                    ind("202509", "3000", "300", "250"), ind("202512", "4000", "400", "330")))).isFalse();
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
    @Nested
    @DisplayName("prod 실측 회귀 (2026-08-27) — 삼성전자 누적 데이터")
    class RealSamsungData {

        /**
         * 2026-08-27 운영 DB 에서 그대로 가져온 삼성전자 매출(stock_quarterly_financial).
         * 매 회계연도 3월에 리셋되고 12월까지 단조 증가한다 = 명백한 누적(YTD).
         */
        private List<Figures> samsungRevenue() {
            String[][] raw = {
                {"202503", "7914.05"}, {"202506", "15370.68"}, {"202509", "23976.86"},
                {"202512", "33360.59"}, {"202603", "13387.34"}, {"202606", "30537.29"}
            };
            List<Figures> out = new java.util.ArrayList<>();
            for (String[] r : raw) out.add(cum(r[0], r[1], r[1], r[1]));
            return out;
        }

        @Test
        @DisplayName("누적으로 판정한다 — 옛 휴리스틱은 최신 3개만 봐서 FY 리셋에 걸려 실패했다")
        void detectsCumulativeAcrossFiscalYearBoundary() {
            // 최신 3개(202606, 202603, 202512)는 202603 리셋 때문에 단조증가가 깨진다.
            // 이력 전체를 보면 인접쌍 5개 중 4개가 증가 → 누적.
            assertThat(QuarterlyFinancials.detectCumulative(samsungRevenue())).isTrue();
        }

        @Test
        @DisplayName("TTM 은 48,527 — 누적 4개를 그냥 더한 101,260(2.09배)이 아니다")
        void ttmMatchesHandCalculation() {
            List<Figures> flagged = QuarterlyFinancials.withDetectedCumulative(samsungRevenue());
            BigDecimal[] ttm = QuarterlyFinancials.ttmSum(
                    QuarterlyFinancials.toIndividualQuarters(flagged));

            assertThat(ttm).isNotNull();
            // 정공법 검산: FY2025(33,360.59) + H1-2026(30,537.29) − H1-2025(15,370.68) = 48,527.20
            assertThat(ttm[0]).isEqualByComparingTo("48527.20");

            // 옛 방식(누적 4개 단순합)이었다면 이 값이었다 — 2.09배
            BigDecimal naive = bd("30537.29").add(bd("13387.34")).add(bd("33360.59")).add(bd("23976.86"));
            assertThat(naive).isEqualByComparingTo("101262.08");
            assertThat(ttm[0]).isLessThan(naive);
        }

        @Test
        @DisplayName("개별 분기 환산값도 상식에 맞는다 — 분기 매출이 서로 비슷한 규모")
        void individualQuartersAreSane() {
            List<Figures> ind = QuarterlyFinancials.toIndividualQuarters(
                    QuarterlyFinancials.withDetectedCumulative(samsungRevenue()));

            // 202503 은 직전 누적이 없어 제외 → 202506 부터
            assertThat(ind).extracting(Figures::fiscalPeriod)
                    .containsExactly("202506", "202509", "202512", "202603", "202606");
            assertThat(ind.get(0).revenue()).isEqualByComparingTo("7456.63");   // 15370.68-7914.05
            assertThat(ind.get(3).revenue()).isEqualByComparingTo("13387.34");  // FY 리셋 → 그대로
            assertThat(ind.get(4).revenue()).isEqualByComparingTo("17149.95");  // 30537.29-13387.34
        }

        @Test
        @DisplayName("개별 분기 시계열은 누적으로 오판되지 않는다 — 반대 방향 오판 방지")
        void individualSeriesIsNotFlaggedCumulative() {
            // 실제 개별 분기라면 오르내림이 반반에 가깝다
            List<Figures> ind = List.of(
                    ind("202503", "7900", "600", "500"), ind("202506", "7450", "550", "480"),
                    ind("202509", "8600", "700", "600"), ind("202512", "9380", "800", "700"),
                    ind("202603", "8100", "620", "520"), ind("202606", "8900", "750", "640"));
            assertThat(QuarterlyFinancials.detectCumulative(ind)).isFalse();
        }

        @Test
        @DisplayName("연속 4분기가 아니면 TTM 을 만들지 않는다 — 12개월치가 아니므로")
        void ttmRefusesGaps() {
            List<Figures> ind = List.of(
                    ind("202503", "100", "10", "8"), ind("202506", "110", "11", "9"),
                    ind("202512", "120", "12", "10"), ind("202603", "130", "13", "11"));
            assertThat(QuarterlyFinancials.ttmSum(ind)).isNull();
        }
    }
}
