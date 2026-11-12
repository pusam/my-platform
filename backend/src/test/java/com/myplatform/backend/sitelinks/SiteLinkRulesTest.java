package com.myplatform.backend.sitelinks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 바로가기 주소는 화면에서 링크가 된다 — http/https 가 아닌 주소가 저장되면 누르는 순간 스크립트가 돌 수 있다.
 */
class SiteLinkRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "https://example.com/",
            "http://example.com:8080/path?a=1&b=2#top",
            "HTTPS://Example.com/Admin",
            "https://sub.example.co.kr/a/b"})
    @DisplayName("http·https 주소는 그대로 받는다")
    void acceptsHttpAndHttps(String url) {
        assertThat(SiteLinkRules.url(url)).isEqualTo(url);
    }

    @Test
    @DisplayName("앞뒤 공백은 떼고 저장한다")
    void trims() {
        assertThat(SiteLinkRules.url("  https://example.com/x  ")).isEqualTo("https://example.com/x");
        assertThat(SiteLinkRules.name("  주식  ")).isEqualTo("주식");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox(1)",
            "file:///etc/passwd",
            "ftp://example.com/",
            "//example.com/path",
            "example.com",
            "/sites",
            "https:example.com",
            "https:///path-only",
            "java\nscript:alert(1)",
            "https://example.com/a b",
            "https://user:secret@example.com/",
            "https://user@example.com/"})
    @DisplayName("스크립트·다른 방식·호스트 없는 주소·계정 정보가 든 주소는 거절한다")
    void rejectsUnsafe(String url) {
        assertThatThrownBy(() -> SiteLinkRules.url(url)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("빈 주소·너무 긴 주소는 거절한다")
    void rejectsEmptyAndTooLong() {
        assertThatThrownBy(() -> SiteLinkRules.url(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SiteLinkRules.url("   ")).isInstanceOf(IllegalArgumentException.class);
        String longUrl = "https://example.com/" + "a".repeat(SiteLinkRules.MAX_URL);
        assertThatThrownBy(() -> SiteLinkRules.url(longUrl)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이름은 필수·60자까지, 설명은 선택(비면 null)·200자까지")
    void nameAndDescription() {
        assertThatThrownBy(() -> SiteLinkRules.name(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SiteLinkRules.name("가".repeat(SiteLinkRules.MAX_NAME + 1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(SiteLinkRules.name("가".repeat(SiteLinkRules.MAX_NAME))).hasSize(SiteLinkRules.MAX_NAME);

        assertThat(SiteLinkRules.description(null)).isNull();
        assertThat(SiteLinkRules.description("  ")).isNull();
        assertThat(SiteLinkRules.description(" 관리자 화면 ")).isEqualTo("관리자 화면");
        assertThatThrownBy(() -> SiteLinkRules.description("가".repeat(SiteLinkRules.MAX_DESCRIPTION + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
