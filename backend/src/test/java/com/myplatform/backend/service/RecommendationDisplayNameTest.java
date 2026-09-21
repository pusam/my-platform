package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발굴 목록의 <b>표시용 종목명</b> 해석 — 순수 함수(2026-09-21 데이터 점검).
 *
 * <p><b>무엇이 틀렸나</b>: 저평가·성장 트랙은 이름을 {@code stock_financial_data.stock_name} 에서 읽는데,
 * prod 실측 당일 수집분 <b>2,660행 중 1,869행(70%)이 이름 대신 종목코드</b>를 담고 있었다
 * (011780 → "011780", 002380 → "002380"). 그래서 발굴 저평가 TOP10 이 "금호석유화학" 대신
 * "011780" 로 떴다 — 10건 중 9건이 그랬다. 가격·점수·태그는 정상이고 <b>이름만</b> 비어 있다.
 *
 * <p><b>왜 읽는 쪽에서 고치나</b>: `stock_master` 에는 2,668종목 전부 정상 이름이 있다(KIS 종목마스터).
 * 재무 테이블은 writer 가 둘이라(§4c) 일괄 UPDATE 가 위험하고, 운영 DB 보정은 별도 판단 사안이다.
 * 표시 계층에서 마스터 이름으로 보강하면 기존 행을 건드리지 않고 화면이 정상화된다.
 *
 * <p><b>불변식</b>: 마스터에도 없으면 <b>코드를 그대로</b> 둔다 — 빈칸이나 "-" 로 지우지 않는다.
 * 코드는 적어도 종목을 특정할 수 있는 정직한 값이다(§4c: 없는 걸 그럴듯하게 만들지 않되 지우지도 않는다).
 */
class RecommendationDisplayNameTest {

    private static final Map<String, String> MASTER = Map.of(
            "011780", "금호석유화학",
            "002380", "케이씨씨",
            "012330", "현대모비스");

    @Test
    @DisplayName("재무 행의 이름이 코드와 같으면 마스터 이름으로 보강한다 — prod 70% 가 이 경우")
    void codeAsNameIsReplacedByMasterName() {
        assertThat(RecommendationService.resolveDisplayName("011780", "011780", MASTER))
                .isEqualTo("금호석유화학");
        assertThat(RecommendationService.resolveDisplayName("002380", "002380", MASTER))
                .isEqualTo("케이씨씨");
    }

    @Test
    @DisplayName("재무 행에 진짜 이름이 있으면 그대로 쓴다 — 마스터로 덮어쓰지 않는다")
    void realNameIsKept() {
        assertThat(RecommendationService.resolveDisplayName("012330", "현대모비스", MASTER))
                .isEqualTo("현대모비스");
        // 마스터와 표기가 달라도 재무 쪽을 존중한다(이 함수는 '결측 보강'이지 '정규화'가 아니다)
        assertThat(RecommendationService.resolveDisplayName("011780", "금호석유", MASTER))
                .isEqualTo("금호석유");
    }

    @Test
    @DisplayName("이름이 비었을 때도 마스터로 채운다")
    void blankNameIsFilled() {
        assertThat(RecommendationService.resolveDisplayName("011780", null, MASTER)).isEqualTo("금호석유화학");
        assertThat(RecommendationService.resolveDisplayName("011780", "  ", MASTER)).isEqualTo("금호석유화학");
    }

    @Test
    @DisplayName("마스터에도 없으면 코드를 남긴다 — 빈칸으로 지우지 않는다(§4c)")
    void unknownCodeKeepsCode() {
        assertThat(RecommendationService.resolveDisplayName("999999", "999999", MASTER)).isEqualTo("999999");
        assertThat(RecommendationService.resolveDisplayName("999999", null, MASTER)).isEqualTo("999999");
        assertThat(RecommendationService.resolveDisplayName("999999", null, Map.of())).isEqualTo("999999");
    }

    @Test
    @DisplayName("마스터 조회가 실패해도(null 맵) 터지지 않고 기존 값을 유지한다 — fail-open")
    void nullMasterMapIsSafe() {
        assertThat(RecommendationService.resolveDisplayName("011780", "011780", null)).isEqualTo("011780");
        assertThat(RecommendationService.resolveDisplayName("012330", "현대모비스", null)).isEqualTo("현대모비스");
    }

    @Test
    @DisplayName("코드 자체가 없으면 손대지 않는다")
    void nullCodeIsUntouched() {
        assertThat(RecommendationService.resolveDisplayName(null, "현대모비스", MASTER)).isEqualTo("현대모비스");
        assertThat(RecommendationService.resolveDisplayName(null, null, MASTER)).isNull();
    }
}
