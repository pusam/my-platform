package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.entity.InvestorStockDaily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종목별 투자자매매동향(일별) 해석·확정치 순위 — {@link InvestorFinalRows}(2026-11-05).
 *
 * <p>일별 순위(가집계)는 네이버 확정치와 부호 91%·크기 ±50% 안 33% 만 맞았다(10/28~11/3 600행). 삼성전자 10/30 외국인: 가집계
 * +1,210억 vs 확정 −2,366억. 금액 단위는 공식 샘플에 없어 같은 행의 대금 ÷ (거래량 × 종가)로 판정한다.
 */
class InvestorFinalRowsTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final LocalDate DAY = LocalDate.of(2026, 10, 30);

    private static JsonNode body(String row) throws Exception {
        return M.readTree("{\"rt_cd\":\"0\",\"output1\":{\"stck_prpr\":\"100000\"},\"output2\":[" + row + "]}");
    }

    /** 삼성전자 10/30 모양 — 외국인 순매도 2,366억. 금액 칸은 unit 원당 1. */
    private static String row(String date, long wonPerUnit) {
        return "{\"stck_bsop_date\":\"" + date + "\",\"stck_clpr\":\"100000\",\"prdy_ctrt\":\"-1.25\",\"acml_vol\":\"12345678\","
                + "\"frgn_ntby_qty\":\"-2366000\",\"orgn_ntby_qty\":\"500000\","
                + "\"frgn_ntby_tr_pbmn\":\"" + (-236_600_000_000L / wonPerUnit) + "\","
                + "\"orgn_ntby_tr_pbmn\":\"" + (50_000_000_000L / wonPerUnit) + "\","
                + "\"fund_ntby_tr_pbmn\":\"" + (12_345_000_000L / wonPerUnit) + "\","
                + "\"frgn_shnu_vol\":\"5000000\",\"frgn_shnu_tr_pbmn\":\"" + (500_000_000_000L / wonPerUnit) + "\"}";
    }

    @Test
    @DisplayName("백만원 단위 — 대금 ÷ (거래량 × 종가) ≈ 1e-6 이면 금액을 그렇게 읽는다(삼성전자 외국인 −2,366억)")
    void millionWonUnit() throws Exception {
        InvestorFinalRows.Parsed p = InvestorFinalRows.parse(body(row("20261030", 1_000_000)));

        assertThat(p.unitKnown()).isTrue();
        assertThat(p.unitRatio()).isCloseTo(1e-6, org.assertj.core.data.Offset.offset(1e-9));
        InvestorFinalRows.Row r = p.rows().get(0);
        assertThat(r.tradeDate()).isEqualTo(DAY);
        assertThat(r.foreignNet()).isEqualByComparingTo("-2366");
        assertThat(r.institutionNet()).isEqualByComparingTo("500");
        assertThat(r.pensionNet()).isEqualByComparingTo("123.45");
        assertThat(r.close()).isEqualByComparingTo("100000");
        assertThat(r.changeRate()).isEqualByComparingTo("-1.25");
        assertThat(r.volume()).isEqualTo(12345678L);
    }

    @Test
    @DisplayName("원 단위여도 같은 값이 나온다 — 단위를 짐작하지 않는다")
    void wonUnit() throws Exception {
        InvestorFinalRows.Row r = InvestorFinalRows.parse(body(row("20261030", 1))).rows().get(0);

        assertThat(r.foreignNet()).isEqualByComparingTo("-2366");
    }

    @Test
    @DisplayName("§4c — 단위를 판정 못 하면 그 응답을 쓰지 않는다(천 배 틀린 값을 저장하지 않는다)")
    void unknownUnitIsNotUsed() throws Exception {
        InvestorFinalRows.Parsed p = InvestorFinalRows.parse(body(row("20261030", 1_000)));

        assertThat(p.unitKnown()).isFalse();
        assertThat(p.rows()).isEmpty();
    }

    @Test
    @DisplayName("매수 대금 칸이 없으면 순매수 금액·수량으로 판정한다")
    void unitFromNetFields() throws Exception {
        String row = "{\"stck_bsop_date\":\"20261030\",\"stck_clpr\":\"100000\",\"frgn_ntby_qty\":\"-2366000\","
                + "\"frgn_ntby_tr_pbmn\":\"-236600\"}";

        InvestorFinalRows.Parsed p = InvestorFinalRows.parse(body(row));

        assertThat(p.unitKnown()).isTrue();
        assertThat(p.rows().get(0).foreignNet()).isEqualByComparingTo("-2366");
    }

    @Test
    @DisplayName("거래가 없던 종목 — 금액이 전부 0 이면 어느 단위로도 0 이다")
    void noTrades() throws Exception {
        String row = "{\"stck_bsop_date\":\"20261030\",\"stck_clpr\":\"1000\",\"frgn_ntby_tr_pbmn\":\"0\",\"frgn_ntby_qty\":\"0\"}";

        InvestorFinalRows.Parsed p = InvestorFinalRows.parse(body(row));

        assertThat(p.unitKnown()).isTrue();
        assertThat(p.rows().get(0).foreignNet()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("§4c — 응답에 없는 칸은 0 이 아니라 모름, 날짜를 못 읽는 행은 뺀다")
    void missingFields() throws Exception {
        String rows = row("20261030", 1_000_000).replace("\"fund_ntby_tr_pbmn\"", "\"other\"") + "," + row("-", 1_000_000);

        InvestorFinalRows.Parsed p = InvestorFinalRows.parse(body(rows));

        assertThat(p.rows()).hasSize(1);
        assertThat(p.rows().get(0).pensionNet()).isNull();
    }

    @Test
    @DisplayName("빈 응답·output2 없음 — 행이 없을 뿐이다")
    void emptyResponses() throws Exception {
        assertThat(InvestorFinalRows.parse(M.readTree("{\"rt_cd\":\"0\",\"output2\":[]}")).rows()).isEmpty();
        assertThat(InvestorFinalRows.parse(M.readTree("{\"rt_cd\":\"0\"}")).rows()).isEmpty();
        assertThat(InvestorFinalRows.parse(null).rows()).isEmpty();
    }

    private static InvestorStockDaily stock(String code, String foreign, String institution, String pension) {
        return InvestorStockDaily.builder().stockCode(code).tradeDate(DAY)
                .foreignNet(foreign == null ? null : new BigDecimal(foreign))
                .institutionNet(institution == null ? null : new BigDecimal(institution))
                .pensionNet(pension == null ? null : new BigDecimal(pension))
                .closePrice(new BigDecimal("219500")).changeRate(new BigDecimal("-2.16")).volume(1000L).build();
    }

    private static List<InvestorDailyTrade> of(List<InvestorDailyTrade> rows, String investor, String side) {
        return rows.stream().filter(r -> r.getInvestorType().equals(investor) && r.getTradeType().equals(side)).toList();
    }

    @Test
    @DisplayName("순위 — 순매수는 큰 순, 순매도는 많이 판 순. 순매도 금액은 음수로 저장하고 행은 FINAL 이다")
    void ranking() {
        List<InvestorDailyTrade> rows = InvestorFinalRows.rank(DAY, List.of(
                stock("005930", "-2366", "500", "100"),
                stock("000660", "1200", "-300", null),
                stock("009150", "800", "0", "-50")), Map.of("005930", "삼성전자")::get, 30);

        List<InvestorDailyTrade> buys = of(rows, "FOREIGN", "BUY");
        assertThat(buys).extracting(InvestorDailyTrade::getStockCode).containsExactly("000660", "009150");
        assertThat(buys).extracting(InvestorDailyTrade::getRankNum).containsExactly(1, 2);

        InvestorDailyTrade sell = of(rows, "FOREIGN", "SELL").get(0);
        assertThat(sell.getStockCode()).isEqualTo("005930");
        assertThat(sell.getStockName()).isEqualTo("삼성전자");
        assertThat(sell.getNetBuyAmount()).isEqualByComparingTo("-2366");
        assertThat(sell.getSellAmount()).isEqualByComparingTo("2366");
        assertThat(sell.getBuyAmount()).isEqualByComparingTo("0");
        assertThat(sell.getDataBasis()).isEqualTo(InvestorDailyTrade.BASIS_FINAL);
        assertThat(sell.getMarketType()).isEqualTo("KOSPI");
        assertThat(sell.getCurrentPrice()).isEqualByComparingTo("219500");
        assertThat(sell.getTradeVolume()).isEqualTo(1000L);

        assertThat(of(rows, "INSTITUTION", "BUY")).extracting(InvestorDailyTrade::getStockCode).as("0 은 어느 쪽 순위도 아니다").containsExactly("005930");
        assertThat(of(rows, "PENSION", "BUY")).extracting(InvestorDailyTrade::getStockCode).as("모르는 값은 순위에 넣지 않는다").containsExactly("005930");
        assertThat(of(rows, "PENSION", "SELL")).extracting(InvestorDailyTrade::getStockCode).containsExactly("009150");
    }

    @Test
    @DisplayName("상위 N 개까지, 금액이 같으면 종목코드 순 — 돌릴 때마다 순위가 바뀌지 않게. 이름을 모르면 코드로")
    void topCutAndTies() {
        List<InvestorStockDaily> stocks = new ArrayList<>();
        for (int i = 0; i < 40; i++) stocks.add(stock(String.format("%06d", 40 - i), "100", null, null));

        List<InvestorDailyTrade> rows = InvestorFinalRows.rank(DAY, stocks, code -> null, 30);

        assertThat(rows).hasSize(30);
        assertThat(rows.get(0).getStockCode()).isEqualTo("000001");
        assertThat(rows.get(0).getStockName()).isEqualTo("000001");
        assertThat(rows.get(29).getRankNum()).isEqualTo(30);
    }
}
