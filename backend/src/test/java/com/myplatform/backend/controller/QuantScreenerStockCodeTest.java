package com.myplatform.backend.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 경로 변수로 들어온 종목코드 형식 가드 — 순수(2026-09-23).
 *
 * <p><b>무엇이 있었나</b>: 분기 크롤 엔드포인트를 지우자 {@code POST /api/screener/collect/finance}
 * 가 남아 있던 {@code /collect/{stockCode}} 에 잡혔고, <b>stockCode="finance" 로 수집이 돌아
 * 200 + {"success":true,"message":"재무 데이터 수집 완료"}</b> 를 응답했다.
 * KIS 는 없는 코드에도 200 을 주므로 수집기가 빈 행을 저장했다 — 실제로 운영 DB 에
 * {@code stock_code='finance'} 행이 하나 생겼다(2026-09-23 08:27).
 *
 * <p>기준은 종목마스터 규약과 같은 <b>6자리 영숫자</b>다. {@code \d{6}} 으로 좁히면
 * 종류주식(끝자리 영문)이 빠진다 — CLAUDE.md §4c 의 KIS 마스터 항목과 같은 이유.
 */
class QuantScreenerStockCodeTest {

    @Test
    @DisplayName("정상 종목코드는 통과 — 숫자 6자리와 종류주식(영문 포함) 모두")
    void validCodes() {
        assertThat(QuantScreenerController.isValidStockCode("005930")).isTrue();
        assertThat(QuantScreenerController.isValidStockCode("00590K")).isTrue();   // 종류주식
        assertThat(QuantScreenerController.isValidStockCode("A12345")).isTrue();
    }

    @Test
    @DisplayName("경로 조각이 종목코드로 들어오는 경우를 막는다 — 이 결함의 실제 입력")
    void pathFragmentsAreRejected() {
        assertThat(QuantScreenerController.isValidStockCode("finance")).isFalse();  // 실제로 행을 만들었다
        assertThat(QuantScreenerController.isValidStockCode("async")).isFalse();
        assertThat(QuantScreenerController.isValidStockCode("all")).isFalse();
    }

    @Test
    @DisplayName("길이·문자 위반과 결측")
    void malformedCodes() {
        assertThat(QuantScreenerController.isValidStockCode("12345")).isFalse();    // 5자리
        assertThat(QuantScreenerController.isValidStockCode("1234567")).isFalse();  // 7자리
        assertThat(QuantScreenerController.isValidStockCode("00593a")).isFalse();   // 소문자
        assertThat(QuantScreenerController.isValidStockCode("005 93")).isFalse();
        assertThat(QuantScreenerController.isValidStockCode("")).isFalse();
        assertThat(QuantScreenerController.isValidStockCode(null)).isFalse();
    }
}
