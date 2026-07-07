package com.myplatform.backend.service;

import com.myplatform.backend.dto.RiskAnalysisDto.DartDisclosure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DART 관심/보유 종목 필터(2026-07-07) 순수 함수 — 대상 병합(우선순위·상한)·주요 공시 키워드·중립 톤 메시지.
 * §4c: 키워드 매칭만으로 악재 단정 금지 — NOTABLE 은 "주요 공시" 중립 톤, 판단은 사용자.
 */
class DartDisclosureMonitorServiceTest {

    // ================================================================
    // mergeTargetNames — 실잔고 > 봇 포지션 > 관심 우선순위 + dedup + 상한
    // ================================================================

    @Test
    @DisplayName("mergeTargetNames — 우선순위(실잔고>포지션>관심) + dedup + 순서 보존")
    void mergeTargetNames_priorityAndDedup() {
        Set<String> merged = DartDisclosureMonitorService.mergeTargetNames(
                List.of("실잔고주", "공통주"),
                List.of("포지션주", "공통주"),
                List.of("관심주", "실잔고주"),
                30);

        assertThat(merged).containsExactly("실잔고주", "공통주", "포지션주", "관심주");
    }

    @Test
    @DisplayName("mergeTargetNames — 상한 컷: 초과 시 뒤 그룹(관심)부터 잘림, DART rate 보호")
    void mergeTargetNames_capCutsWatchlistFirst() {
        List<String> kis = List.of("K1", "K2");
        List<String> bot = List.of("B1");
        List<String> watch = List.of("W1", "W2", "W3");

        Set<String> merged = DartDisclosureMonitorService.mergeTargetNames(kis, bot, watch, 4);

        assertThat(merged).containsExactly("K1", "K2", "B1", "W1");   // 관심 W2/W3 잘림
    }

    @Test
    @DisplayName("mergeTargetNames — null 그룹/blank 원소 안전")
    void mergeTargetNames_nullSafe() {
        assertThat(DartDisclosureMonitorService.mergeTargetNames(null, null, List.of("W1", " "), 30))
                .containsExactly("W1");
        assertThat(DartDisclosureMonitorService.mergeTargetNames(null, null, null, 30)).isEmpty();
    }

    // ================================================================
    // matchNotableKeyword — 주요 공시 최소 키워드 셋
    // ================================================================

    @Test
    @DisplayName("matchNotableKeyword — 소송/계약해지/영업정지/손해배상 매칭, '정정' 은 의도적 미매칭(스팸)")
    void matchNotableKeyword_minimalSet() {
        assertThat(DartDisclosureMonitorService.matchNotableKeyword("소송등의제기")).isEqualTo("소송");
        assertThat(DartDisclosureMonitorService.matchNotableKeyword("단일판매ㆍ공급계약해지")).isEqualTo("계약해지");
        assertThat(DartDisclosureMonitorService.matchNotableKeyword("영업정지 관련 안내")).isEqualTo("영업정지");
        assertThat(DartDisclosureMonitorService.matchNotableKeyword("손해배상 청구 소송")).isEqualTo("소송");   // 첫 매칭
        // '정정' 대부분은 무해([기재정정] 류) — 스팸 방지 위해 의도적으로 미매칭
        assertThat(DartDisclosureMonitorService.matchNotableKeyword("[기재정정] 분기보고서")).isNull();
        assertThat(DartDisclosureMonitorService.matchNotableKeyword("주요사항보고서")).isNull();
        assertThat(DartDisclosureMonitorService.matchNotableKeyword(null)).isNull();
        assertThat(DartDisclosureMonitorService.matchNotableKeyword("  ")).isNull();
    }

    // ================================================================
    // buildNotableMessage — 중립 톤(§4c: 악재 단정 금지)
    // ================================================================

    @Test
    @DisplayName("주요 공시 메시지 — 중립 톤('주요 공시'), 공시명·키워드·DART 링크·직접 확인 유도 포함")
    void buildNotableMessage_neutralTone() {
        DartDisclosure d = DartDisclosure.builder()
                .corpName("삼성전자")
                .reportNm("소송등의제기ㆍ신청")
                .rceptNo("20260707000123")
                .rceptDt("20260707")
                .build();

        String msg = DartDisclosureMonitorService.buildNotableMessage("삼성전자", d, "소송");

        assertThat(msg).contains("주요 공시");
        assertThat(msg).contains("소송등의제기ㆍ신청");
        assertThat(msg).contains("키워드: 소송");
        assertThat(msg).contains("rcpNo=20260707000123");
        assertThat(msg).contains("호재/악재 판단 아님");   // §4c — 악재 단정 금지, 직접 확인 유도
        assertThat(msg).doesNotContain("중대 공시");        // 위험(DANGER) 톤과 구분
        assertThat(msg).doesNotContain("⚠️");
    }
}
