package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KIS 지수 일봉 응답 파싱(FHPUP02120000) 순수 함수 테스트.
 * pykrx 지수 엔드포인트 KRX 포맷 변경 대체(2026-06-30) — KOSPI 일봉을 regime/sector 에 공급.
 * 오름차순 정렬/결측 graceful skip(4c 위장값 금지) 검증.
 */
class KoreaInvestmentIndexParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    @DisplayName("정상 output2 → 과거→최신 오름차순, 종가/시고저 파싱")
    void parse_sortsAscendingAndMapsFields() throws Exception {
        // KIS 는 최신순(내림차순)으로 내려줌 — 파서가 오름차순으로 뒤집어야 한다.
        JsonNode resp = json("{\"rt_cd\":\"0\",\"output2\":["
                + "{\"stck_bsop_date\":\"20260630\",\"bstp_nmix_prpr\":\"2650.50\",\"bstp_nmix_oprc\":\"2640.00\",\"bstp_nmix_hgpr\":\"2655.00\",\"bstp_nmix_lwpr\":\"2638.00\"},"
                + "{\"stck_bsop_date\":\"20260627\",\"bstp_nmix_prpr\":\"2630.00\",\"bstp_nmix_oprc\":\"2625.00\",\"bstp_nmix_hgpr\":\"2632.00\",\"bstp_nmix_lwpr\":\"2620.00\"}"
                + "]}");

        List<KoreaInvestmentService.IndexOhlcvData> rows = KoreaInvestmentService.parseIndexDaily(resp);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).date()).isEqualTo("2026-06-27");   // 오름차순: 과거 먼저
        assertThat(rows.get(1).date()).isEqualTo("2026-06-30");
        assertThat(rows.get(1).close()).isEqualByComparingTo("2650.50");
        assertThat(rows.get(1).open()).isEqualByComparingTo("2640.00");
        assertThat(rows.get(1).high()).isEqualByComparingTo("2655.00");
        assertThat(rows.get(1).low()).isEqualByComparingTo("2638.00");
    }

    @Test
    @DisplayName("종가/영업일자 결측/이상 행은 제외 (보정 안 함)")
    void parse_skipsMissingOrInvalidRows() throws Exception {
        JsonNode resp = json("{\"output2\":["
                + "{\"stck_bsop_date\":\"20260630\",\"bstp_nmix_prpr\":\"2650.50\"},"   // 정상(시고저 누락은 허용)
                + "{\"stck_bsop_date\":\"20260629\",\"bstp_nmix_prpr\":\"\"},"           // 종가 빈값 → 제외
                + "{\"stck_bsop_date\":\"20260628\",\"bstp_nmix_prpr\":\"0\"},"          // 종가 0 → 제외
                + "{\"stck_bsop_date\":\"2026\",\"bstp_nmix_prpr\":\"2600\"},"            // 영업일자 이상 → 제외
                + "{\"bstp_nmix_prpr\":\"2600\"}"                                          // 영업일자 없음 → 제외
                + "]}");

        List<KoreaInvestmentService.IndexOhlcvData> rows = KoreaInvestmentService.parseIndexDaily(resp);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).date()).isEqualTo("2026-06-30");
        assertThat(rows.get(0).open()).isNull();   // 시고저 누락은 null 허용
    }

    @Test
    @DisplayName("output2 없음 / null → 빈 리스트 (위장값 금지)")
    void parse_emptyOnMissing() throws Exception {
        assertThat(KoreaInvestmentService.parseIndexDaily(null)).isEmpty();
        assertThat(KoreaInvestmentService.parseIndexDaily(json("{\"rt_cd\":\"1\"}"))).isEmpty();
        assertThat(KoreaInvestmentService.parseIndexDaily(json("{\"output2\":{}}"))).isEmpty();
    }
}
