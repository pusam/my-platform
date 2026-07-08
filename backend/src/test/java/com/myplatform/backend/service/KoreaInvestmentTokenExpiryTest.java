package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KIS 토큰 만료시각을 응답의 expires_in 으로 설정(24h 하드코딩 제거).
 * KisApiService/MarketIndicatorService 와 동일 패턴 통일. 결측/비정상은 폴백 신호(-1)로
 * 넘겨 호출부가 보수적 24h 를 쓰게 한다(§4c: 결측을 그럴듯한 값으로 위장하지 않음).
 */
class KoreaInvestmentTokenExpiryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String body) throws Exception {
        return mapper.readTree(body);
    }

    @Test
    @DisplayName("정상 expires_in(숫자) → 그 값(초) 반환")
    void normal_numeric() throws Exception {
        assertThat(KoreaInvestmentService.parseExpiresInSeconds(
                json("{\"access_token\":\"t\",\"expires_in\":86400}")))
                .isEqualTo(86400L);
    }

    @Test
    @DisplayName("숫자 문자열 expires_in → 파싱 반환 (KIS 응답 변형 대비)")
    void numeric_string() throws Exception {
        assertThat(KoreaInvestmentService.parseExpiresInSeconds(
                json("{\"expires_in\":\"43200\"}")))
                .isEqualTo(43200L);
    }

    @Test
    @DisplayName("expires_in 필드 결측 → -1 (폴백 신호)")
    void missing() throws Exception {
        assertThat(KoreaInvestmentService.parseExpiresInSeconds(
                json("{\"access_token\":\"t\"}")))
                .isEqualTo(-1L);
    }

    @Test
    @DisplayName("expires_in null → -1 (폴백 신호)")
    void nullValue() throws Exception {
        assertThat(KoreaInvestmentService.parseExpiresInSeconds(
                json("{\"expires_in\":null}")))
                .isEqualTo(-1L);
    }

    @Test
    @DisplayName("expires_in 0 이하(0/음수) → -1 (비정상, 폴백)")
    void nonPositive() throws Exception {
        assertThat(KoreaInvestmentService.parseExpiresInSeconds(json("{\"expires_in\":0}")))
                .isEqualTo(-1L);
        assertThat(KoreaInvestmentService.parseExpiresInSeconds(json("{\"expires_in\":-5}")))
                .isEqualTo(-1L);
    }

    @Test
    @DisplayName("비숫자 문자열 expires_in → -1 (폴백)")
    void nonNumericString() throws Exception {
        assertThat(KoreaInvestmentService.parseExpiresInSeconds(
                json("{\"expires_in\":\"abc\"}")))
                .isEqualTo(-1L);
    }

    @Test
    @DisplayName("root null → -1 (폴백)")
    void nullRoot() {
        assertThat(KoreaInvestmentService.parseExpiresInSeconds(null)).isEqualTo(-1L);
    }
}
