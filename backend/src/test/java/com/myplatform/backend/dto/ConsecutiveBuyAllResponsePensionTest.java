package com.myplatform.backend.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 연속 매수 전체 응답에 연기금 — 2026-10-03.
 *
 * <p>재현: 서비스는 연기금(PENSION) 연속 매수를 계산하는데 응답 DTO 에 칸이 없어 버려졌다 — 화면 '연기금' 탭은
 * {@code data.PENSION || []} 로 늘 비어 "연속 매수 종목 없음"처럼 보였다.
 */
class ConsecutiveBuyAllResponsePensionTest {

    @Test
    @DisplayName("재현: PENSION 키로 직렬화된다")
    void pensionIsSerialized() throws Exception {
        ConsecutiveBuyDto p = new ConsecutiveBuyDto();
        p.setStockCode("005930");
        ConsecutiveBuyAllResponse r = ConsecutiveBuyAllResponse.builder()
                .foreign(List.of()).institution(List.of()).pension(List.of(p)).build();

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(r);

        assertThat(json).contains("\"PENSION\"").contains("005930");
    }
}
