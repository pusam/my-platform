package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 코스피 출발 전망은 간밤 미국장을 본다(2026-11-06 운영 화면).
 *
 * <p>재현: 간밤 나스닥100 현물 −6.36%·S&P500 −4.36% 였는데 08:37 '종합 시장 방향성'은 <b>보합 출발 예상</b>이었다. 입력이 선물뿐이라
 * 그렇다 — 선물 등락률은 새 CME 세션 기준이라 −0.52% 였고, 간밤 낙폭이 어디에도 들어가지 않았다. 같은 시각 오늘 탭은 '간밤 미국장 약세'였다.
 */
class GlobalFuturesOvernightImpactTest {

    private static final LocalDateTime FRI_0837 = LocalDateTime.of(2026, 11, 6, 8, 37);
    private static final GlobalFuturesService.LastUsSession CRASH = new GlobalFuturesService.LastUsSession(-6.36, -4.36);

    private static GlobalFuturesService.FuturesQuote ok(String symbol, String rate, String price) {
        return GlobalFuturesService.FuturesQuote.builder()
                .symbol(symbol).success(true)
                .changeRate(new BigDecimal(rate)).currentPrice(new BigDecimal(price))
                .build();
    }

    /** 2026-11-06 08:37 화면 값. VIX 는 2시간 지난 값(종가)이다. */
    private static List<GlobalFuturesService.FuturesQuote> quotes() {
        GlobalFuturesService.FuturesQuote vix = GlobalFuturesService.FuturesQuote.builder()
                .symbol("VIX").success(true).stale(true).dataAgeMinutes(140)
                .changeRate(new BigDecimal("69.66")).currentPrice(new BigDecimal("28.18")).build();
        return List.of(ok("NQ", "-0.52", "26612"), ok("ES", "-0.37", "7355.75"), ok("CL", "1.03", "91.45"),
                ok("KRW", "1.13", "1355.74"), vix);
    }

    private static GlobalFuturesService service() {
        return new GlobalFuturesService(mock(RestTemplate.class), new ObjectMapper(), mock(KisApiService.class));
    }

    @Test
    @DisplayName("재현: 간밤 나스닥 −6.36% 면 장 시작 전 전망은 폭락 경계 — '보합 출발 예상'이 아니다")
    void overnightCrashReachesTheForecast() {
        Map<String, Object> a = service().analyzeImpact(quotes(), FRI_0837, CRASH);

        assertThat(a.get("alertLevel")).isEqualTo("CRISIS");
        assertThat((String) a.get("comment")).contains("폭락 경계").contains("간밤 나스닥 -6.36%").doesNotContain("보합");
    }

    @Test
    @DisplayName("간밤 값이 없으면 종전대로 선물로 계산한다 — 미국 휴장 다음 날")
    void noOvernightFallsBackToFutures() {
        Map<String, Object> a = service().analyzeImpact(quotes(), FRI_0837, GlobalFuturesService.LastUsSession.NONE);

        assertThat((String) a.get("comment")).contains("나스닥 선물 -0.52%");
    }

    @Test
    @DisplayName("작은 간밤 움직임도 선물 대신 쓴다 — 점수가 간밤을 본다")
    @SuppressWarnings("unchecked")
    void overnightReplacesFuturesFactors() {
        Map<String, Object> a = service().analyzeImpact(quotes(), FRI_0837, new GlobalFuturesService.LastUsSession(1.20, 0.80));

        List<Map<String, Object>> factors = (List<Map<String, Object>>) a.get("riskFactors");
        assertThat(factors.get(0).get("name")).isEqualTo("나스닥100 간밤");
        assertThat(factors.get(0).get("changeRate")).isEqualTo(1.20);
        assertThat(factors.get(1).get("name")).isEqualTo("S&P500 간밤");
        assertThat((String) a.get("comment")).contains("간밤 나스닥 +1.20%");
    }

    @Test
    @DisplayName("간밤 미국장이 코스피에 아직 안 들어간 시간 — 평일 05:00~09:00, 토 05:00 부터 월 09:00 까지")
    void windowIsBetweenUsCloseAndKospiOpen() {
        assertThat(GlobalFuturesService.usCloseNotYetInKospi(LocalDateTime.of(2026, 11, 6, 4, 59))).isFalse();   // 금
        assertThat(GlobalFuturesService.usCloseNotYetInKospi(LocalDateTime.of(2026, 11, 6, 5, 0))).isTrue();
        assertThat(GlobalFuturesService.usCloseNotYetInKospi(LocalDateTime.of(2026, 11, 6, 8, 59))).isTrue();
        assertThat(GlobalFuturesService.usCloseNotYetInKospi(LocalDateTime.of(2026, 11, 6, 9, 0))).isFalse();
        assertThat(GlobalFuturesService.usCloseNotYetInKospi(LocalDateTime.of(2026, 11, 6, 21, 0))).isFalse();
        assertThat(GlobalFuturesService.usCloseNotYetInKospi(LocalDateTime.of(2026, 11, 7, 4, 0))).isFalse();    // 토 새벽 — 미국 장중
        assertThat(GlobalFuturesService.usCloseNotYetInKospi(LocalDateTime.of(2026, 11, 7, 10, 0))).isTrue();
        assertThat(GlobalFuturesService.usCloseNotYetInKospi(LocalDateTime.of(2026, 11, 8, 15, 0))).isTrue();    // 일
        assertThat(GlobalFuturesService.usCloseNotYetInKospi(LocalDateTime.of(2026, 11, 9, 1, 0))).isTrue();     // 월 새벽 — 금요일 세션
        assertThat(GlobalFuturesService.usCloseNotYetInKospi(LocalDateTime.of(2026, 11, 9, 9, 0))).isFalse();
    }
}
