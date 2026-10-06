package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gemini 429 가 어느 한도에 걸린 것인지 본문에서 읽는다(2026-10-06 진단).
 *
 * <p>10/6 은 09:30 부터 AI 전략 회차마다 429 였고(12:37 이후 매 회차 실패), AI 스냅샷의 Gemini 테마는 장중 회차에 거의 붙지
 * 않는다(9/29~10/2 오전 회차 14~22개 중 2~5개) — 한국 16시(태평양 자정)가 지나면 다시 붙는다. 하루 한도(RPD)를 저녁 회차가 먼저
 * 쓰는 것인지, 분당 한도인지, 같은 키를 쓰는 다른 곳이 있는지 로그만으로는 가릴 수 없었다 — 429 본문을 한 줄도 남기지 않았다.
 * 이 테마는 종합추천 섹터 축 가산(최대 +10)으로 들어가므로 원인을 알아야 판단할 수 있다.
 */
class GeminiQuotaViolationTest {

    @Test
    @DisplayName("한도 위반 상세(QuotaFailure)가 있으면 한도 이름·값·모델을 읽는다")
    void readsQuotaFailure() {
        String body = """
                {
                  "error": {
                    "code": 429,
                    "message": "You exceeded your current quota, please check your plan and billing details.\\n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-2.5-flash-lite\\nPlease retry in 41.2s.",
                    "status": "RESOURCE_EXHAUSTED",
                    "details": [
                      {"@type": "type.googleapis.com/google.rpc.QuotaFailure",
                       "violations": [{"quotaMetric": "generativelanguage.googleapis.com/generate_content_free_tier_requests",
                                       "quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier",
                                       "quotaDimensions": {"location": "global", "model": "gemini-2.5-flash-lite"},
                                       "quotaValue": "20"}]},
                      {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "41s"}
                    ]
                  }
                }""";

        assertThat(GeminiService.quotaViolationOf(body))
                .isEqualTo("GenerateRequestsPerDayPerProjectPerModel-FreeTier · 한도 20 · gemini-2.5-flash-lite");
    }

    @Test
    @DisplayName("상세가 없으면 상태와 메시지 첫 줄")
    void fallsBackToStatusAndMessage() {
        assertThat(GeminiService.quotaViolationOf(
                "{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted (e.g. check quota).\",\"status\":\"RESOURCE_EXHAUSTED\"}}"))
                .isEqualTo("RESOURCE_EXHAUSTED · Resource has been exhausted (e.g. check quota).");
    }

    @Test
    @DisplayName("본문이 없거나 JSON 이 아니면 모른다(null) — 지어내지 않는다")
    void unknownWhenNoBody() {
        assertThat(GeminiService.quotaViolationOf(null)).isNull();
        assertThat(GeminiService.quotaViolationOf("  ")).isNull();
        assertThat(GeminiService.quotaViolationOf("<html>Too Many Requests</html>")).isNull();
    }
}
