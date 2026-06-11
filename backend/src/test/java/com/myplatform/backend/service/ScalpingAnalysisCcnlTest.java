package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 체결강도(tday_rltv) 파싱 — 체결 API(FHKST01010300) 응답 순수 함수 테스트.
 *
 * 배경: 현재가 시세 API(FHKST01010100) 응답에는 체결강도 필드가 없어 항상 null
 * → 폴백 100% 고정 표시되던 버그. 체결 API output 배열(최신순)의 tday_rltv 가 정답 소스.
 */
class ScalpingAnalysisCcnlTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    @DisplayName("output 배열 첫 항목의 tday_rltv 사용 (최신 체결)")
    void parse_firstTick() throws Exception {
        JsonNode resp = json("""
            {"rt_cd":"0","output":[
              {"stck_cntg_hour":"153059","tday_rltv":"135.42"},
              {"stck_cntg_hour":"153058","tday_rltv":"135.40"}
            ]}""");

        BigDecimal power = ScalpingAnalysisService.parseCcnlVolumePower(resp);

        assertThat(power).isEqualByComparingTo("135.42");
    }

    @Test
    @DisplayName("첫 항목 tday_rltv 빈값/0 이면 다음 유효값으로 폴백")
    void parse_skipsEmptyAndZero() throws Exception {
        JsonNode resp = json("""
            {"rt_cd":"0","output":[
              {"stck_cntg_hour":"153059","tday_rltv":""},
              {"stck_cntg_hour":"153058","tday_rltv":"0"},
              {"stck_cntg_hour":"153057","tday_rltv":"87.13"}
            ]}""");

        BigDecimal power = ScalpingAnalysisService.parseCcnlVolumePower(resp);

        assertThat(power).isEqualByComparingTo("87.13");
    }

    @Test
    @DisplayName("output 없음 / 배열 아님 / 전부 무효 / null → null (100 위장 금지)")
    void parse_invalidShapes() throws Exception {
        assertThat(ScalpingAnalysisService.parseCcnlVolumePower(null)).isNull();
        assertThat(ScalpingAnalysisService.parseCcnlVolumePower(json("{\"rt_cd\":\"0\"}"))).isNull();
        assertThat(ScalpingAnalysisService.parseCcnlVolumePower(
                json("{\"rt_cd\":\"0\",\"output\":{}}"))).isNull();
        assertThat(ScalpingAnalysisService.parseCcnlVolumePower(
                json("{\"rt_cd\":\"0\",\"output\":[{\"tday_rltv\":\"abc\"},{\"tday_rltv\":\"\"}]}"))).isNull();
    }
}
