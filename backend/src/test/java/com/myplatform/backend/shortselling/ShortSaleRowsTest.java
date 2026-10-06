package com.myplatform.backend.shortselling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KIS 공매도 응답 파싱 — 순수 함수(2026-10-02).
 *
 * <p>필드명은 공식 샘플 COLUMN_MAPPING 그대로다(chk_short_sale.py · chk_daily_short_sale.py). 두 API 모두
 * <b>거래 비중</b>(그날 거래량 중 공매도 몫)이지 잔고가 아니다. 빈값·'-'·숫자 아님은 null(모름) — 실측 0 과 구분한다(§4c).
 */
class ShortSaleRowsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("공매도 상위종목[국내주식-133]")
    class Ranking {

        private final JsonNode output = json("["
                + "{'mksc_shrn_iscd':'005930','hts_kor_isnm':'삼성전자','stck_prpr':'71500','prdy_vrss':'500',"
                + "'prdy_vrss_sign':'2','prdy_ctrt':'0.70','acml_vol':'12000000','acml_tr_pbmn':'858000000000',"
                + "'ssts_cntg_qty':'960000','ssts_vol_rlim':'8.00','ssts_tr_pbmn':'68640000000',"
                + "'ssts_tr_pbmn_rlim':'8.00','stnd_date1':'20261001','stnd_date2':'20261001','avrg_prc':'71500'},"
                + "{'mksc_shrn_iscd':'000660','hts_kor_isnm':'SK하이닉스','stck_prpr':'180000','prdy_ctrt':'-1.10',"
                + "'acml_vol':'3000000','acml_tr_pbmn':'540000000000','ssts_cntg_qty':'0','ssts_vol_rlim':'0.00',"
                + "'ssts_tr_pbmn':'0','ssts_tr_pbmn_rlim':'0.00','stnd_date1':'20261001','stnd_date2':'20261001',"
                + "'avrg_prc':''}"
                + "]");

        @Test
        @DisplayName("공식 필드를 그대로 옮긴다 — 순위는 응답 순서(앞 페이지 개수만큼 밀린다)")
        void mapsOfficialFields() {
            List<ShortSaleRows.RankingRow> rows = ShortSaleRows.parseRanking(output, 30);

            assertThat(rows).hasSize(2);
            ShortSaleRows.RankingRow first = rows.get(0);
            assertThat(first.stockCode()).isEqualTo("005930");
            assertThat(first.stockName()).isEqualTo("삼성전자");
            assertThat(first.tradeDate()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(first.rank()).isEqualTo(31);
            assertThat(first.shortVolume()).isEqualTo(960_000L);
            assertThat(first.shortVolumeShare()).isEqualByComparingTo("8.00");
            assertThat(first.shortAmount()).isEqualByComparingTo("68640000000");
            assertThat(first.shortAmountShare()).isEqualByComparingTo("8.00");
            assertThat(first.totalVolume()).isEqualTo(12_000_000L);
            assertThat(first.totalAmount()).isEqualByComparingTo("858000000000");
            assertThat(first.price()).isEqualByComparingTo("71500");
            assertThat(first.changeRate()).isEqualByComparingTo("0.70");
            assertThat(first.avgPrice()).isEqualByComparingTo("71500");
            assertThat(rows.get(1).rank()).isEqualTo(32);
        }

        @Test
        @DisplayName("실측 0 은 0, 빈값은 null — 결측을 0 으로 위장하지 않는다")
        void zeroIsZeroBlankIsNull() {
            ShortSaleRows.RankingRow second = ShortSaleRows.parseRanking(output, 0).get(1);

            assertThat(second.shortVolume()).isZero();
            assertThat(second.shortVolumeShare()).isEqualByComparingTo("0");
            assertThat(second.avgPrice()).isNull();
        }

        @Test
        @DisplayName("기준일은 응답의 stnd_date2(없으면 stnd_date1) — 수집한 날로 찍지 않는다")
        void tradeDateComesFromTheResponse() {
            JsonNode onlyFirst = json("[{'mksc_shrn_iscd':'005930','hts_kor_isnm':'삼성전자',"
                    + "'ssts_vol_rlim':'5.5','stnd_date1':'20260930','stnd_date2':''}]");

            assertThat(ShortSaleRows.parseRanking(onlyFirst, 0).get(0).tradeDate())
                    .isEqualTo(LocalDate.of(2026, 9, 30));
        }

        @Test
        @DisplayName("재현: 기준일이 첫 행에만 있는 응답 — 나머지 행은 같은 응답의 기준일로 놓는다(10/6 실측 30건 중 1건만 저장)")
        void dateOnlyOnFirstRowAppliesToTheResponse() {
            // 10/2·10/6 18:30 수집 모두 KIS 가 30행을 줬는데 첫 행만 stnd_date1/2 를 갖고 있어 29행을 버렸다.
            JsonNode response = json("["
                    + "{'mksc_shrn_iscd':'018880','hts_kor_isnm':'한온시스템','ssts_vol_rlim':'3.59','stnd_date1':'20261002','stnd_date2':'20261002'},"
                    + "{'mksc_shrn_iscd':'005930','hts_kor_isnm':'삼성전자','ssts_vol_rlim':'2.10','stnd_date1':'','stnd_date2':''},"
                    + "{'mksc_shrn_iscd':'000660','hts_kor_isnm':'SK하이닉스','ssts_vol_rlim':'1.80'}"
                    + "]");

            ShortSaleRows.RankingParse parsed = ShortSaleRows.parseRankingDetailed(response, 0);

            assertThat(parsed.rows()).extracting(ShortSaleRows.RankingRow::stockCode).containsExactly("018880", "005930", "000660");
            assertThat(parsed.rows()).extracting(ShortSaleRows.RankingRow::tradeDate).containsOnly(LocalDate.of(2026, 10, 2));
            assertThat(parsed.rows()).extracting(ShortSaleRows.RankingRow::rank).containsExactly(1, 2, 3);
            assertThat(parsed.dateFromResponse()).isEqualTo(2);
            assertThat(parsed.missingCode()).isZero();
            assertThat(parsed.missingDate()).isZero();
        }

        @Test
        @DisplayName("종목코드가 없는 행은 버리고 센다 — 어디에 놓을지 모르는 값은 저장하지 않는다")
        void rowsWithoutCodeAreDroppedAndCounted() {
            JsonNode broken = json("[{'mksc_shrn_iscd':'','ssts_vol_rlim':'3.0','stnd_date2':'20261001'},"
                    + "{'mksc_shrn_iscd':'035720','ssts_vol_rlim':'3.0','stnd_date2':'20261001'}]");

            ShortSaleRows.RankingParse parsed = ShortSaleRows.parseRankingDetailed(broken, 0);

            assertThat(parsed.rows()).extracting(ShortSaleRows.RankingRow::stockCode).containsExactly("035720");
            assertThat(parsed.rows().get(0).rank()).isEqualTo(2);   // 순위는 응답 위치 그대로
            assertThat(parsed.missingCode()).isEqualTo(1);
            assertThat(parsed.firstDroppedFields()).contains("mksc_shrn_iscd", "ssts_vol_rlim", "stnd_date2");
        }

        @Test
        @DisplayName("응답 어디에도 기준일이 없거나 서로 다른 기준일이 섞이면 기준일 없는 행은 버린다 — 날짜를 짐작하지 않는다")
        void noSingleResponseDateMeansDrop() {
            JsonNode noDate = json("[{'mksc_shrn_iscd':'035720','ssts_vol_rlim':'3.0','stnd_date1':'','stnd_date2':'-'}]");
            ShortSaleRows.RankingParse none = ShortSaleRows.parseRankingDetailed(noDate, 0);
            assertThat(none.rows()).isEmpty();
            assertThat(none.missingDate()).isEqualTo(1);

            JsonNode mixed = json("[{'mksc_shrn_iscd':'005930','stnd_date2':'20261001'},"
                    + "{'mksc_shrn_iscd':'000660','stnd_date2':'20261002'},"
                    + "{'mksc_shrn_iscd':'035720'}]");
            ShortSaleRows.RankingParse conflict = ShortSaleRows.parseRankingDetailed(mixed, 0);
            assertThat(conflict.rows()).extracting(ShortSaleRows.RankingRow::stockCode).containsExactly("005930", "000660");
            assertThat(conflict.missingDate()).isEqualTo(1);
            assertThat(conflict.dateFromResponse()).isZero();
        }

        @Test
        @DisplayName("output 이 없거나 배열이 아니면 빈 목록")
        void missingOutputIsEmpty() {
            assertThat(ShortSaleRows.parseRanking(null, 0)).isEmpty();
            assertThat(ShortSaleRows.parseRanking(json("{}"), 0)).isEmpty();
        }
    }

    @Nested
    @DisplayName("공매도 일별추이[국내주식-134]")
    class Daily {

        private final JsonNode output2 = json("["
                + "{'stck_bsop_date':'20261002','stck_clpr':'72000','ssts_cntg_qty':'100','ssts_vol_rlim':'1.10',"
                + "'ssts_tr_pbmn':'7200000','ssts_tr_pbmn_rlim':'1.05'},"
                + "{'stck_bsop_date':'20261001','stck_clpr':'71500','ssts_cntg_qty':'960000','ssts_vol_rlim':'8.00',"
                + "'ssts_tr_pbmn':'68640000000','ssts_tr_pbmn_rlim':'8.00'},"
                + "{'stck_bsop_date':'20260930','stck_clpr':'71000','ssts_cntg_qty':'','ssts_vol_rlim':'',"
                + "'ssts_tr_pbmn':'','ssts_tr_pbmn_rlim':''}"
                + "]");

        @Test
        @DisplayName("행마다 영업일자·공매도 수량·거래량 비중·거래대금(+비중)·종가")
        void mapsOfficialFields() {
            List<ShortSaleRows.DailyRow> rows = ShortSaleRows.parseDaily(output2);

            assertThat(rows).hasSize(3);
            ShortSaleRows.DailyRow oct1 = rows.get(1);
            assertThat(oct1.date()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(oct1.shortVolume()).isEqualTo(960_000L);
            assertThat(oct1.shortVolumeShare()).isEqualByComparingTo("8.00");
            assertThat(oct1.shortAmount()).isEqualByComparingTo("68640000000");
            assertThat(oct1.shortAmountShare()).isEqualByComparingTo("8.00");
            assertThat(oct1.closePrice()).isEqualByComparingTo("71500");
            assertThat(rows.get(2).shortVolumeShare()).as("빈값은 null").isNull();
        }

        @Test
        @DisplayName("기준일 이하에서 가장 최근 행 — 장중 '오늘' 형성 행을 마감값처럼 쓰지 않는다")
        void latestOnOrBeforeSkipsTodaysFormingRow() {
            List<ShortSaleRows.DailyRow> rows = ShortSaleRows.parseDaily(output2);

            assertThat(ShortSaleRows.latestOnOrBefore(rows, LocalDate.of(2026, 10, 1)))
                    .get().extracting(ShortSaleRows.DailyRow::date).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(ShortSaleRows.latestOnOrBefore(rows, LocalDate.of(2026, 10, 2)))
                    .get().extracting(ShortSaleRows.DailyRow::date).isEqualTo(LocalDate.of(2026, 10, 2));
            assertThat(ShortSaleRows.latestOnOrBefore(rows, LocalDate.of(2026, 9, 29))).isEmpty();
        }

        @Test
        @DisplayName("비중이 비어 있는 날은 '최근 값'으로 고르지 않는다 — 값 없는 행을 0% 로 보여주지 않게")
        void blankShareRowIsNotPicked() {
            List<ShortSaleRows.DailyRow> rows = ShortSaleRows.parseDaily(json("["
                    + "{'stck_bsop_date':'20261001','ssts_vol_rlim':''},"
                    + "{'stck_bsop_date':'20260930','ssts_vol_rlim':'2.50'}]"));

            assertThat(ShortSaleRows.latestOnOrBefore(rows, LocalDate.of(2026, 10, 1)))
                    .get().extracting(ShortSaleRows.DailyRow::date).isEqualTo(LocalDate.of(2026, 9, 30));
        }
    }
}
