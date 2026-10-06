package com.myplatform.backend.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 텔레그램 봇 토큰이 로그로 새지 않는다(2026-10-06).
 *
 * <p>재현: 10/6 10:15 배포 직후 부팅 알림이 Read timed out 으로 실패했는데, ERROR 로그가 RestTemplate 의 I/O 오류 메시지
 * ("I/O error on POST request for \"https://api.telegram.org/bot{토큰}/sendMessage\"")를 그대로 찍어 봇 토큰이 서버 로그에
 * 남았다. DART 키와 같은 규칙 — 키·토큰이 든 URL 을 로그로 내보내지 않는다.
 */
class TelegramTokenRedactionTest {

    private static final String TOKEN = "1234567890:AAH-fake_TOKEN-value_for-test";

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(TelegramNotificationService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private static String allText(ILoggingEvent e) {
        StringBuilder sb = new StringBuilder(e.getFormattedMessage());
        for (IThrowableProxy t = e.getThrowableProxy(); t != null; t = t.getCause()) {
            sb.append(' ').append(t.getMessage());
        }
        return sb.toString();
    }

    @Test
    @DisplayName("재현: I/O 오류로 발송이 실패해도 로그에 봇 토큰이 없다")
    void ioErrorLogDoesNotContainToken() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException(
                        "I/O error on POST request for \"https://api.telegram.org/bot" + TOKEN + "/sendMessage\": Read timed out",
                        new SocketTimeoutException("Read timed out")));

        TelegramNotificationService svc = new TelegramNotificationService();
        ReflectionTestUtils.setField(svc, "restTemplate", rt);
        ReflectionTestUtils.setField(svc, "botToken", TOKEN);
        ReflectionTestUtils.setField(svc, "chatId", "-100123");
        ReflectionTestUtils.setField(svc, "enabled", true);

        svc.sendMessage("부팅 알림");

        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).noneMatch(e -> allText(e).contains(TOKEN));
        assertThat(appender.list).anyMatch(e -> allText(e).contains("Read timed out"));   // 원인은 남긴다
    }

    /** Spring 7 의 응답 오류 메시지 그대로 — "400 Bad Request on POST request for \"{URL}\": \"{본문}\"" (쿼리만 떼고 경로는 남긴다). */
    private static HttpClientErrorException badRequestWithUrl() {
        String body = "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: can't parse entities\"}";
        return HttpClientErrorException.create(
                "400 Bad Request on POST request for \"https://api.telegram.org/bot" + TOKEN + "/sendMessage\": \"" + body + "\"",
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private TelegramNotificationService serviceWith(RestTemplate rt) {
        TelegramNotificationService svc = new TelegramNotificationService();
        ReflectionTestUtils.setField(svc, "restTemplate", rt);
        ReflectionTestUtils.setField(svc, "botToken", TOKEN);
        ReflectionTestUtils.setField(svc, "chatId", "-100123");
        ReflectionTestUtils.setField(svc, "enabled", true);
        return svc;
    }

    @Test
    @DisplayName("재현: 응답 오류(400)로 실패해도 로그에 봇 토큰이 없다 — Spring 7 은 응답 오류 메시지에도 URL 경로를 담는다")
    void httpErrorLogDoesNotContainToken() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(badRequestWithUrl());

        serviceWith(rt).sendMessage("<b>부팅 알림");

        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).noneMatch(e -> allText(e).contains(TOKEN));
        assertThat(appender.list).anyMatch(e -> allText(e).contains("can't parse entities"));   // 원인(본문)은 남긴다
    }

    @Test
    @DisplayName("가린 뒤에도 예외 타입은 그대로 — 400 이면 평문 폴백 발송이 그대로 일어난다")
    void badRequestStillFallsBackToPlainText() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(badRequestWithUrl())
                .thenReturn(ResponseEntity.ok("{\"ok\":true}"));

        serviceWith(rt).sendBriefing("<b>장 마감 알림");

        verify(rt, times(2)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        assertThat(appender.list).noneMatch(e -> allText(e).contains(TOKEN));
    }
}
