package com.myplatform.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 밖에서 부를 일이 없는 주소는 nginx 가 백엔드로 넘기지 않는다 (2026-11-10).
 *
 * <p>진단 API({@code /api/diagnostics})와 python API({@code /api/v2})는 로그인 없이 응답하는 주소다.
 * 화면은 둘 다 쓰지 않는다 — 진단은 서버 안에서 {@code localhost:8080} 으로 보고, python 은 백엔드가
 * 도커 내부망({@code python-backend:8000})으로 부른다. 그래서 밖에서 오는 요청은 nginx 에서 403 으로 끝낸다.
 *
 * <p>nginx 는 가장 긴 접두 location 을 고르므로 이 블록들이 일반 {@code /api/} 프록시보다 먼저 맞는다.
 * 실제 동작은 배포 때 서버의 {@code nginx -t} 와 배포 뒤 바깥 호출로 확인한다 — 이 테스트는 설정에서
 * 블록이 빠지거나 다시 프록시로 바뀌는 것을 막는다.
 */
class NginxExternalBlockTest {

    private static String httpsServer;

    @BeforeAll
    static void load() throws IOException {
        String conf = Files.readString(Path.of("..", "nginx", "default.conf"), StandardCharsets.UTF_8);
        int https = conf.indexOf("listen 443");
        assertThat(https).as("443 server 블록을 찾지 못했다").isPositive();
        httpsServer = conf.substring(https);
    }

    @Test
    @DisplayName("재현: 진단 API 는 밖에서 403 이다 — 백엔드로 넘기지 않는다")
    void diagnosticsIsBlockedAtTheEdge() {
        String body = locationBody("/api/diagnostics");
        assertThat(body).as("location /api/diagnostics 블록").isNotNull();
        assertThat(body).contains("return 403;").doesNotContain("proxy_pass");
    }

    @Test
    @DisplayName("재현: python API(/api/v2)는 밖에서 403 이다 — python 으로 넘기지 않는다")
    void pythonApiIsBlockedAtTheEdge() {
        String body = locationBody("/api/v2");
        assertThat(body).as("location /api/v2 블록").isNotNull();
        assertThat(body).contains("return 403;").doesNotContain("proxy_pass");
    }

    @Test
    @DisplayName("밖에서 python 으로 가는 프록시가 어디에도 남아 있지 않다")
    void nothingProxiesToPythonFromOutside() {
        assertThat(httpsServer).doesNotContain("python-backend:8000");
    }

    @Test
    @DisplayName("일반 API 는 그대로 백엔드로 간다")
    void generalApiIsStillProxied() {
        String body = locationBody("/api/");
        assertThat(body).as("location /api/ 블록").isNotNull();
        assertThat(body).contains("proxy_pass $upstream_backend;");
    }

    /** {@code location <prefix> { ... }} 의 본문. 수식어(=, ~, ^~)가 붙은 블록은 보지 않는다. 없으면 null. */
    private static String locationBody(String prefix) {
        Matcher m = Pattern.compile("(?m)^ *location +" + Pattern.quote(prefix) + " *[{]").matcher(httpsServer);
        if (!m.find()) {
            return null;
        }
        int depth = 1;
        int i = m.end();
        while (i < httpsServer.length() && depth > 0) {
            char c = httpsServer.charAt(i++);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return depth == 0 ? httpsServer.substring(m.end(), i - 1) : null;
    }
}
