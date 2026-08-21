package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KRX 상장종목 응답 파싱 (2026-08-21 OTP 2단계 전환) — 순수 함수 테스트.
 *
 * 핵심은 <b>거부 응답("LOGOUT")을 "종목 0건"으로 위장하지 않는 것</b>(§4c). 파싱이 빈 집합을
 * 돌려주면 호출측 <100 게이트가 동기화를 취소하고 기존 목록을 유지한다 — 死피드 위에서
 * activeStockCodes 가 오염되는 일이 없어야 한다.
 */
class StockStatusServiceTest {

    @Test
    @DisplayName("정상 JSON → 6자리 숫자 코드만 수집")
    void parse_validJson() {
        String body = "{\"OutBlock_1\":[" +
                "{\"ISU_SRT_CD\":\"005930\",\"ISU_ABBRV\":\"삼성전자\"}," +
                "{\"ISU_SRT_CD\":\"000660\",\"ISU_ABBRV\":\"SK하이닉스\"}," +
                "{\"ISU_SRT_CD\":\"12345\"}," +          // 5자리 — 제외
                "{\"ISU_SRT_CD\":\"ABC123\"}," +          // 비숫자 — 제외
                "{\"OTHER\":\"x\"}]}";                     // 필드 없음 — 제외

        assertThat(StockStatusService.parseStockCodes(body))
                .containsExactlyInAnyOrder("005930", "000660");
    }

    @Test
    @DisplayName("세션 거부 본문 'LOGOUT' → 빈 집합 (죽은 피드의 실제 응답 — §4c 위장 금지)")
    void parse_logoutBody() {
        assertThat(StockStatusService.parseStockCodes("LOGOUT")).isEmpty();
    }

    @Test
    @DisplayName("HTML/JSON 아님/null/blank → 빈 집합")
    void parse_invalidBodies() {
        assertThat(StockStatusService.parseStockCodes("<html><body>error</body></html>")).isEmpty();
        assertThat(StockStatusService.parseStockCodes(null)).isEmpty();
        assertThat(StockStatusService.parseStockCodes("  ")).isEmpty();
    }

    @Test
    @DisplayName("OutBlock_1 부재/배열 아님 → 빈 집합")
    void parse_missingOutBlock() {
        assertThat(StockStatusService.parseStockCodes("{\"output\":[]}")).isEmpty();
        assertThat(StockStatusService.parseStockCodes("{\"OutBlock_1\":\"notArray\"}")).isEmpty();
    }
}
