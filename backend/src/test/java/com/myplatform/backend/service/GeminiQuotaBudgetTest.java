package com.myplatform.backend.service;

import com.myplatform.backend.entity.AiStrategySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Gemini 무료 하루 20회를 '재료 분류 먼저'로 나눠 쓴다(2026-10-06 확정 · 10/7 반영).
 *
 * <p>재현: 429 본문이 {@code GenerateRequestsPerDayPerProjectPerModel-FreeTier · 한도 20} 이고 한도는 태평양 자정(한국 16시)에
 * 초기화된다. 16~19시 AI 전략 회차(표시용 코멘트·테마 — 10/6 부터 점수와 무관)가 새 창의 20회를 먼저 쓰고 나면 다음 날 07:30·08:00
 * 재료 분류(배지·시그널 스냅샷 검증용)와 종목 상세 AI 분석이 429 를 맞았다. 기존 '저우선 양보'는 429 를 맞은 뒤에야 물러났다.
 * 이제 저우선(AI 전략)은 그 창의 호출이 (한도 − 예약분) 미만일 때만 부른다 — 기본 20 − 12 = 8.
 */
class GeminiQuotaBudgetTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private GeminiService service;
    private RestTemplate rest;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new GeminiService(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                mock(ObjectProvider.class), mock(ObjectProvider.class));
        rest = mock(RestTemplate.class);
        ReflectionTestUtils.setField(service, "restTemplate", rest);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "apiUrl", "https://example.invalid/v1beta/models/m:generateContent");
        ReflectionTestUtils.setField(service, "quotaDailyRequests", 20);
        ReflectionTestUtils.setField(service, "quotaHighPriorityReserve", 12);
    }

    @Test
    @DisplayName("한도 창은 태평양 날짜 — 한국 16시(서머타임)에 넘어간다")
    void quotaDayFollowsPacificMidnight() {
        assertThat(GeminiService.quotaDay(ZonedDateTime.of(2026, 10, 6, 15, 59, 0, 0, KST).toInstant()))
                .isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(GeminiService.quotaDay(ZonedDateTime.of(2026, 10, 6, 16, 0, 0, 0, KST).toInstant()))
                .isEqualTo(LocalDate.of(2026, 10, 6));
        // 서머타임이 끝나면(11월) 한국 17시
        assertThat(GeminiService.quotaDay(ZonedDateTime.of(2026, 11, 10, 16, 30, 0, 0, KST).toInstant()))
                .isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    @DisplayName("저우선은 창의 호출이 한도 − 예약분 미만일 때만")
    void lowPriorityThreshold() {
        assertThat(GeminiService.lowPriorityAllowed(7, 20, 12)).isTrue();
        assertThat(GeminiService.lowPriorityAllowed(8, 20, 12)).isFalse();
        assertThat(GeminiService.lowPriorityAllowed(0, 20, 25)).isFalse();   // 예약이 한도를 넘으면 저우선은 없다
        assertThat(GeminiService.lowPriorityAllowed(100, 0, 12)).isTrue();   // 한도 0 이하 = 기능 끔(종전 동작)
    }

    @Test
    @DisplayName("재현: 창에서 이미 8회를 썼으면 AI 전략 코멘트는 Gemini 를 부르지 않는다 — 재료 분류 몫을 남긴다")
    void aiScoringYieldsWhenLowPriorityBudgetIsUsed() {
        for (int i = 0; i < 8; i++) service.noteQuotaWindowCall();

        AiStrategySnapshot candidate = new AiStrategySnapshot();
        candidate.setStockCode("005930");
        candidate.setStockName("삼성전자");
        assertThat(service.scoreStockCandidates(List.of(candidate), "SCALPING")).isEmpty();

        verifyNoInteractions(rest);
    }
}
