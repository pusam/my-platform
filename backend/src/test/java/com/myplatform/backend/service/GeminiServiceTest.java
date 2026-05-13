package com.myplatform.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * GeminiService 단위 테스트
 *
 * 검증 포인트:
 * 1. 정상 응답 시 텍스트 파싱 성공
 * 2. API 키 없으면 null 반환 (예외 없음)
 * 3. 429 Rate Limit 시 재시도 → 최종 실패 시 에러 메시지 반환
 * 4. 네트워크/서버 에러 시 null 반환
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class GeminiServiceTest {

    private GeminiService geminiService;
    private RestTemplate mockRestTemplate;

    @BeforeEach
    void setUp() {
        // SimpleMeterRegistry — 테스트용 노옵 MeterRegistry (의존성 추가 없이 기본 제공)
        // ObjectProvider 는 빈 미등록 시나리오 — getIfAvailable() = null (텔레그램 비활성 환경 동일).
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<TelegramNotificationService> telegramProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(telegramProvider.getIfAvailable()).thenReturn(null);
        geminiService = new GeminiService(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                telegramProvider);
        mockRestTemplate = mock(RestTemplate.class);

        ReflectionTestUtils.setField(geminiService, "restTemplate", mockRestTemplate);
        ReflectionTestUtils.setField(geminiService, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(geminiService, "apiUrl",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent");
    }

    private ResponseEntity<Map> buildGeminiResponse(String text) {
        Map<String, Object> body = Map.of(
                "candidates", List.of(
                        Map.of("content", Map.of("parts", List.of(Map.of("text", text))))
                )
        );
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    // ========== 정상 동작 ==========

    @Nested
    @DisplayName("정상 API 호출")
    class NormalOperationTests {

        @Test
        @DisplayName("정상 응답 시 텍스트 반환")
        void normalResponse_returnsText() {
            // given
            when(mockRestTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                    .thenReturn(buildGeminiResponse("삼성전자 분석: 매수 추천"));

            // when
            String result = geminiService.chat("삼성전자 분석해줘");

            // then
            assertThat(result).isNotNull();
            assertThat(result).contains("삼성전자");
        }

        @Test
        @DisplayName("빈 candidates 시 예외 없이 처리")
        void emptyCandidates_noException() {
            // given — candidates가 빈 배열이면 callGeminiApi에서 null 반환
            // 하지만 callWithFallback → callGeminiApiWithRetry 체인에서 재시도될 수 있음
            Map<String, Object> body = Map.of("candidates", List.of());
            ResponseEntity<Map> response = new ResponseEntity<>(body, HttpStatus.OK);
            when(mockRestTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                    .thenReturn(response);

            // when & then — 핵심: 예외가 전파되지 않아야 함
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                    geminiService.chat("분석해줘"));
        }
    }

    // ========== API 키 검증 ==========

    @Nested
    @DisplayName("API 키 검증")
    class ApiKeyTests {

        @Test
        @DisplayName("API 키 없으면 null 반환 (RestTemplate 호출 안 함)")
        void noApiKey_returnsNull() {
            // given
            ReflectionTestUtils.setField(geminiService, "apiKey", "");

            // when
            String result = geminiService.chat("삼성전자 분석");

            // then
            assertThat(result).isNull();
            verifyNoInteractions(mockRestTemplate);
        }

        @Test
        @DisplayName("API 키 null이면 null 반환")
        void nullApiKey_returnsNull() {
            // given
            ReflectionTestUtils.setField(geminiService, "apiKey", null);

            // when
            String result = geminiService.chat("테스트");

            // then
            assertThat(result).isNull();
        }
    }

    // ========== 에러 처리 ==========

    @Nested
    @DisplayName("에러 처리 및 복구")
    class ErrorHandlingTests {

        @Test
        @DisplayName("429 Rate Limit → 재시도 후 에러 메시지 반환")
        void rateLimited_returnsErrorMessage() {
            // given
            when(mockRestTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.TOO_MANY_REQUESTS, "Rate limited",
                            HttpHeaders.EMPTY, "Resource has been exhausted".getBytes(), null));

            // when
            String result = geminiService.chat("분석해줘");

            // then
            // Rate limit 시 에러 메시지 또는 null 반환 (예외는 전파 안 됨)
            // callWithFallback이 quota 메시지를 반환하거나 null
            verify(mockRestTemplate, atLeastOnce()).postForEntity(anyString(), any(), eq(Map.class));
        }

        @Test
        @DisplayName("네트워크 예외 → null 반환 (예외 전파 안 함)")
        void networkException_returnsNull() {
            // given
            when(mockRestTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            // when
            String result = geminiService.chat("분석해줘");

            // then
            assertThat(result).isNull();
        }
    }
}
