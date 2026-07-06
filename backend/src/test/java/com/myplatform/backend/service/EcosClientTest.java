package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ECOS StatisticSearch 파서 + bp 변환(P3-7) 순수 함수 테스트.
 * 특히 INFO-200(HTTP 200 + RESULT body = 데이터 없음)의 무음 처리와 %→bp ×100 을 가드
 * — 키 발급 전 매일 ERROR 스팸/단위 무음 버그가 이 기능의 최대 함정.
 */
class EcosClientTest {

    private final ObjectMapper om = new ObjectMapper();

    private com.fasterxml.jackson.databind.JsonNode json(String s) throws Exception {
        return om.readTree(s);
    }

    @Test
    @DisplayName("정상 응답: row 파싱 + yyyyMMdd→ISO + 과거→최신 정렬")
    void parseNormal() throws Exception {
        String body = """
                {"StatisticSearch":{"list_total_count":2,"row":[
                  {"TIME":"20260703","DATA_VALUE":"2.855"},
                  {"TIME":"20260702","DATA_VALUE":"2.840"}
                ]}}""";
        List<EcosClient.EcosPoint> pts = EcosClient.parseStatisticSearch(json(body));
        assertThat(pts).hasSize(2);
        assertThat(pts.get(0).date()).isEqualTo("2026-07-02");   // 오름차순 정렬
        assertThat(pts.get(1).value()).isEqualTo(2.855);
    }

    @Test
    @DisplayName("INFO-200(데이터 없음)·INFO-100(키 오류) = HTTP 200 + RESULT body → 조용히 빈 리스트")
    void parseResultBodySilent() throws Exception {
        String info200 = """
                {"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}""";
        assertThat(EcosClient.parseStatisticSearch(json(info200))).isEmpty();
        String info100 = """
                {"RESULT":{"CODE":"INFO-100","MESSAGE":"인증키가 유효하지 않습니다."}}""";
        assertThat(EcosClient.parseStatisticSearch(json(info100))).isEmpty();
    }

    @Test
    @DisplayName("null/형식 불량/값 파싱 불가 행 → 빈 리스트 또는 skip (throw 금지, 보정 금지)")
    void parseMalformed() throws Exception {
        assertThat(EcosClient.parseStatisticSearch(null)).isEmpty();
        assertThat(EcosClient.parseStatisticSearch(json("{\"foo\":1}"))).isEmpty();
        String partial = """
                {"StatisticSearch":{"row":[
                  {"TIME":"20260703","DATA_VALUE":"abc"},
                  {"TIME":"2026","DATA_VALUE":"2.8"},
                  {"TIME":"20260702","DATA_VALUE":"2.840"}
                ]}}""";
        List<EcosClient.EcosPoint> pts = EcosClient.parseStatisticSearch(json(partial));
        assertThat(pts).hasSize(1);   // 불량 2행 skip, 유효 1행만
        assertThat(pts.get(0).value()).isEqualTo(2.840);
    }

    @Test
    @DisplayName("trendBp: %→bp ×100 (2.855→2.705 = -15.0bp) — 단위 무음 버그 가드")
    void trendBpConversion() {
        List<EcosClient.EcosPoint> series = new java.util.ArrayList<>();
        series.add(new EcosClient.EcosPoint("2026-06-01", 2.855));
        for (int i = 2; i <= 20; i++) {
            series.add(new EcosClient.EcosPoint(String.format("2026-06-%02d", i), 2.8));
        }
        series.add(new EcosClient.EcosPoint("2026-07-03", 2.705));   // 21점 — lookback 20 이 첫 점을 봄
        Double bp = EcosClient.trendBp(series, 20);
        assertThat(bp).isCloseTo(-15.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("trendBp: 시계열 lookback+1 미만 → null (미수집, §4c)")
    void trendBpInsufficient() {
        List<EcosClient.EcosPoint> series = List.of(
                new EcosClient.EcosPoint("2026-07-02", 2.84),
                new EcosClient.EcosPoint("2026-07-03", 2.85));
        assertThat(EcosClient.trendBp(series, 20)).isNull();
        assertThat(EcosClient.trendBp(null, 20)).isNull();
    }
}
