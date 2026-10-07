package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종목별 프로그램매매추이(체결)[국내주식-044] — 요청과 응답 해석(2026-10-07).
 *
 * <p>재현: 종목 상세의 '프로그램' 순매수가 한 번도 값을 가진 적이 없다(운영 로그 — 장중·장후 모두 '프로그램: null억, 시계열: 0건').
 * 요청이 공식 샘플에 없는 경로·tr_id({@code inquire-daily-programtrade} · {@code FHKST01010700})였고, 해석도 응답에 없는
 * {@code output1.ntby_tr_pbmn}·{@code output2[]} 를 읽었다. KIS 는 틀린 요청에도 200 을 준다(분봉·재무 tr_id 와 같은 부류).
 * 공식 샘플(koreainvestment/open-trading-api, examples_llm/domestic_stock/program_trade_by_stock): 경로
 * {@code program-trade-by-stock} · tr_id {@code FHPPG04650101} · 파라미터 FID_COND_MRKT_DIV_CODE·FID_INPUT_ISCD ·
 * 응답 {@code output} 배열(bsop_hour · stck_prpr · whol_smtn_shnu_vol · whol_smtn_shnu_tr_pbmn · whol_smtn_ntby_tr_pbmn …).
 *
 * <p>금액 단위는 샘플에 없고 다른 구현들도 원·백만원으로 갈린다 — 짐작하지 않고 같은 행의 매수 거래대금 ÷ (매수 거래량 × 현재가)로
 * 판정한다(≈1 원 · ≈1e-6 백만원 · 그 밖은 모름 → '-'). 2026-10-02 공매도 거래대금도 같은 방식(대금 ÷ (평균가 × 수량))으로 확인했다.
 */
class ProgramTradeRowsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode body(String rowsJson) throws Exception {
        return M.readTree("{\"rt_cd\":\"0\",\"output\":" + rowsJson + "}");
    }

    private static String row(String hour, long prpr, long buyVol, String buyAmt, String netAmt) {
        return String.format("{\"bsop_hour\":\"%s\",\"stck_prpr\":\"%d\",\"whol_smtn_shnu_vol\":\"%d\","
                + "\"whol_smtn_shnu_tr_pbmn\":\"%s\",\"whol_smtn_ntby_tr_pbmn\":\"%s\"}", hour, prpr, buyVol, buyAmt, netAmt);
    }

    @Test
    @DisplayName("요청은 공식 샘플 그대로 — program-trade-by-stock · FHPPG04650101 · 시장 J · 종목코드")
    void requestMatchesOfficialSample() {
        String url = KoreaInvestmentService.buildProgramTradeUrl("https://openapi.koreainvestment.com:9443", "005930");
        assertThat(url).isEqualTo("https://openapi.koreainvestment.com:9443/uapi/domestic-stock/v1/quotations/program-trade-by-stock"
                + "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=005930");
        assertThat(KoreaInvestmentService.TR_PROGRAM_TRADE_BY_STOCK).isEqualTo("FHPPG04650101");
    }

    @Test
    @DisplayName("원 단위 응답 — 매수대금 ÷ (매수량 × 현재가) ≈ 1 → 억으로 환산, 최신 시각 행이 당일 누적")
    void wonUnit() throws Exception {
        // 최신이 먼저 와도(공식 시간 API 는 첫 행이 최신) 시각으로 고른다
        JsonNode b = body("[" + row("100500", 560000, 1_000_000, "561000000000", "12345678900") + ","
                + row("095000", 558000, 400_000, "223000000000", "5000000000") + "]");
        ProgramTradeRows.Parsed p = ProgramTradeRows.parse(b);
        assertThat(p.netBuyEok()).isEqualByComparingTo("123.46");
        assertThat(p.series()).extracting(s -> s.getTime()).containsExactly("09:50", "10:05");   // 오래된 것부터
        assertThat(p.series()).extracting(s -> s.getNetBuyAmount().toPlainString()).containsExactly("50.00", "123.46");
    }

    @Test
    @DisplayName("백만원 단위 응답 — 비율 ≈ 1e-6 → 같은 억 값")
    void millionWonUnit() throws Exception {
        JsonNode b = body("[" + row("100500", 560000, 1_000_000, "561000", "12346") + "]");
        assertThat(ProgramTradeRows.parse(b).netBuyEok()).isEqualByComparingTo("123.46");
    }

    @Test
    @DisplayName("재현: 단위를 판정할 수 없으면 값을 만들지 않는다(화면 '-') — 비율은 알려 준다")
    void unknownUnit() throws Exception {
        JsonNode b = body("[" + row("100500", 560000, 1_000_000, "561000000", "12345678") + "]");   // 비율 ≈ 1e-3
        ProgramTradeRows.Parsed p = ProgramTradeRows.parse(b);
        assertThat(p.netBuyEok()).isNull();
        assertThat(p.series()).isEmpty();
        assertThat(p.unitRatio()).isNotNull();
    }

    @Test
    @DisplayName("재현: 예전 해석이 읽던 output1/output2 모양은 값이 아니다 — 응답에 output 이 없으면 null")
    void oldShapeOrEmptyIsNothing() throws Exception {
        assertThat(ProgramTradeRows.parse(M.readTree("{\"rt_cd\":\"0\",\"output1\":{\"ntby_tr_pbmn\":\"100\"},\"output2\":[]}"))
                .netBuyEok()).isNull();
        assertThat(ProgramTradeRows.parse(body("[]")).netBuyEok()).isNull();
        assertThat(ProgramTradeRows.parse(null).netBuyEok()).isNull();
    }

    @Test
    @DisplayName("프로그램 매수가 아직 없고 순매수가 0 이면 0 — 어느 단위로도 0 이다")
    void zeroWithoutBuys() throws Exception {
        JsonNode b = body("[" + row("090100", 560000, 0, "0", "0") + "]");
        assertThat(ProgramTradeRows.parse(b).netBuyEok()).isEqualByComparingTo("0");
    }
}
