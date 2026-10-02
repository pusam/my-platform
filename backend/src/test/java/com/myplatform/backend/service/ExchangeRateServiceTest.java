package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.ExchangeRateDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 수출입은행 authkey 로그 유출 방어 (maskAuthKey 순수함수).
 * RestTemplate I/O 예외 메시지는 요청 URL 전체(authkey 쿼리 포함)를 담으므로
 * 로그 출력 전 키가 반드시 마스킹돼야 한다.
 *
 * <p>2026-10-02 화면 점검: USD/KRW 가 화면에서 사라져 있었다 — 키가 설정된 적이 없어 수출입은행 경로는 늘 건너뛰었고,
 * 유일하게 돌던 네이버 폴백은 페이지가 SPA 로 옮겨 rate=null 을 "조회 완료"로 남겼다. 구 도메인
 * (www.koreaexim.go.kr) API 는 2026-04-30 종료 공지대로 응답이 없어 키를 넣어도 실패했을 것이다.
 */
class ExchangeRateServiceTest {

    private static final String NEW_URL = "https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON";
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void maskAuthKey_예외메시지_URL에_포함된_인증키를_마스킹한다() {
        String key = "SECRETKEY1234567890";
        String msg = "I/O error on GET request for \"" + NEW_URL
                + "?authkey=" + key + "&searchdate=20260712&data=AP01\": Connection timed out";

        String masked = ExchangeRateService.maskAuthKey(msg, key);

        assertFalse(masked.contains(key));
        assertTrue(masked.contains("authkey=***"));
        assertTrue(masked.contains("searchdate=20260712")); // 키 외 진단 정보는 보존
    }

    @Test
    void maskAuthKey_null_메시지는_null_그대로() {
        assertNull(ExchangeRateService.maskAuthKey(null, "key"));
    }

    @Test
    void maskAuthKey_키가_null이거나_빈값이면_원문_유지() {
        assertEquals("msg", ExchangeRateService.maskAuthKey("msg", null));
        assertEquals("msg", ExchangeRateService.maskAuthKey("msg", ""));
    }

    // ── 도메인 이전(2026-10-02) ──

    @Test
    void 운영_설정은_새_oapi_도메인을_쓴다() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(in);
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(yml.contains("url: " + NEW_URL), "application.yml 의 koreaexim.api.url");
            assertFalse(yml.contains("www.koreaexim.go.kr/site/program"), "구 도메인은 응답이 없다");
        }
    }

    @Test
    void 필드_기본값도_새_도메인() throws Exception {
        Value v = ExchangeRateService.class.getDeclaredField("koreaeximApiUrl").getAnnotation(Value.class);
        assertTrue(v.value().contains(NEW_URL), v.value());
    }

    // ── 응답 해석(순수) ──

    @Test
    void 재현_틀린_키는_result3_을_200으로_준다__이유가_남는다() throws Exception {
        // 10/2 실측(oapi 도메인, 가짜 키) 그대로
        JsonNode body = om.readTree("[{\"result\":3,\"cur_unit\":null,\"ttb\":null,\"tts\":null,\"deal_bas_r\":null,"
                + "\"bkpr\":null,\"yy_efee_r\":null,\"ten_dd_efee_r\":null,\"kftc_bkpr\":null,\"kftc_deal_bas_r\":null,\"cur_nm\":null}]");

        ExchangeRateService.EximParse p = ExchangeRateService.parseUsdDealBasRate(body);

        assertNull(p.rate());
        assertNotNull(p.problem());
        assertTrue(p.problem().contains("result=3"), p.problem());
    }

    @Test
    void 정상_응답에서_USD_매매기준율을_읽는다_콤마_제거() throws Exception {
        JsonNode body = om.readTree("[{\"result\":1,\"cur_unit\":\"JPY(100)\",\"deal_bas_r\":\"935.12\"},"
                + "{\"result\":1,\"cur_unit\":\"USD\",\"deal_bas_r\":\"1,358.9\"}]");

        ExchangeRateService.EximParse p = ExchangeRateService.parseUsdDealBasRate(body);

        assertEquals(new BigDecimal("1358.9"), p.rate());
        assertNull(p.problem());
    }

    @Test
    void 빈_배열은_고시_전_비영업일__이상이_아니다() throws Exception {
        ExchangeRateService.EximParse p = ExchangeRateService.parseUsdDealBasRate(om.readTree("[]"));

        assertNull(p.rate());
        assertNull(p.problem(), "정상 상태라 경고 사유가 없다");
    }

    @Test
    void 배열이_아니거나_USD가_없으면_이유와_함께_실패() throws Exception {
        assertNotNull(ExchangeRateService.parseUsdDealBasRate(om.readTree("{\"error\":\"x\"}")).problem());
        assertNotNull(ExchangeRateService.parseUsdDealBasRate(null).problem());
        ExchangeRateService.EximParse noUsd = ExchangeRateService.parseUsdDealBasRate(
                om.readTree("[{\"result\":1,\"cur_unit\":\"EUR\",\"deal_bas_r\":\"1,500\"}]"));
        assertNull(noUsd.rate());
        assertEquals("USD 항목 없음", noUsd.problem());
    }

    @Test
    void 키가_없으면_외부_호출_없이_rate_null() {
        ExchangeRateService svc = new ExchangeRateService();
        ReflectionTestUtils.setField(svc, "koreaeximApiKey", "");
        // 호출되면 실패하도록 닿을 수 없는 주소 — 키가 없으면 아예 부르지 않아야 한다
        ReflectionTestUtils.setField(svc, "koreaeximApiUrl", "http://127.0.0.1:1/never");

        ExchangeRateDto dto = svc.getCurrentExchangeRate();

        assertNull(dto.getRate());
        assertNotNull(dto.getInterpretation());
    }
}
