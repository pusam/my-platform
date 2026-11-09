package com.myplatform.backend.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.myplatform.jwtredis.entrypoint.JwtAuthenticationEntryPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로그인 없는 요청의 401 은 서버 오류가 아니다(2026-11-09).
 * 재현(운영 16:59:56): 로그인 전 화면이 API 6개를 불러 'Responding with unauthorized error' ERROR 6줄이 찍혔다 — 매일 보는
 * ERROR 건수에 섞여 진짜 오류를 가리고, 어느 주소였는지는 남지 않았다. WARN 으로, 메서드·경로와 함께(쿼리 문자열은 빼고).
 * 응답(401·고정 문구)은 그대로다.
 */
class JwtEntryPointLogLevelTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(JwtAuthenticationEntryPoint.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("재현(11/9): 로그인 없는 요청의 401 은 WARN — 메서드·경로를 남기고 쿼리 문자열은 남기지 않는다")
    void unauthenticatedIsWarnWithPath() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/market/status");
        req.setQueryString("token=secret-value");
        MockHttpServletResponse res = new MockHttpServletResponse();

        new JwtAuthenticationEntryPoint().commence(req, res,
                new InsufficientAuthenticationException("Full authentication is required to access this resource"));

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("인증이 필요합니다.");   // 응답은 그대로
        assertThat(appender.list).hasSize(1);
        ILoggingEvent e = appender.list.get(0);
        assertThat(e.getLevel()).isEqualTo(Level.WARN);
        assertThat(e.getFormattedMessage())
                .contains("GET /api/market/status")
                .contains("Full authentication is required")
                .doesNotContain("secret-value");
    }
}
