package com.myplatform.backend.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 한국은행 ECOS 키가 로그로 새지 않는다(2026-10-06).
 *
 * <p>ECOS 는 키를 <b>URL 경로</b>에 싣는다({@code /api/StatisticSearch/{키}/json/...}). 실패 로그는 "URI 미출력(키 보호)"이라고
 * 적혀 있었지만 {@code e.getMessage()} 를 찍었고, RestTemplate 의 I/O 오류 메시지는 요청 URL 을 담는다 — Spring 이 떼는 것은
 * 쿼리 문자열뿐이라(spring-web 7.0 {@code createResourceAccessException}: getRawQuery 앞에서 자른다) 경로에 든 키는 남는다.
 * 같은 날 텔레그램 봇 토큰(경로)이 바로 이렇게 ERROR 로그에 남았다. 운영엔 아직 ECOS 키가 없어(매크로 스냅샷 rate_date 가 늘 비어
 * 있다) 실제로 샌 적은 없다 — 키를 넣는 날 새지 않게 막아 둔다.
 */
class EcosClientKeyRedactionTest {

    private static final String KEY = "ABCDEF0123456789XYZ";

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(EcosClient.class);
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
    @DisplayName("재현: I/O 오류로 조회가 실패해도 로그에 ECOS 키가 없다")
    void ioErrorLogDoesNotContainKey() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForObject(any(URI.class), eq(String.class)))
                .thenThrow(new ResourceAccessException(
                        "I/O error on GET request for \"https://ecos.bok.or.kr/api/StatisticSearch/" + KEY
                                + "/json/kr/1/100/817Y002/D/20260822/20261006/010200000\": Read timed out",
                        new SocketTimeoutException("Read timed out")));

        EcosClient client = new EcosClient(new ObjectMapper());
        ReflectionTestUtils.setField(client, "restTemplate", rt);
        ReflectionTestUtils.setField(client, "apiKey", KEY);

        assertThat(client.getKtb3ySeries(45)).isEmpty();   // 실패는 빈 시계열(§4c) — 종전 그대로

        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).noneMatch(e -> allText(e).contains(KEY));
        assertThat(appender.list).anyMatch(e -> allText(e).contains("Read timed out"));   // 원인은 남긴다
    }
}
