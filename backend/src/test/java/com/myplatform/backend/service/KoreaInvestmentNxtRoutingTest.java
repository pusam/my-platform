package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.util.OrderSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NXT/연장장 주문 라우팅 배선(Phase 1, 2026-09-14 대비) — applyNxtRouting 결정성 검증.
 * 핵심 불변식: ① flag OFF = 바디 현행 동일(NXT 필드 미추가) ② NXT 파라미터 미확정 = fail-CLOSED
 * 명시적 거부(rt_cd≠0, killswitch 유발 null 아님) ③ 확정 시에만 거래소구분 필드 추가.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KoreaInvestmentNxtRoutingTest {

    @Mock private RestTemplate restTemplate;
    @Mock private KisApiRateLimiter rateLimiter;

    private KoreaInvestmentService service;

    private static final LocalTime IN_NXT_WINDOW = LocalTime.of(16, 0);  // 15:30~20:00 창 내

    @BeforeEach
    void setUp() {
        // P3-8: 토큰은 공유 KisTokenManager 로 이관 — applyNxtRouting 은 토큰 미사용이라 기본 매니저로 충분.
        service = new KoreaInvestmentService(restTemplate, new ObjectMapper(), rateLimiter,
                new KisTokenManager(restTemplate, new ObjectMapper()));
    }

    private Map<String, String> baseBody() {
        Map<String, String> body = new HashMap<>();
        body.put("CANO", "12345678");
        body.put("PDNO", "005930");
        body.put("ORD_DVSN", "00");
        return body;
    }

    private JsonNode invokeRouting(Map<String, String> body, OrderSession requested, LocalTime now) {
        return (JsonNode) ReflectionTestUtils.invokeMethod(
                service, "applyNxtRouting", body, "005930", requested, now);
    }

    @Test
    @DisplayName("REGULAR 요청 → 바디 무변경 + null (현행 바이트 동일)")
    void regular_bodyUnchanged() {
        ReflectionTestUtils.setField(service, "nxtRoutingEnabled", true);   // flag ON 이어도 REGULAR 요청은 무영향
        ReflectionTestUtils.setField(service, "nxtExchangeParamName", "EXCG_ID_DVSN_CD");
        ReflectionTestUtils.setField(service, "nxtExchangeParamValue", "NXT");
        Map<String, String> body = baseBody();

        JsonNode reject = invokeRouting(body, OrderSession.REGULAR, IN_NXT_WINDOW);

        assertThat(reject).isNull();
        assertThat(body).doesNotContainKey("EXCG_ID_DVSN_CD");
        assertThat(body).hasSize(3);   // CANO/PDNO/ORD_DVSN — 변화 없음
    }

    @Test
    @DisplayName("flag OFF + NXT 요청 → REGULAR 강등, 바디 무변경 + null")
    void flagOff_downgradeNoBodyChange() {
        ReflectionTestUtils.setField(service, "nxtRoutingEnabled", false);
        ReflectionTestUtils.setField(service, "nxtExchangeParamName", "EXCG_ID_DVSN_CD");
        ReflectionTestUtils.setField(service, "nxtExchangeParamValue", "NXT");
        Map<String, String> body = baseBody();

        JsonNode reject = invokeRouting(body, OrderSession.NXT_EXTENDED, IN_NXT_WINDOW);

        assertThat(reject).isNull();
        assertThat(body).doesNotContainKey("EXCG_ID_DVSN_CD");
    }

    @Test
    @DisplayName("flag ON + NXT 창 내 + 파라미터 미설정 → fail-CLOSED 명시적 거부(rt_cd=1), 바디 무변경")
    void flagOn_unconfigured_failClosed() {
        ReflectionTestUtils.setField(service, "nxtRoutingEnabled", true);
        ReflectionTestUtils.setField(service, "nxtExchangeParamName", "");   // 미확정
        ReflectionTestUtils.setField(service, "nxtExchangeParamValue", "");
        Map<String, String> body = baseBody();

        JsonNode reject = invokeRouting(body, OrderSession.NXT_EXTENDED, IN_NXT_WINDOW);

        assertThat(reject).isNotNull();
        assertThat(reject.get("rt_cd").asText()).isEqualTo("1");   // ≠0 = 명시적 거부 → markFailed(killswitch 미발동)
        assertThat(reject.get("msg1").asText()).isEqualTo("NXT_PARAM_UNCONFIGURED");
        assertThat(body).doesNotContainKey("EXCG_ID_DVSN_CD");     // 주문 미구성
    }

    @Test
    @DisplayName("flag ON + NXT 창 내 + 파라미터 설정 → 거래소구분 필드 추가 + null (진행)")
    void flagOn_configured_addsField() {
        ReflectionTestUtils.setField(service, "nxtRoutingEnabled", true);
        ReflectionTestUtils.setField(service, "nxtExchangeParamName", "EXCG_ID_DVSN_CD");
        ReflectionTestUtils.setField(service, "nxtExchangeParamValue", "NXT");
        Map<String, String> body = baseBody();

        JsonNode reject = invokeRouting(body, OrderSession.NXT_EXTENDED, IN_NXT_WINDOW);

        assertThat(reject).isNull();
        assertThat(body).containsEntry("EXCG_ID_DVSN_CD", "NXT");
    }

    @Test
    @DisplayName("flag ON + NXT 요청이지만 정규장 시간(10:00) → REGULAR 강등, 필드 미추가")
    void flagOn_regularHours_noNxt() {
        ReflectionTestUtils.setField(service, "nxtRoutingEnabled", true);
        ReflectionTestUtils.setField(service, "nxtExchangeParamName", "EXCG_ID_DVSN_CD");
        ReflectionTestUtils.setField(service, "nxtExchangeParamValue", "NXT");
        Map<String, String> body = baseBody();

        JsonNode reject = invokeRouting(body, OrderSession.NXT_EXTENDED, LocalTime.of(10, 0));

        assertThat(reject).isNull();
        assertThat(body).doesNotContainKey("EXCG_ID_DVSN_CD");
    }
}
