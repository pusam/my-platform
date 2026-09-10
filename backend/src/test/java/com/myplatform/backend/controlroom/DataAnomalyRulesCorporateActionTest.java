package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관제실 규칙 ⑭ — 액면변경으로 저장 이력이 현재가와 비교 불가해진 종목.
 *
 * <p>2026-09-11 실사고: 조일알미늄(018470) 현재가 4,865 가 저장가 973 의 정확히 5.00배로 튀어 가격 이상치
 * 그물에 걸렸는데 로그는 "응답 일괄 배수 오염"이라고 말했다 — 실제는 거래정지 중 액면병합 5:1. 이 규칙이
 * 있었다면 "고장이 아니라 상태"라고 바로 읽혔을 것이다.
 */
class DataAnomalyRulesCorporateActionTest {

    private static Map<String, String> suspected(String... codes) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String c : codes) m.put(c, "거래정지 6봉 뒤 현재가가 저장가의 5배 — 액면병합(5:1) 의심, 저장 이력 비교 불가");
        return m;
    }

    @Test
    @DisplayName("의심 종목이 없으면 조용하다(null) — 정상에서 시끄러우면 사람이 무시한다")
    void quietWhenNone() {
        assertThat(DataAnomalyRules.corporateActionHistoryStale(null)).isNull();
        assertThat(DataAnomalyRules.corporateActionHistoryStale(Map.of())).isNull();
    }

    @Test
    @DisplayName("의심 종목이 있으면 WARNING + 코드와 확인 방법을 담는다")
    void reportsSuspectedCodes() {
        DataAnomalyRules.Anomaly a = DataAnomalyRules.corporateActionHistoryStale(suspected("018470"));

        assertThat(a).isNotNull();
        assertThat(a.severity()).isEqualTo(DataAnomalyRules.WARNING);
        assertThat(a.key()).isEqualTo("corporate-action-history-stale");
        assertThat(a.title()).contains("1종목");
        assertThat(a.evidence()).contains("018470");
        assertThat(a.detail())
                .as("무엇을 확인할지와 손대면 안 되는 것까지")
                .contains("PriceSanityGuard").contains("일봉 갱신").contains("보정하지 말 것");
    }

    @Test
    @DisplayName("많으면 앞 5개만 나열하고 나머지는 '외 N건' — 메시지가 컨텍스트를 잡아먹지 않게")
    void truncatesLongList() {
        DataAnomalyRules.Anomaly a = DataAnomalyRules.corporateActionHistoryStale(
                suspected("000001", "000002", "000003", "000004", "000005", "000006", "000007"));

        assertThat(a.title()).contains("7종목");
        assertThat(a.evidence()).contains("000001").contains("000005").contains("외 2건")
                .doesNotContain("000006");
    }
}
