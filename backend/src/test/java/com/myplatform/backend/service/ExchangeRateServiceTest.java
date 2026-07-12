package com.myplatform.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 수출입은행 authkey 로그 유출 방어 (maskAuthKey 순수함수).
 * RestTemplate I/O 예외 메시지는 요청 URL 전체(authkey 쿼리 포함)를 담으므로
 * 로그 출력 전 키가 반드시 마스킹돼야 한다.
 */
class ExchangeRateServiceTest {

    @Test
    void maskAuthKey_예외메시지_URL에_포함된_인증키를_마스킹한다() {
        String key = "SECRETKEY1234567890";
        String msg = "I/O error on GET request for \"https://www.koreaexim.go.kr/site/program/financial/exchangeJSON"
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
}
