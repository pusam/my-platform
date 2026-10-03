package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주식현재가 투자자(FHKST01010900) 응답 해석 — {@code ScalpingAnalysisService.todayInvestorNetEok}(2026-10-03).
 *
 * <p>재현: 응답 {@code output} 은 일자별 행 배열인데(공식 샘플 chk_inquire_investor.py — 행마다 stck_bsop_date) 객체로 읽어
 * 외국인·기관 순매수가 늘 null 이었다. 금액을 원으로 보고 1억으로 나눴지만 이 필드는 백만원 단위다(여러 구현이 KIS 문서를
 * 인용). 당일 행은 장 종료 후 제공된다(공식 샘플 주석) — 장중엔 오늘 값이 없다고 본다.
 */
class ScalpingInvestorRowsTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);

    private static JsonNode output(String rows) throws Exception {
        return M.readTree("[" + rows + "]");
    }

    @Test
    @DisplayName("재현: 배열의 오늘 행을 읽고 백만원을 억으로 — 장 마감 뒤")
    void todayRowAfterClose() throws Exception {
        JsonNode out = output("""
                {"stck_bsop_date":"20261002","frgn_ntby_tr_pbmn":"-106344","orgn_ntby_tr_pbmn":"3162"},
                {"stck_bsop_date":"20261001","frgn_ntby_tr_pbmn":"5000","orgn_ntby_tr_pbmn":"-100"}""");

        ScalpingAnalysisService.InvestorNet net = ScalpingAnalysisService.todayInvestorNetEok(out, TODAY, true);

        assertThat(net.foreignEok()).isEqualByComparingTo("-1063.44");
        assertThat(net.institutionEok()).isEqualByComparingTo("31.62");
    }

    @Test
    @DisplayName("장중엔 오늘 값이 없다 — 어제 행을 오늘처럼 쓰지 않는다")
    void beforeCloseIsUnknown() throws Exception {
        JsonNode out = output("""
                {"stck_bsop_date":"20261002","frgn_ntby_tr_pbmn":"0","orgn_ntby_tr_pbmn":"0"},
                {"stck_bsop_date":"20261001","frgn_ntby_tr_pbmn":"5000","orgn_ntby_tr_pbmn":"-100"}""");

        ScalpingAnalysisService.InvestorNet net = ScalpingAnalysisService.todayInvestorNetEok(out, TODAY, false);

        assertThat(net.foreignEok()).isNull();
        assertThat(net.institutionEok()).isNull();
    }

    @Test
    @DisplayName("오늘 행이 없거나(휴장·미제공) 칸이 비면 모름")
    void missingRowOrBlankIsUnknown() throws Exception {
        assertThat(ScalpingAnalysisService.todayInvestorNetEok(
                output("{\"stck_bsop_date\":\"20261001\",\"frgn_ntby_tr_pbmn\":\"5000\"}"), TODAY, true).foreignEok()).isNull();
        ScalpingAnalysisService.InvestorNet blank = ScalpingAnalysisService.todayInvestorNetEok(
                output("{\"stck_bsop_date\":\"20261002\",\"frgn_ntby_tr_pbmn\":\"\",\"orgn_ntby_tr_pbmn\":\"\"}"), TODAY, true);
        assertThat(blank.foreignEok()).isNull();
        assertThat(blank.institutionEok()).isNull();
        // 배열이 아니면(예전 가정) 모름
        assertThat(ScalpingAnalysisService.todayInvestorNetEok(M.readTree("{\"frgn_ntby_tr_pbmn\":\"1\"}"), TODAY, true).foreignEok()).isNull();
    }
}
