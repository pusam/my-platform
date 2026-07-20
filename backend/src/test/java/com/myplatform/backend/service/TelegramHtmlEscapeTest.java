package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 텔레그램 HTML 이스케이프/평문 폴백 — parse_mode=HTML 에 외부 텍스트(종목명 "S&T모티브" 등)가
 * 그대로 들어가 400(can't parse entities)으로 알림이 유실되던 버그의 회귀 방지.
 */
class TelegramHtmlEscapeTest {

    @Test
    @DisplayName("escapeHtml — &/</> 이스케이프 (S&T모티브 같은 실존 종목명)")
    void escapeHtml_specialChars() {
        assertThat(TelegramNotificationService.escapeHtml("S&T모티브")).isEqualTo("S&amp;T모티브");
        assertThat(TelegramNotificationService.escapeHtml("PER<10 & ROE>15")).isEqualTo("PER&lt;10 &amp; ROE&gt;15");
        assertThat(TelegramNotificationService.escapeHtml(null)).isEmpty();
        assertThat(TelegramNotificationService.escapeHtml("삼성전자")).isEqualTo("삼성전자");   // 일반 텍스트 불변
    }

    @Test
    @DisplayName("stripHtml — 태그 제거 + 엔티티 복원 (400 폴백 평문 발송용)")
    void stripHtml_tagsAndEntities() {
        assertThat(TelegramNotificationService.stripHtml("<b>매수 신호</b> S&amp;T모티브"))
                .isEqualTo("매수 신호 S&T모티브");
        assertThat(TelegramNotificationService.stripHtml(null)).isEmpty();
    }
}
