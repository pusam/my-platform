package com.myplatform.backend.dartfinancial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dartfinancial.ControllingEarnings.Figures;
import com.myplatform.backend.dartfinancial.ControllingEarnings.Report;
import com.myplatform.backend.dartfinancial.ControllingEarnings.ReportCode;
import com.myplatform.backend.dartfinancial.ControllingEarnings.ReportKey;
import com.myplatform.backend.dartfinancial.ControllingEarnings.Ttm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지배주주 순이익 TTM — {@link ControllingEarnings}. 값은 2026-09-30 DART {@code fnlttSinglAcntAll} 실제 응답(원 단위).
 *
 * <p>다우기술(023590)은 연결 당기순이익의 56% 가 비지배지분(키움증권 소수주주) 몫이라, KIS 연결 순이익으로 만든 PER 이
 * 0.9 였다. 지배주주 기준 TTM 은 7,488.2억 — 네이버 분기 지배주주순이익 4개 합과 같다.
 */
class ControllingEarningsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode list(String json) throws Exception {
        return M.readTree(json);
    }

    /** 다우기술 2026 반기(연결) — 지배주주 귀속 순이익은 CIS 에만 있다. */
    private static final String DAOU_2026_H1 = "["
            + "{\"rcept_no\":\"20260814003900\",\"sj_div\":\"BS\",\"account_id\":\"ifrs-full_Equity\",\"account_nm\":\"자본총계\","
            + "\"thstrm_amount\":\"8400030034629\",\"frmtrm_amount\":\"7494224568073\"},"
            + "{\"rcept_no\":\"20260814003900\",\"sj_div\":\"BS\",\"account_id\":\"ifrs-full_EquityAttributableToOwnersOfParent\","
            + "\"account_nm\":\"지배기업소유주지분\",\"thstrm_amount\":\"3883281985289\",\"frmtrm_amount\":\"3446435267021\"},"
            + "{\"rcept_no\":\"20260814003900\",\"sj_div\":\"CIS\",\"account_id\":\"ifrs-full_ProfitLoss\",\"account_nm\":\"반기순이익\","
            + "\"thstrm_amount\":\"678203792305\",\"thstrm_add_amount\":\"1165367503401\",\"frmtrm_q_amount\":\"328369926570\","
            + "\"frmtrm_add_amount\":\"565842909578\"},"
            + "{\"rcept_no\":\"20260814003900\",\"sj_div\":\"CIS\",\"account_id\":\"ifrs-full_ProfitLossAttributableToOwnersOfParent\","
            + "\"account_nm\":\"지배기업소유주지분\",\"thstrm_amount\":\"281825969996\",\"thstrm_add_amount\":\"492382928525\","
            + "\"frmtrm_q_amount\":\"146633583747\",\"frmtrm_add_amount\":\"248744902085\"},"
            + "{\"rcept_no\":\"20260814003900\",\"sj_div\":\"CIS\",\"account_id\":\"ifrs-full_ComprehensiveIncomeAttributableToOwnersOfParent\","
            + "\"account_nm\":\"지배기업소유주지분\",\"thstrm_amount\":\"264000000000\",\"thstrm_add_amount\":\"512700000000\"}"
            + "]";

    /** 다우기술 2025 사업보고서(연결). */
    private static final String DAOU_2025_FY = "["
            + "{\"rcept_no\":\"20260318001606\",\"sj_div\":\"BS\",\"account_id\":\"ifrs-full_EquityAttributableToOwnersOfParent\","
            + "\"account_nm\":\"지배기업소유주지분\",\"thstrm_amount\":\"3446435267021\",\"frmtrm_amount\":\"2887288844494\"},"
            + "{\"rcept_no\":\"20260318001606\",\"sj_div\":\"CIS\",\"account_id\":\"ifrs-full_ProfitLossAttributableToOwnersOfParent\","
            + "\"account_nm\":\"지배기업소유주지분\",\"thstrm_amount\":\"505182368263\",\"frmtrm_amount\":\"355835455442\"}"
            + "]";

    @Nested
    @DisplayName("파싱")
    class Parse {

        @Test
        @DisplayName("다우기술 반기 — CIS 의 지배주주 귀속 순이익(누적·전년 동기 누적)과 지배지분 자본, 접수일")
        void daouHalfYear() throws Exception {
            Figures f = ControllingEarnings.parse(list(DAOU_2026_H1), ReportCode.H1, true);

            assertThat(f.ctrlNetIncome()).isEqualByComparingTo("4923.83");
            assertThat(f.ctrlNetIncomePrev()).isEqualByComparingTo("2487.45");
            assertThat(f.totalNetIncome()).as("비지배 포함 연결 순이익").isEqualByComparingTo("11653.68");
            assertThat(f.ctrlEquity()).isEqualByComparingTo("38832.82");
            assertThat(f.totalEquity()).isEqualByComparingTo("84000.30");
            assertThat(f.filedOn()).isEqualTo(LocalDate.of(2026, 8, 14));
        }

        @Test
        @DisplayName("포괄이익 귀속 계정(ComprehensiveIncome…)을 순이익으로 잡지 않는다 — 이름이 같아도 ID 가 다르다")
        void comprehensiveIncomeIsNotNetIncome() throws Exception {
            Figures f = ControllingEarnings.parse(list(DAOU_2026_H1), ReportCode.H1, true);
            assertThat(f.ctrlNetIncome()).isNotEqualByComparingTo("5127.00");
        }

        @Test
        @DisplayName("사업보고서는 연간값(thstrm_amount)과 전년 연간(frmtrm_amount)")
        void annualReport() throws Exception {
            Figures f = ControllingEarnings.parse(list(DAOU_2025_FY), ReportCode.FY, true);
            assertThat(f.ctrlNetIncome()).isEqualByComparingTo("5051.82");
            assertThat(f.ctrlNetIncomePrev()).isEqualByComparingTo("3558.35");
        }

        @Test
        @DisplayName("손익계산서(IS)에 있으면 IS 를 쓴다 — 삼성전자형")
        void incomeStatementPreferred() throws Exception {
            String json = "[{\"rcept_no\":\"20260814000001\",\"sj_div\":\"CIS\",\"account_id\":\"ifrs-full_ProfitLossAttributableToOwnersOfParent\","
                    + "\"thstrm_add_amount\":\"999900000000\",\"frmtrm_add_amount\":\"1\"},"
                    + "{\"rcept_no\":\"20260814000001\",\"sj_div\":\"IS\",\"account_id\":\"ifrs-full_ProfitLossAttributableToOwnersOfParent\","
                    + "\"thstrm_amount\":\"71269500000000\",\"thstrm_add_amount\":\"118370700000000\",\"frmtrm_add_amount\":\"50000000000000\"}]";
            Figures f = ControllingEarnings.parse(list(json), ReportCode.H1, true);
            assertThat(f.ctrlNetIncome()).isEqualByComparingTo("1183707.00");
            assertThat(f.ctrlNetIncomePrev()).isEqualByComparingTo("500000.00");
        }

        @Test
        @DisplayName("별도(OFS)는 비지배가 없다 — 지배주주 = 당기순이익·자본총계")
        void separateStatements() throws Exception {
            String json = "[{\"rcept_no\":\"20260813000100\",\"sj_div\":\"IS\",\"account_id\":\"ifrs-full_ProfitLoss\",\"account_nm\":\"반기순이익\","
                    + "\"thstrm_add_amount\":\"4500000000\",\"frmtrm_add_amount\":\"3000000000\"},"
                    + "{\"rcept_no\":\"20260813000100\",\"sj_div\":\"BS\",\"account_id\":\"ifrs-full_Equity\",\"thstrm_amount\":\"90000000000\"}]";
            Figures f = ControllingEarnings.parse(list(json), ReportCode.H1, false);
            assertThat(f.ctrlNetIncome()).isEqualByComparingTo("45.00");
            assertThat(f.ctrlNetIncomePrev()).isEqualByComparingTo("30.00");
            assertThat(f.ctrlEquity()).isEqualByComparingTo("900.00");
        }

        @Test
        @DisplayName("연결인데 표준 계정이 없으면 모른다(null) — 이름으로 추측하지 않는다")
        void nonStandardAccountsAreUnknown() throws Exception {
            String json = "[{\"rcept_no\":\"20260814000002\",\"sj_div\":\"CIS\",\"account_id\":\"-표준계정코드 미사용-\","
                    + "\"account_nm\":\"지배기업소유주지분\",\"thstrm_add_amount\":\"100000000000\"}]";
            Figures f = ControllingEarnings.parse(list(json), ReportCode.H1, true);
            assertThat(f.ctrlNetIncome()).isNull();
            assertThat(f.ctrlEquity()).isNull();
        }

        @Test
        @DisplayName("1분기는 누적 칸이 비어도 3개월 값이 곧 누적")
        void firstQuarterFallsBackToThreeMonths() throws Exception {
            String json = "[{\"rcept_no\":\"20260515000003\",\"sj_div\":\"IS\",\"account_id\":\"ifrs-full_ProfitLossAttributableToOwnersOfParent\","
                    + "\"thstrm_amount\":\"21060000000\",\"frmtrm_q_amount\":\"10210000000\"}]";
            Figures f = ControllingEarnings.parse(list(json), ReportCode.Q1, true);
            assertThat(f.ctrlNetIncome()).isEqualByComparingTo("210.60");
            assertThat(f.ctrlNetIncomePrev()).isEqualByComparingTo("102.10");
        }

        @Test
        @DisplayName("빈 값·'-'·숫자 아님은 모름(null)이지 0 이 아니다")
        void blankAmountIsUnknown() {
            assertThat(ControllingEarnings.eok("")).isNull();
            assertThat(ControllingEarnings.eok("-")).isNull();
            assertThat(ControllingEarnings.eok("abc")).isNull();
            assertThat(ControllingEarnings.eok("1,234,500,000,000")).isEqualByComparingTo("12345.00");
            assertThat(ControllingEarnings.eok("-50000000")).isEqualByComparingTo("-0.50");
        }
    }

    @Nested
    @DisplayName("TTM")
    class TtmCalc {

        private final LocalDate today = LocalDate.of(2026, 9, 30);
        private final ReportKey h1 = new ReportKey(2026, ReportCode.H1);
        private final ReportKey fy = new ReportKey(2025, ReportCode.FY);

        private Report daouH1() {
            return new Report(h1, "CFS", new BigDecimal("4923.83"), new BigDecimal("2487.45"),
                    new BigDecimal("38832.82"), LocalDate.of(2026, 8, 14));
        }

        private Report daouFy() {
            return new Report(fy, "CFS", new BigDecimal("5051.82"), new BigDecimal("3558.35"),
                    new BigDecimal("34464.35"), LocalDate.of(2026, 3, 18));
        }

        @Test
        @DisplayName("다우기술 — 4,923.83 + 5,051.82 − 2,487.45 = 7,488.20억(네이버 분기 지배주주순이익 4개 합과 같다)")
        void daou() {
            Ttm t = ControllingEarnings.ttm(List.of(daouFy(), daouH1()), today);
            assertThat(t.netIncome()).isEqualByComparingTo("7488.20");
            assertThat(t.equity()).as("최신 보고서의 지배지분 자본").isEqualByComparingTo("38832.82");
            assertThat(t.basedOn()).isEqualTo(h1);
        }

        @Test
        @DisplayName("최신이 사업보고서면 그 연간값이 TTM")
        void annualIsTtm() {
            Ttm t = ControllingEarnings.ttm(List.of(daouFy()), LocalDate.of(2026, 4, 10));
            assertThat(t.netIncome()).isEqualByComparingTo("5051.82");
        }

        @Test
        @DisplayName("직전 사업보고서가 없으면 순이익은 모른다 — 자본은 최신 보고서 것으로 안다")
        void missingPriorAnnual() {
            Ttm t = ControllingEarnings.ttm(List.of(daouH1()), today);
            assertThat(t.netIncome()).isNull();
            assertThat(t.equity()).isEqualByComparingTo("38832.82");
        }

        @Test
        @DisplayName("연결/별도가 다른 두 보고서는 섞지 않는다")
        void mixedStatementKindsAreUnknown() {
            Report fyOfs = new Report(fy, "OFS", new BigDecimal("5051.82"), new BigDecimal("3558.35"),
                    null, LocalDate.of(2026, 3, 18));
            assertThat(ControllingEarnings.ttm(List.of(fyOfs, daouH1()), today).netIncome()).isNull();
        }

        @Test
        @DisplayName("최신 보고서가 200일 넘게 묵었으면 모른다")
        void staleLatestIsUnknown() {
            assertThat(ControllingEarnings.ttm(List.of(daouFy(), daouH1()), LocalDate.of(2027, 3, 10))).isNull();
        }

        @Test
        @DisplayName("적자도 산술 그대로 — 부호를 바꾸거나 0 으로 만들지 않는다")
        void lossesAreArithmetic() {
            Report lossH1 = new Report(h1, "CFS", new BigDecimal("-120.00"), new BigDecimal("30.00"),
                    new BigDecimal("500.00"), LocalDate.of(2026, 8, 14));
            Report lossFy = new Report(fy, "CFS", new BigDecimal("-40.00"), null, null, LocalDate.of(2026, 3, 18));
            assertThat(ControllingEarnings.ttm(List.of(lossFy, lossH1), today).netIncome()).isEqualByComparingTo("-190.00");
        }

        @Test
        @DisplayName("보고서가 없으면 null")
        void nothing() {
            assertThat(ControllingEarnings.ttm(List.of(), today)).isNull();
        }
    }

    @Nested
    @DisplayName("조회 계획 — 제출 기한이 지난 보고서부터")
    class Candidates {

        @Test
        @DisplayName("9/30 — 반기(기한 8/14)가 최신, 3분기(기한 11/14)는 아직")
        void endOfSeptember() {
            assertThat(ControllingEarnings.candidates(LocalDate.of(2026, 9, 30), 4)).containsExactly(
                    new ReportKey(2026, ReportCode.H1), new ReportKey(2026, ReportCode.Q1),
                    new ReportKey(2025, ReportCode.FY), new ReportKey(2025, ReportCode.Q3));
        }

        @Test
        @DisplayName("기한 당일부터 후보 — 8/13 엔 1분기가 최신, 8/14 엔 반기")
        void dueDateBoundary() {
            assertThat(ControllingEarnings.candidates(LocalDate.of(2026, 8, 13), 1))
                    .containsExactly(new ReportKey(2026, ReportCode.Q1));
            assertThat(ControllingEarnings.candidates(LocalDate.of(2026, 8, 14), 1))
                    .containsExactly(new ReportKey(2026, ReportCode.H1));
        }

        @Test
        @DisplayName("사업보고서 기한은 3/31 — 3/30 엔 전년 3분기가 최신")
        void annualDue() {
            assertThat(ControllingEarnings.candidates(LocalDate.of(2026, 3, 30), 1))
                    .containsExactly(new ReportKey(2025, ReportCode.Q3));
            assertThat(ControllingEarnings.candidates(LocalDate.of(2026, 3, 31), 1))
                    .containsExactly(new ReportKey(2025, ReportCode.FY));
        }

        @Test
        @DisplayName("TTM 짝 — 분·반기의 직전 사업보고서")
        void priorAnnual() {
            assertThat(new ReportKey(2026, ReportCode.H1).priorAnnual()).isEqualTo(new ReportKey(2025, ReportCode.FY));
            assertThat(new ReportKey(2025, ReportCode.FY).priorAnnual()).isNull();
        }
    }
}
