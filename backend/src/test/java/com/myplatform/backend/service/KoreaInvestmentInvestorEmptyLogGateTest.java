package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외국인/기관 매매종목가집계(FHPTJ04400000) 빈 응답 로그 게이트 — previousInvestorState 순수 함수.
 *
 * KIS 는 매일 08:00~10:00 사이 이 API 를 정상(rt_cd=0)인데 output=[] 로 돌려준다(집계 전). 캐시 워머가
 * 그 창에서 계속 호출하니 같은 WARN 이 하루 400줄(2026-09-01 실측 402)이었다. §5 규칙대로 "경보를 끄는
 * 게 아니라 상태가 바뀐 순간만" 남긴다: 하루 첫 빈 응답 1회 WARN, 이어지는 빈 응답은 생략, 데이터가
 * 들어오면 복구 알림 1회(WARN — logback 이 이 클래스 로거를 WARN 으로 묶어 INFO 는 안 보임), 그 뒤 다시 비면 다시 WARN(장중 재발 = 진짜 이상, 침묵 금지 §4c).
 */
class KoreaInvestmentInvestorEmptyLogGateTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 2);

    @Test
    @DisplayName("하루 첫 관측은 이전 상태 없음(null) — 빈 응답이면 WARN 1회 대상")
    void firstObservationOfDayHasNoPrevious() {
        Map<String, String> holder = new HashMap<>();
        assertThat(KoreaInvestmentService.previousInvestorState(holder, "1/buy", D1, "EMPTY")).isNull();
    }

    @Test
    @DisplayName("같은 날 같은 상태가 이어지면 이전 상태를 돌려준다 — 호출부가 중복 WARN 을 생략")
    void repeatedEmptyReturnsEmpty() {
        Map<String, String> holder = new HashMap<>();
        KoreaInvestmentService.previousInvestorState(holder, "1/buy", D1, "EMPTY");
        assertThat(KoreaInvestmentService.previousInvestorState(holder, "1/buy", D1, "EMPTY")).isEqualTo("EMPTY");
        assertThat(KoreaInvestmentService.previousInvestorState(holder, "1/buy", D1, "EMPTY")).isEqualTo("EMPTY");
    }

    @Test
    @DisplayName("EMPTY → OK 전이는 이전값 EMPTY 를 돌려준다 — 호출부가 복구 INFO 1회")
    void transitionToOkExposesPreviousEmpty() {
        Map<String, String> holder = new HashMap<>();
        KoreaInvestmentService.previousInvestorState(holder, "2/sell", D1, "EMPTY");
        assertThat(KoreaInvestmentService.previousInvestorState(holder, "2/sell", D1, "OK")).isEqualTo("EMPTY");
        // OK 가 이어지는 동안은 아무 로그도 없어야 한다 — 이전값 OK
        assertThat(KoreaInvestmentService.previousInvestorState(holder, "2/sell", D1, "OK")).isEqualTo("OK");
    }

    @Test
    @DisplayName("OK → EMPTY 재발은 이전값 OK — 장중 다시 비면 다시 WARN(침묵 금지)")
    void relapseAfterOkIsVisibleAgain() {
        Map<String, String> holder = new HashMap<>();
        KoreaInvestmentService.previousInvestorState(holder, "1/buy", D1, "EMPTY");
        KoreaInvestmentService.previousInvestorState(holder, "1/buy", D1, "OK");
        assertThat(KoreaInvestmentService.previousInvestorState(holder, "1/buy", D1, "EMPTY")).isEqualTo("OK");
    }

    @Test
    @DisplayName("날짜가 바뀌면 이전 상태를 잊는다 — 매일 첫 빈 응답은 반드시 1회 보인다")
    void newDayResetsState() {
        Map<String, String> holder = new HashMap<>();
        KoreaInvestmentService.previousInvestorState(holder, "1/buy", D1, "EMPTY");
        assertThat(KoreaInvestmentService.previousInvestorState(holder, "1/buy", D2, "EMPTY")).isNull();
    }

    @Test
    @DisplayName("scope(투자자구분/매수·매도)별로 독립 — 서로의 상태를 덮어쓰지 않는다")
    void scopesAreIndependent() {
        Map<String, String> holder = new HashMap<>();
        KoreaInvestmentService.previousInvestorState(holder, "1/buy", D1, "EMPTY");
        assertThat(KoreaInvestmentService.previousInvestorState(holder, "2/buy", D1, "EMPTY")).isNull();
        assertThat(KoreaInvestmentService.previousInvestorState(holder, "1/sell", D1, "OK")).isNull();
        assertThat(KoreaInvestmentService.previousInvestorState(holder, "1/buy", D1, "OK")).isEqualTo("EMPTY");
        // 맵은 scope 당 1항목만 유지한다(날짜별로 불어나지 않음)
        assertThat(holder).hasSize(3);
    }
}
