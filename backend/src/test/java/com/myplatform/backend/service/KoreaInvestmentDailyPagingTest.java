package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 일봉 페이지네이션(차트 200봉+MA120 깊은 히스토리) 병합 순수 함수 — dedupeSortRowsDesc.
 * 페이지 경계 날짜 중복 dedup + newest→oldest 정렬 규약을 고정(fetchChartData 가 이 순서를 전제).
 */
class KoreaInvestmentDailyPagingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode row(String yyyymmdd) throws Exception {
        return MAPPER.readTree("{\"stck_bsop_date\":\"" + yyyymmdd + "\",\"stck_clpr\":\"1000\"}");
    }

    @Test
    @DisplayName("페이지 경계 날짜 dedup + newest→oldest 정렬")
    void dedupeSortRowsDesc_mergesAndSorts() throws Exception {
        // 페이지1(최근): 0715, 0714 / 페이지2(과거, 경계 0714 중복): 0714, 0713
        List<JsonNode> merged = new ArrayList<>(List.of(
                row("20260715"), row("20260714"), row("20260714"), row("20260713")));
        List<JsonNode> out = KoreaInvestmentService.dedupeSortRowsDesc(merged);

        assertThat(out).hasSize(3);   // 0714 중복 제거
        assertThat(out.stream().map(r -> r.path("stck_bsop_date").asText()).toList())
                .containsExactly("20260715", "20260714", "20260713");   // newest→oldest
    }

    @Test
    @DisplayName("날짜 결측/형식오류 행 제외, null/빈 입력 방어")
    void dedupeSortRowsDesc_guards() throws Exception {
        List<JsonNode> mixed = new ArrayList<>(List.of(
                row("20260715"), MAPPER.readTree("{\"stck_clpr\":\"1\"}"), row("badformat")));
        assertThat(KoreaInvestmentService.dedupeSortRowsDesc(mixed))
                .extracting(r -> r.path("stck_bsop_date").asText())
                .containsExactly("20260715");
        assertThat(KoreaInvestmentService.dedupeSortRowsDesc(null)).isEmpty();
        assertThat(KoreaInvestmentService.dedupeSortRowsDesc(new ArrayList<>())).isEmpty();
    }
}
