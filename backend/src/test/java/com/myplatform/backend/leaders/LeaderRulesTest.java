package com.myplatform.backend.leaders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주도주 목록 규칙(2026-11-19) — 시가총액 1조 원 이상 종목을 20거래일 평균 거래대금 순으로 세우고 20·60거래일 등락을 붙인다.
 *
 * <p>만든 까닭: 사용자가 원하는 것이 '저평가 종목'과 '국내 주도주(삼성전자·SK하이닉스 같은)'인데 주도주를 보여 주는 목록이 없었다.
 * 추천 점수·봇·시그널과는 무관한 표시 전용 목록이다.
 */
class LeaderRulesTest {

    private static final LocalDate BASIS = LocalDate.of(2026, 11, 18);

    /** 달력일을 하루씩 거슬러 n개 — 테스트에선 날짜가 연속이면 충분하다(거래일 달력은 서비스가 넘긴다). */
    private static List<LeaderRules.Bar> bars(int count, String close, String volume) {
        List<LeaderRules.Bar> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new LeaderRules.Bar(BASIS.minusDays(i), new BigDecimal(close), new BigDecimal(volume)));
        }
        return out;
    }

    private static void setClose(List<LeaderRules.Bar> bars, int daysBack, String close) {
        LeaderRules.Bar old = bars.get(daysBack);
        bars.set(daysBack, new LeaderRules.Bar(old.date(), new BigDecimal(close), old.volume()));
    }

    @Test
    @DisplayName("20·60거래일 등락은 달력이 정한 그 날짜의 종가와 견준다 — 평균 거래대금은 최근 20봉의 (종가×거래량) 평균(억 원)")
    void measuresReturnsAgainstCalendarDatesAndAveragesTradedValue() {
        List<LeaderRules.Bar> bars = bars(61, "100", "2000000");   // 100원 × 200만 주 = 2억 원
        setClose(bars, 20, "80");
        setClose(bars, 60, "125");

        LeaderRules.Metrics m = LeaderRules.measure(bars, BASIS, BASIS.minusDays(20), BASIS.minusDays(60));

        assertThat(m).isNotNull();
        assertThat(m.close()).isEqualByComparingTo("100");
        assertThat(m.return20()).isEqualByComparingTo("25.00");    // 100 ÷ 80 − 1
        assertThat(m.return60()).isEqualByComparingTo("-20.00");   // 100 ÷ 125 − 1
        assertThat(m.avgTradedValueEok()).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("기준일 봉이 없으면 재지 않는다 — 그날 거래 기록이 없는 종목을 앞 봉으로 대신 세우지 않는다")
    void noBarOnBasisDateMeansNoMetrics() {
        List<LeaderRules.Bar> bars = bars(61, "100", "2000000");
        bars.remove(0);   // 기준일 봉 없음

        assertThat(LeaderRules.measure(bars, BASIS, BASIS.minusDays(20), BASIS.minusDays(60))).isNull();
    }

    @Test
    @DisplayName("상장한 지 60거래일이 안 된 종목은 60거래일 등락을 모른다(null) — 가장 오래된 봉으로 대신하지 않는다")
    void shortHistoryLeavesLongReturnUnknown() {
        List<LeaderRules.Bar> bars = bars(30, "100", "2000000");
        setClose(bars, 20, "50");

        LeaderRules.Metrics m = LeaderRules.measure(bars, BASIS, BASIS.minusDays(20), BASIS.minusDays(60));

        assertThat(m.return20()).isEqualByComparingTo("100.00");
        assertThat(m.return60()).isNull();
    }

    @Test
    @DisplayName("기준일 뒤의 봉(아직 값이 굳지 않은 날)은 종가·평균 어디에도 쓰지 않는다")
    void barsAfterBasisAreIgnored() {
        List<LeaderRules.Bar> bars = bars(61, "100", "2000000");
        bars.add(0, new LeaderRules.Bar(BASIS.plusDays(1), new BigDecimal("999"), new BigDecimal("900000000")));

        LeaderRules.Metrics m = LeaderRules.measure(bars, BASIS, BASIS.minusDays(20), BASIS.minusDays(60));

        assertThat(m.close()).isEqualByComparingTo("100");
        assertThat(m.avgTradedValueEok()).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("쓸 수 있는 봉이 10개 미만이면 평균 거래대금을 모른다 — 순위에 넣지 않는다(매수 후보의 거래대금 하한과 같은 정의)")
    void tooFewBarsMeansUnknownAverage() {
        assertThat(LeaderRules.measure(bars(9, "100", "2000000"), BASIS, BASIS.minusDays(20), BASIS.minusDays(60))).isNull();
        assertThat(LeaderRules.measure(bars(10, "100", "2000000"), BASIS, BASIS.minusDays(20), BASIS.minusDays(60))).isNotNull();
    }

    @Test
    @DisplayName("견줄 종가가 0 이하이거나 없으면 등락을 만들지 않는다")
    void changePctNeedsPositiveBase() {
        assertThat(LeaderRules.changePct(new BigDecimal("100"), null)).isNull();
        assertThat(LeaderRules.changePct(new BigDecimal("100"), BigDecimal.ZERO)).isNull();
        assertThat(LeaderRules.changePct(null, new BigDecimal("100"))).isNull();
        assertThat(LeaderRules.changePct(new BigDecimal("190300"), new BigDecimal("211500"))).isEqualByComparingTo("-10.02");
    }

    @Test
    @DisplayName("대상 = 시가총액 1조 원 이상 · 상장법인 목록(마스터)의 시장이 KOSPI·KOSDAQ · 거래정지 아님 — 우선주·ETF 는 마스터에 시장이 없어 빠진다")
    void universeNeedsSizeMasterMarketAndActive() {
        assertThat(LeaderRules.inUniverse(new BigDecimal("10000"), "KOSPI", true)).isTrue();
        assertThat(LeaderRules.inUniverse(new BigDecimal("20000"), "KOSDAQ", true)).isTrue();
        assertThat(LeaderRules.inUniverse(new BigDecimal("9999.99"), "KOSPI", true)).isFalse();
        assertThat(LeaderRules.inUniverse(new BigDecimal("1267747"), null, true)).isFalse();     // 삼성전자우 — 마스터에 시장 없음
        assertThat(LeaderRules.inUniverse(new BigDecimal("1267747"), " ", true)).isFalse();
        assertThat(LeaderRules.inUniverse(new BigDecimal("1267747"), "ETF", true)).isFalse();
        assertThat(LeaderRules.inUniverse(new BigDecimal("50000"), "KOSDAQ", false)).isFalse();   // 거래정지
        assertThat(LeaderRules.inUniverse(null, "KOSPI", true)).isFalse();                        // 시가총액 모름
    }

    private static LeaderRules.Candidate candidate(String code, String cap, String avg) {
        return new LeaderRules.Candidate(code, code, "KOSPI", new BigDecimal(cap),
                new LeaderRules.Metrics(new BigDecimal("100"), new BigDecimal(avg), null, null));
    }

    @Test
    @DisplayName("순서 = 20거래일 평균 거래대금 큰 순 → 시가총액 큰 순 → 종목코드 순(입력 순서와 무관)")
    void ranksByAverageTradedValueThenCapThenCode() {
        List<LeaderRules.Candidate> input = new ArrayList<>(List.of(
                candidate("005930", "11000000", "37847"),
                candidate("000660", "8000000", "49484"),
                candidate("111111", "20000", "100"),
                candidate("222222", "30000", "100"),
                candidate("333333", "20000", "100")));

        assertThat(LeaderRules.rank(input)).extracting(LeaderRules.Candidate::stockCode)
                .containsExactly("000660", "005930", "222222", "111111", "333333");
        java.util.Collections.reverse(input);
        assertThat(LeaderRules.rank(input)).extracting(LeaderRules.Candidate::stockCode)
                .containsExactly("000660", "005930", "222222", "111111", "333333");
    }

    @Test
    @DisplayName("재지 못한 종목이 대상의 10% 를 넘거나 대상이 너무 적으면 그 회차 목록을 내지 않는다 — 일부로 만든 순위는 순위가 아니다")
    void publishesOnlyWhenMostOfTheUniverseWasMeasured() {
        assertThat(LeaderRules.publishable(286, 280)).isTrue();
        assertThat(LeaderRules.publishable(286, 258)).isTrue();      // 못 잰 종목 9.8%
        assertThat(LeaderRules.publishable(286, 257)).isFalse();     // 10.1%
        assertThat(LeaderRules.publishable(40, 40)).isFalse();       // 대상이 비정상적으로 적다(시가총액·마스터 입력 이상)
        assertThat(LeaderRules.publishable(286, 0)).isFalse();       // 잰 종목이 없다
    }

    private static com.myplatform.backend.entity.InvestorStockDaily net(String foreign, String institution, String pension) {
        return com.myplatform.backend.entity.InvestorStockDaily.builder()
                .foreignNet(foreign == null ? null : new BigDecimal(foreign))
                .institutionNet(institution == null ? null : new BigDecimal(institution))
                .pensionNet(pension == null ? null : new BigDecimal(pension)).build();
    }

    @Test
    @DisplayName("외국인+기관 순매수 합 — 기관 계에 연기금이 들어 있어 연기금을 또 더하지 않는다, 한쪽을 모르는 날은 세지 않는다")
    void netBuyAddsForeignAndInstitutionOnly() {
        LeaderRules.NetBuy n = LeaderRules.netBuy(List.of(
                net("100.5", "-30.25", "999"),     // 연기금 999 는 기관 −30.25 안에 이미 있다
                net("-20", "5", null),
                net(null, "77", null),             // 외국인을 모른다 — 그날 합은 모른다
                net("1", null, null)));

        assertThat(n.sumEok()).isEqualByComparingTo("55.25");
        assertThat(n.days()).isEqualTo(2);
    }

    @Test
    @DisplayName("더한 날이 하나도 없으면 0 이 아니라 모름(null)")
    void netBuyWithoutUsableDaysIsUnknown() {
        assertThat(LeaderRules.netBuy(List.of())).isNull();
        assertThat(LeaderRules.netBuy(List.of(net(null, "1", null)))).isNull();
        assertThat(LeaderRules.netBuy(null)).isNull();
    }
}
