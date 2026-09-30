package com.myplatform.backend.service;

import com.myplatform.backend.service.EarningsQuality.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이익의 질 판정 — {@link EarningsQuality#judge}.
 *
 * <p>고치려는 결함(2026-09-29 운영): AI 스윙 5종목이 전부 100점이었는데, 1·3위는 거래정지 종목이고
 * 2위 베뉴지는 순이익 3,564억이 영업이익 146억의 24배였다. PER·ROE 가 순이익으로 만들어지니
 * 한 번의 영업외 이익이 PER(→0.7)과 ROE(→51.7%)를 동시에 극단으로 밀어 마법의 공식 1등이 된다.
 * 값은 전부 2026-09-29 KIS 일별 행(TTM, 억원) 실측이다.
 */
class EarningsQualityTest {

    private static Verdict judge(String revenue, String operatingProfit, String netIncome) {
        return EarningsQuality.judge(dec(revenue), dec(operatingProfit), dec(netIncome));
    }

    private static BigDecimal dec(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    @Nested
    @DisplayName("순이익이 본업으로 설명되지 않는다")
    class NonOperatingDominant {

        @Test
        @DisplayName("베뉴지 — 순이익이 영업이익의 24배(매출의 7배)")
        void venuezi() {
            assertThat(judge("504", "146", "3564")).isEqualTo(Verdict.NON_OPERATING_DOMINANT);
        }

        @Test
        @DisplayName("인지소프트 — 순이익이 매출보다 크다")
        void inzisoft() {
            assertThat(judge("185", "34", "710")).isEqualTo(Verdict.NON_OPERATING_DOMINANT);
        }

        @Test
        @DisplayName("삼부토건 7.1배 · 오성첨단소재 6.3배 · 솔본 2.4배")
        void otherObservedCases() {
            assertThat(judge("1021", "249", "1763")).isEqualTo(Verdict.NON_OPERATING_DOMINANT);
            assertThat(judge("1689", "282", "1768")).isEqualTo(Verdict.NON_OPERATING_DOMINANT);
            assertThat(judge("1251", "278", "679")).isEqualTo(Verdict.NON_OPERATING_DOMINANT);
        }

        @Test
        @DisplayName("영업손실인데 순이익 흑자 — 순이익 전부가 본업 밖")
        void operatingLossButNetProfit() {
            assertThat(judge("500", "-30", "120")).isEqualTo(Verdict.NON_OPERATING_DOMINANT);
        }

        @Test
        @DisplayName("경계 — 정확히 2배는 통과, 넘으면 제외")
        void boundaryIsStrictlyGreater() {
            assertThat(judge("1000", "100", "200")).isEqualTo(Verdict.OK);
            assertThat(judge("1000", "100", "200.01")).isEqualTo(Verdict.NON_OPERATING_DOMINANT);
        }
    }

    @Nested
    @DisplayName("영업이익이 매출로 설명되지 않는다")
    class OperatingExceedsRevenue {

        @Test
        @DisplayName("이오플로우 — 매출 20억에 영업이익 799억(영업이익률 3,995%)")
        void eoflow() {
            assertThat(judge("20", "799", "603")).isEqualTo(Verdict.OPERATING_EXCEEDS_REVENUE);
        }

        @Test
        @DisplayName("피씨엘 — 매출 37억에 영업이익 58억(순이익/영업이익은 0.9배라 첫 규칙으론 못 잡는다)")
        void pcl() {
            assertThat(judge("37", "58", "52")).isEqualTo(Verdict.OPERATING_EXCEEDS_REVENUE);
        }

        @Test
        @DisplayName("매출이 없거나 0 이하면 이 규칙은 판정하지 않는다 — 0 이 결측일 수 있다")
        void noRevenueMeansNoJudgementOnThisRule() {
            assertThat(judge(null, "58", "52")).isEqualTo(Verdict.OK);
            assertThat(judge("0", "58", "52")).isEqualTo(Verdict.OK);
        }
    }

    @Nested
    @DisplayName("정상 — 순이익이 영업이익 근처")
    class Normal {

        @Test
        @DisplayName("액토즈소프트 1.4배 · 영화테크 1.74배 · KG에코솔루션 1.82배 — 이자·지분 이익이 있는 흔한 범위")
        void cashRichButOperating() {
            assertThat(judge("742", "379", "531")).isEqualTo(Verdict.OK);
            assertThat(judge("1068", "167", "291")).isEqualTo(Verdict.OK);
            assertThat(judge("81436", "1520", "2767")).isEqualTo(Verdict.OK);
        }

        @Test
        @DisplayName("티에이치엔 — 순이익이 영업이익보다 작다")
        void thn() {
            assertThat(judge("11302", "789", "750")).isEqualTo(Verdict.OK);
        }

        @Test
        @DisplayName("적자는 이 판정의 대상이 아니다(PER·ROE 가 이미 음수)")
        void netLossIsNotThisRulesConcern() {
            assertThat(judge("1000", "50", "-80")).isEqualTo(Verdict.OK);
            assertThat(judge("1000", "-50", "-80")).isEqualTo(Verdict.OK);
        }
    }

    @Nested
    @DisplayName("판정 불가는 통과 — 결측은 제외 근거가 아니다(§4c)")
    class Unknown {

        @Test
        @DisplayName("진양제약 — 일별 행에 매출·영업이익·순이익이 없다(PER·ROE 는 있다)")
        void jinyang() {
            Verdict v = judge(null, null, null);
            assertThat(v).isEqualTo(Verdict.UNKNOWN);
            assertThat(v.distorted()).isFalse();
        }

        @Test
        @DisplayName("영업이익이나 순이익 한쪽만 없어도 판정 불가")
        void oneSideMissing() {
            assertThat(judge("1000", null, "300")).isEqualTo(Verdict.UNKNOWN);
            assertThat(judge("1000", "100", null)).isEqualTo(Verdict.UNKNOWN);
        }
    }

    @Test
    @DisplayName("제외 대상은 두 왜곡뿐")
    void onlyTheTwoDistortionsAreExcluded() {
        assertThat(Verdict.NON_OPERATING_DOMINANT.distorted()).isTrue();
        assertThat(Verdict.OPERATING_EXCEEDS_REVENUE.distorted()).isTrue();
        assertThat(Verdict.OK.distorted()).isFalse();
        assertThat(Verdict.UNKNOWN.distorted()).isFalse();
    }
}
