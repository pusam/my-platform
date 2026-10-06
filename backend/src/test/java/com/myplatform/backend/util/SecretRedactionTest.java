package com.myplatform.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 외부 API 키·토큰을 로그에서 가린다(2026-10-06). RestTemplate 의 I/O 오류 메시지는 요청 URL 을 통째로 담아, 키를 URL 에 싣는
 * 호출(텔레그램 봇 토큰·Gemini key·DART crtfc_key)은 시간 초과 한 번에 그 값이 ERROR 로그에 남는다 — 10/6 부팅 알림
 * Read timed out 으로 텔레그램 토큰이 실제로 남았다.
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
    @DisplayName("비밀값이 없는 문장·null 은 그대로")
    void leavesOthersAlone() {
        assertThat(SecretRedaction.redact("400 Bad Request")).isEqualTo("400 Bad Request");
        assertThat(SecretRedaction.redact(null)).isNull();
    }

    @Test
    @DisplayName("재현: I/O 오류는 키를 가린 사본으로 다시 던진다 — 타입·원인(IOException)은 그대로")
    void ioErrorsAreRethrownRedacted() {
        SocketTimeoutException cause = new SocketTimeoutException("Read timed out");
        assertThatThrownBy(() -> SecretRedaction.redactingIoErrors(() -> {
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
    @DisplayName("응답 오류(4xx)는 건드리지 않는다 — 호출자가 타입(BadRequest·TooManyRequests)으로 가른다")
    void httpErrorsPassThroughUnchanged() {
        HttpClientErrorException tooMany = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null, null, null);
        assertThatThrownBy(() -> SecretRedaction.redactingIoErrors(() -> {
            throw tooMany;
        })).isSameAs(tooMany);
        assertThat(SecretRedaction.redactingIoErrors(() -> "ok")).isEqualTo("ok");
    }
}
