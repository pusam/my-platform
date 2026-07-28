package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DART 위험 공시 키워드 매칭 ({@link DartService#matchDangerKeyword}).
 *
 * <p>2026-07-28 보강 회귀: 관리종목·상장적격성·감사의견 비적정 계열(의견거절/의견한정/부적정)이
 * 키워드에 없어 무페널티 통과하던 구멍 + 기존 제외 패턴(종속회사 등) 유지 확인.
 */
class DartDangerKeywordTest {

    @Test
    @DisplayName("관리종목 지정 공시 → 위험")
    void managedStockDesignation() {
        assertThat(DartService.matchDangerKeyword("관리종목지정")).isEqualTo("관리종목");
        assertThat(DartService.matchDangerKeyword("주권매매거래정지및관리종목지정예고")).isNotNull();
    }

    @Test
    @DisplayName("상장적격성 실질심사 → 위험")
    void listingEligibilityReview() {
        assertThat(DartService.matchDangerKeyword("상장적격성 실질심사 대상 결정")).isEqualTo("상장적격성");
    }

    @Test
    @DisplayName("감사의견 비적정 계열(거절·한정·부적정) 전부 위험")
    void auditOpinions() {
        assertThat(DartService.matchDangerKeyword("감사의견거절")).isNotNull();
        assertThat(DartService.matchDangerKeyword("[기재정정]감사보고서제출(의견한정)")).isEqualTo("의견한정");
        assertThat(DartService.matchDangerKeyword("감사보고서제출(부적정)")).isEqualTo("부적정");
        assertThat(DartService.matchDangerKeyword("조회공시요구(풍문또는보도)에대한답변(의견거절)")).isEqualTo("의견거절");
    }

    @Test
    @DisplayName("기존 키워드 유지: 유상증자·자본잠식·거래정지")
    void legacyKeywords() {
        assertThat(DartService.matchDangerKeyword("유상증자결정")).isEqualTo("유상증자");
        assertThat(DartService.matchDangerKeyword("자본잠식사유발생")).isEqualTo("자본잠식");
        assertThat(DartService.matchDangerKeyword("주권매매거래정지")).isEqualTo("거래정지");
    }

    @Test
    @DisplayName("종속회사/주식담보제공 공시는 제외 패턴 유지")
    void excludePatterns() {
        assertThat(DartService.matchDangerKeyword("종속회사의유상증자결정")).isNull();
        assertThat(DartService.matchDangerKeyword("최대주주변경을수반하는주식담보제공계약체결")).isNull();
    }

    @Test
    @DisplayName("평범한 공시·null 은 위험 아님")
    void benignTitles() {
        assertThat(DartService.matchDangerKeyword("분기보고서 (2026.03)")).isNull();
        assertThat(DartService.matchDangerKeyword(null)).isNull();
    }
}
