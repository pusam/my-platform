package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChartPatternClient 타이밍 조회 — 가용/미가용 명시(빈 결과가 '신호 없음'인지 'python 다운'인지 구분) + 헬스 집계.
 */
class ChartPatternClientTest {

    @Test
    @DisplayName("타이밍 조회 예외 → available=false + 실패 집계")
    void timingFailure() {
        RestTemplate rt = mock(RestTemplate.class);
        PythonBackendHealthTracker health = mock(PythonBackendHealthTracker.class);
        when(rt.postForObject(anyString(), any(), eq(String.class))).thenThrow(new RuntimeException("down"));
        ChartPatternClient c = new ChartPatternClient("http://x", rt, health);

        ChartPatternClient.TimingFetch fetch = c.getTimingSignals(List.of("005930"));

        assertThat(fetch.available()).isFalse();
        assertThat(fetch.signals()).isEmpty();
        verify(health).recordFailure(eq(PythonBackendHealthTracker.SOURCE_TIMING), anyString());
    }

    @Test
    @DisplayName("타이밍 정상 응답 → available=true + 성공 집계 + 파싱")
    void timingSuccess() {
        RestTemplate rt = mock(RestTemplate.class);
        PythonBackendHealthTracker health = mock(PythonBackendHealthTracker.class);
        String body = "{\"success\":true,\"data\":{\"results\":[{\"ticker\":\"005930\",\"available\":true,"
                + "\"timingScore\":8,\"riskExcluded\":false,\"signals\":[\"정배열\"]}]}}";
        when(rt.postForObject(anyString(), any(), eq(String.class))).thenReturn(body);
        ChartPatternClient c = new ChartPatternClient("http://x", rt, health);

        ChartPatternClient.TimingFetch fetch = c.getTimingSignals(List.of("005930"));

        assertThat(fetch.available()).isTrue();
        assertThat(fetch.signals()).hasSize(1);
        assertThat(fetch.signals().get(0).timingScore()).isEqualTo(8);
        verify(health).recordSuccess(PythonBackendHealthTracker.SOURCE_TIMING);
    }

    @Test
    @DisplayName("빈 입력 → 요청 없이 available=true(요청 안 함)")
    void emptyInput() {
        ChartPatternClient c = new ChartPatternClient("http://x",
                mock(RestTemplate.class), mock(PythonBackendHealthTracker.class));
        ChartPatternClient.TimingFetch fetch = c.getTimingSignals(List.of());
        assertThat(fetch.available()).isTrue();
        assertThat(fetch.signals()).isEmpty();
    }
}
