package com.myplatform.backend.util;

import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 외부 API 의 키·토큰이 로그로 새지 않게 가린다(2026-10-06).
 *
 * <p>RestTemplate 의 I/O 오류(ResourceAccessException) 메시지는 "I/O error on POST request for \"{URL}\"" 처럼 요청 URL 을 통째로
 * 담는다. 키를 URL 에 싣는 호출 — 텔레그램 봇 토큰(경로)·Gemini {@code key}·DART {@code crtfc_key}·수출입은행 {@code authkey}(쿼리)
 * — 은 시간 초과 한 번에 그 값이 호출자의 ERROR·WARN 로그에 남는다(10/6 배포 직후 부팅 알림 Read timed out 으로 텔레그램 토큰이
 * 실제로 남았다). 응답 오류(4xx·5xx)는 URL 을 담지 않고 호출자가 타입(BadRequest·TooManyRequests)으로 가르므로 건드리지 않는다.
 */
public final class SecretRedaction {

    private static final Pattern BOT_TOKEN = Pattern.compile("/bot[^/\\s\"]+/");
    private static final Pattern QUERY_KEY = Pattern.compile("([?&](?:key|crtfc_key|authkey)=)[^&\\s\"]+");

    private SecretRedaction() {}

    /** 봇 토큰 경로 조각과 key·crtfc_key·authkey 쿼리 값을 별표로 바꾼다. 나머지 문장은 그대로. 순수 함수. */
    public static String redact(String message) {
        if (message == null) return null;
        String s = BOT_TOKEN.matcher(message).replaceAll("/bot***/");
        return QUERY_KEY.matcher(s).replaceAll("$1***");
    }

    /** 외부 호출을 감싸 I/O 오류만 키를 가린 사본으로 바꿔 던진다 — 예외 타입과 원인(IOException)은 그대로. */
    public static <T> T redactingIoErrors(Supplier<T> call) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            throw new ResourceAccessException(redact(e.getMessage()),
                    e.getCause() instanceof IOException io ? io : null);
        }
    }
}
