package com.myplatform.backend.util;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 외부 API 의 키·토큰이 로그로 새지 않게 가린다(2026-10-06).
 *
 * <p>RestTemplate(spring-web 7.0)의 오류 메시지는 요청 URL 을 담는다 — I/O 오류는 "I/O error on POST request for \"{URL}\"",
 * 응답 오류(4xx·5xx)도 "400 Bad Request on POST request for \"{URL}\": \"{본문}\"". Spring 은 <b>쿼리 문자열만 떼고</b>(? 앞에서
 * 자른다) 경로는 그대로 둔다. 그래서 키를 <b>경로</b>에 싣는 호출 — 텔레그램 봇 토큰({@code /bot{토큰}/})·한국은행 ECOS
 * ({@code /api/StatisticSearch/{키}/}) — 은 시간 초과든 응답 오류든 실패 한 번에 그 값이 호출자의 로그에 남는다(10/6 배포 직후
 * 부팅 알림 Read timed out 으로 텔레그램 토큰이 실제로 남았다). 쿼리에 싣는 Gemini {@code key}·DART {@code crtfc_key}·수출입은행
 * {@code authkey} 는 Spring 이 이미 떼지만(운영 로그로 확인 — 10/6 08:00 Gemini 시간 초과 메시지에 {@code ?key=} 가 없다), 다른
 * 클라이언트로 바뀌어도 새지 않게 같은 규칙으로 한 번 더 가린다.
 */
public final class SecretRedaction {

    private static final Pattern BOT_TOKEN = Pattern.compile("/bot[^/\\s\"]+/");
    /** 한국은행 ECOS — 키가 서비스 이름 다음 경로 칸이다({@code /api/StatisticSearch/{키}/json/...}). */
    private static final Pattern ECOS_PATH_KEY = Pattern.compile("(ecos\\.bok\\.or\\.kr/api/[A-Za-z]+/)[^/\\s\"]+");
    private static final Pattern QUERY_KEY = Pattern.compile("([?&](?:key|crtfc_key|authkey)=)[^&\\s\"]+");

    private SecretRedaction() {}

    /** 봇 토큰·ECOS 키 경로 조각과 key·crtfc_key·authkey 쿼리 값을 별표로 바꾼다. 나머지 문장은 그대로. 순수 함수. */
    public static String redact(String message) {
        if (message == null) return null;
        String s = BOT_TOKEN.matcher(message).replaceAll("/bot***/");
        s = ECOS_PATH_KEY.matcher(s).replaceAll("$1***");
        return QUERY_KEY.matcher(s).replaceAll("$1***");
    }

    /**
     * 외부 호출을 감싸 오류 메시지의 키를 가린 사본으로 바꿔 던진다.
     * <ul>
     * <li>I/O 오류 — 같은 타입, 원인(IOException)은 그대로(원인 메시지에는 URL 이 없다).</li>
     * <li>응답 오류 — 같은 하위 타입(BadRequest·TooManyRequests 등)·상태·헤더·본문으로 다시 만든다. 호출자는 타입으로 가르고
     *     본문({@code retry_after}·{@code retryDelay})과 헤더(Retry-After)를 읽으므로 그 셋은 바뀌면 안 된다. 원래 예외를
     *     원인으로 달지 않는다 — 스택 트레이스를 찍는 로그가 원인 메시지로 토큰을 다시 남긴다.</li>
     * <li>가릴 것이 없으면 원래 예외를 그대로 던진다.</li>
     * </ul>
     */
    public static <T> T redactingErrors(Supplier<T> call) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            String m = redact(e.getMessage());
            if (Objects.equals(m, e.getMessage())) throw e;
            throw new ResourceAccessException(m, e.getCause() instanceof IOException io ? io : null);
        } catch (HttpClientErrorException e) {
            String m = redact(e.getMessage());
            if (Objects.equals(m, e.getMessage())) throw e;
            throw HttpClientErrorException.create(m, e.getStatusCode(), e.getStatusText(),
                    headersOf(e.getResponseHeaders()), e.getResponseBodyAsByteArray(), StandardCharsets.UTF_8);
        } catch (HttpServerErrorException e) {
            String m = redact(e.getMessage());
            if (Objects.equals(m, e.getMessage())) throw e;
            throw HttpServerErrorException.create(m, e.getStatusCode(), e.getStatusText(),
                    headersOf(e.getResponseHeaders()), e.getResponseBodyAsByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static HttpHeaders headersOf(HttpHeaders headers) {
        return headers != null ? headers : HttpHeaders.EMPTY;
    }
}
