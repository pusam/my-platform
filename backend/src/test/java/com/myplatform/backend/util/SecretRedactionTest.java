package com.myplatform.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 외부 API 키·토큰을 로그에서 가린다(2026-10-06). RestTemplate(spring-web 7.0)의 오류 메시지는 I/O 오류든 응답 오류(4xx·5xx)든
 * 요청 URL 을 담고, 떼는 것은 쿼리 문자열뿐이다 — 키를 경로에 싣는 호출(텔레그램 봇 토큰·ECOS 키)은 실패 한 번에 그 값이 로그에
 * 남는다. 10/6 부팅 알림 Read timed out 으로 텔레그램 토큰이 실제로 남았다.
 */
class SecretRedactionTest {

    @Test
    @DisplayName("봇 토큰·key·crtfc_key·authkey 값만 가리고 나머지 문장은 그대로")
    void redactsKnownSecrets() {
        assertThat(SecretRedaction.redact(
                "I/O error on POST request for \"https://api.telegram.org/bot1234567890:AAH-x_y/sendMessage\": Read timed out"))
                .isEqualTo("I/O error on POST request for \"https://api.telegram.org/bot***/sendMessage\": Read timed out");
        assertThat(SecretRedaction.redact(
                "I/O error on POST request for \"https://generativelanguage.googleapis.com/v1beta/models/m:generateContent?key=AIzaSyABC-def_123\": Read timed out"))
                .isEqualTo("I/O error on POST request for \"https://generativelanguage.googleapis.com/v1beta/models/m:generateContent?key=***\": Read timed out");
        assertThat(SecretRedaction.redact(
                "I/O error on GET request for \"https://opendart.fss.or.kr/api/list.json?crtfc_key=abcdef0123456789&corp_code=00126380\": Connection reset"))
                .isEqualTo("I/O error on GET request for \"https://opendart.fss.or.kr/api/list.json?crtfc_key=***&corp_code=00126380\": Connection reset");
        assertThat(SecretRedaction.redact("x?searchdate=20261006&authkey=ZZZ999&data=AP01"))
                .isEqualTo("x?searchdate=20261006&authkey=***&data=AP01");
    }

    @Test
    @DisplayName("재현: 경로에 든 ECOS 키도 가린다 — Spring 은 쿼리만 떼고 경로는 남긴다")
    void redactsEcosPathKey() {
        assertThat(SecretRedaction.redact(
                "I/O error on GET request for \"https://ecos.bok.or.kr/api/StatisticSearch/ABCDEF0123456789XYZ/json/kr/1/100/817Y002/D/20260822/20261006/010200000\": Read timed out"))
                .isEqualTo("I/O error on GET request for \"https://ecos.bok.or.kr/api/StatisticSearch/***/json/kr/1/100/817Y002/D/20260822/20261006/010200000\": Read timed out");
    }

    @Test
    @DisplayName("비밀값이 없는 문장·null 은 그대로")
    void leavesOthersAlone() {
        assertThat(SecretRedaction.redact("400 Bad Request")).isEqualTo("400 Bad Request");
        assertThat(SecretRedaction.redact(null)).isNull();
    }

    @Test
    @DisplayName("재현: I/O 오류는 키를 가린 사본으로 다시 던진다 — 타입·원인(IOException)은 그대로")
    void ioErrorsAreRethrownRedacted() {
        SocketTimeoutException cause = new SocketTimeoutException("Read timed out");
        assertThatThrownBy(() -> SecretRedaction.redactingErrors(() -> {
            throw new ResourceAccessException(
                    "I/O error on GET request for \"https://opendart.fss.or.kr/api/list.json?crtfc_key=abcdef0123456789\": Read timed out",
                    cause);
        }))
                .isInstanceOf(ResourceAccessException.class)
                .hasMessageNotContaining("abcdef0123456789")
                .hasMessageContaining("crtfc_key=***")
                .hasCause(cause);
    }

    @Test
    @DisplayName("가릴 것이 없는 응답 오류·성공은 그대로 — 같은 예외 객체")
    void httpErrorsWithoutSecretsPassThrough() {
        HttpClientErrorException tooMany = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null, null, null);
        assertThatThrownBy(() -> SecretRedaction.redactingErrors(() -> {
            throw tooMany;
        })).isSameAs(tooMany);
        assertThat(SecretRedaction.redactingErrors(() -> "ok")).isEqualTo("ok");
    }

    @Test
    @DisplayName("재현: 응답 오류 메시지의 경로 토큰도 가린다 — 하위 타입·상태·헤더·본문은 그대로(호출자가 그걸로 가른다)")
    void httpErrorsAreRethrownRedactedKeepingType() {
        String body = "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":3}}";
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "3");
        HttpClientErrorException tooMany = HttpClientErrorException.create(
                "429 Too Many Requests on POST request for \"https://api.telegram.org/bot1234567890:AAH-x_y/sendMessage\": \"" + body + "\"",
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SecretRedaction.redactingErrors(() -> {
            throw tooMany;
        }))
                .isInstanceOf(HttpClientErrorException.TooManyRequests.class)
                .hasMessageNotContaining("AAH-x_y")
                .hasMessageContaining("/bot***/sendMessage")
                .hasNoCause()
                .satisfies(t -> {
                    HttpClientErrorException e = (HttpClientErrorException) t;
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getResponseBodyAsString()).isEqualTo(body);
                    assertThat(e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3");
                });

        HttpServerErrorException badGateway = HttpServerErrorException.create(
                "502 Bad Gateway on POST request for \"https://api.telegram.org/bot1234567890:AAH-x_y/sendMessage\": [no body]",
                HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SecretRedaction.redactingErrors(() -> {
            throw badGateway;
        }))
                .isInstanceOf(HttpServerErrorException.BadGateway.class)
                .hasMessageNotContaining("AAH-x_y");
    }
}
