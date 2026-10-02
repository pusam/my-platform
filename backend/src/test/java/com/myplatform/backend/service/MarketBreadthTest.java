package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.MarketDailyStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시장 폭·ADR — {@link MarketBreadth}(2026-10-02).
 *
 * <p>재현 대상: 네이버 시세 크롤이 죽은 뒤 실패가 0 으로 저장돼, 10/2 의 ADR(20일)이 "9/3~9/10 6일치"인데도
 * 85.0(정상 범위)으로 나왔다. 0 행은 합에 아무것도 안 더해서 겉보기엔 멀쩡했다.
 */
class MarketBreadthTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }

    private static MarketDailyStatus row(LocalDate d, Integer adv, Integer dec) {
        MarketDailyStatus r = new MarketDailyStatus();
        r.setMarketType("KOSPI");
        r.setTradeDate(d);
        r.setAdvancingCount(adv);
        r.setDecliningCount(dec);
        r.setUnchangedCount(adv == null ? null : 0);
        return r;
    }

    /** 2026-10-02 운영 모양: 최근 14행 0/0/0(9/11~10/2) + 그 앞 6행 정상(9/3~9/10) — 최신순. */
    private static List<MarketDailyStatus> productionShapeOct2() {
        List<MarketDailyStatus> rows = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 10, 2);
        for (int i = 0; i < 14; i++) rows.add(row(d.minusDays(i), 0, 0));
        int[][] real = {{991, 1302}, {1209, 1083}, {789, 1463}, {1100, 1200}, {1000, 1150}, {950, 1250}};
        for (int i = 0; i < real.length; i++) rows.add(row(LocalDate.of(2026, 9, 10).minusDays(i), real[i][0], real[i][1]));
        return rows;
    }

    @Test
    @DisplayName("재현: 0 행이 섞인 창에서 예전 방식은 그럴듯한 ADR 을 냈다 — 이제는 유효 6일이라 판단 보류(null)")
    void zeroRowsNoLongerProduceAPlausibleAdr() {
        MarketBreadth.Adr adr = MarketBreadth.adr(productionShapeOct2());

        assertThat(adr.value()).isNull();
        assertThat(adr.validDays()).isEqualTo(6);
        assertThat(adr.windowDays()).isEqualTo(20);
    }

    @Test
    @DisplayName("유효일이 15일 이상이면 그 날들만 합산한다 — 0 행은 분자·분모 어디에도 안 들어간다")
    void computesFromValidDaysOnly() {
        List<MarketDailyStatus> rows = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 11, 20);
        for (int i = 0; i < 16; i++) rows.add(row(d.minusDays(i), 1000, 800));   // 유효 16일
        for (int i = 16; i < 20; i++) rows.add(row(d.minusDays(i), 0, 0));       // 0 행 4일

        MarketBreadth.Adr adr = MarketBreadth.adr(rows);

        assertThat(adr.validDays()).isEqualTo(16);
        assertThat(adr.value()).isEqualByComparingTo("125.00");   // 16000 / 12800
    }

    @Test
    @DisplayName("창은 최근 20행 — 그보다 오래된 행은 쓰지 않는다")
    void windowIsTheLatestTwentyRows() {
        List<MarketDailyStatus> rows = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 11, 20);
        for (int i = 0; i < 20; i++) rows.add(row(d.minusDays(i), 900, 1000));
        rows.add(row(d.minusDays(20), 100000, 1));   // 21번째 — 들어가면 값이 확 뛴다

        assertThat(MarketBreadth.adr(rows).value()).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("null·0/0 행은 '모름' — hasCounts false")
    void hasCountsTreatsZeroAndNullAsUnknown() {
        assertThat(MarketBreadth.hasCounts(row(LocalDate.now(), 0, 0))).isFalse();
        assertThat(MarketBreadth.hasCounts(row(LocalDate.now(), null, null))).isFalse();
        assertThat(MarketBreadth.hasCounts(row(LocalDate.now(), 0, 5))).isTrue();
        assertThat(MarketBreadth.hasCounts(null)).isFalse();
        assertThat(MarketBreadth.adr(null).value()).isNull();
    }

    @Test
    @DisplayName("KIS 국내업종 현재지수 응답 — 공식 샘플 필드명(ascn/down/stnr/uplm/lslm_issu_cnt)으로 읽는다")
    void parsesKisIndexPrice() throws Exception {
        MarketBreadth.Counts c = MarketBreadth.fromKisIndexPrice(json("""
                {"rt_cd":"0","msg1":"정상처리 되었습니다.","output":{"bstp_nmix_prpr":"6985.62",
                 "ascn_issu_cnt":"528","down_issu_cnt":"1,302","stnr_issu_cnt":"74","uplm_issu_cnt":"3","lslm_issu_cnt":""}}
                """));

        assertThat(c).isNotNull();
        assertThat(c.advancing()).isEqualTo(528);
        assertThat(c.declining()).isEqualTo(1302);
        assertThat(c.unchanged()).isEqualTo(74);
        assertThat(c.upperLimit()).isEqualTo(3);
        assertThat(c.lowerLimit()).isNull();
        assertThat(c.total()).isEqualTo(1904);
    }

    @Test
    @DisplayName("실패·필드 결측·숫자 아님·전부 0 은 null — 0 으로 저장하지 않는다(§4c)")
    void failuresAreNullNotZero() throws Exception {
        assertThat(MarketBreadth.fromKisIndexPrice(null)).isNull();
        assertThat(MarketBreadth.fromKisIndexPrice(json("{\"rt_cd\":\"1\",\"msg1\":\"INPUT FIELD NOT FOUND\"}"))).isNull();
        assertThat(MarketBreadth.fromKisIndexPrice(json("{\"rt_cd\":\"0\",\"output\":{\"ascn_issu_cnt\":\"10\"}}"))).isNull();
        assertThat(MarketBreadth.fromKisIndexPrice(json(
                "{\"rt_cd\":\"0\",\"output\":{\"ascn_issu_cnt\":\"x\",\"down_issu_cnt\":\"1\",\"stnr_issu_cnt\":\"1\"}}"))).isNull();
        assertThat(MarketBreadth.fromKisIndexPrice(json(
                "{\"rt_cd\":\"0\",\"output\":{\"ascn_issu_cnt\":\"0\",\"down_issu_cnt\":\"0\",\"stnr_issu_cnt\":\"0\"}}"))).isNull();
    }

    @Test
    @DisplayName("output 이 배열이어도 첫 원소를 읽는다")
    void acceptsArrayOutput() throws Exception {
        assertThat(MarketBreadth.fromKisIndexPrice(json(
                "{\"rt_cd\":\"0\",\"output\":[{\"ascn_issu_cnt\":\"5\",\"down_issu_cnt\":\"6\",\"stnr_issu_cnt\":\"7\"}]}"))
                .advancing()).isEqualTo(5);
    }

    @Test
    @DisplayName("등락 수는 15:40 부터 그날 확정치 — 그 전엔 장중 잠정치")
    void countsSettleAfterClose() {
        assertThat(MarketBreadth.countsSettled(LocalTime.of(15, 39, 59))).isFalse();
        assertThat(MarketBreadth.countsSettled(LocalTime.of(15, 40))).isTrue();
        assertThat(MarketBreadth.countsSettled(LocalTime.of(16, 30, 20))).isTrue();
        assertThat(MarketBreadth.countsSettled(null)).isFalse();
    }

    @Test
    @DisplayName("판단 보류 문구는 시장별 유효일과 필요 일수를 말한다")
    void insufficientMessageSaysWhy() {
        assertThat(MarketBreadth.insufficientMessage(6, 6))
                .contains("최근 20거래일 중 코스피 6일·코스닥 6일")
                .contains("ADR 판단을 보류합니다(15일 필요)");
        assertThat(MarketBreadth.insufficientMessage(null, 3)).contains("코스피 ?일");
    }
}
