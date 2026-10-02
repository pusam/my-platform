package com.myplatform.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 백엔드 정적 파일·SPA 폴백 — {@link WebMvcConfig#resolveSpaResource}(2026-10-02).
 *
 * <p>재현: 운영 jar 는 CI 가 {@code -PskipFrontend} 로 만들어 프론트 빌드가 없다(화면은 nginx 가 서빙). 백엔드에 추적돼 있던
 * 3월 빌드({@code src/main/resources/static})를 지우면 {@code static/index.html} 이 없어지는데, 예전 폴백은 존재하지 않는
 * index.html 을 그대로 돌려줘 응답을 쓰다 500 이 났다. 없으면 null(404)이어야 한다.
 */
class WebMvcConfigSpaFallbackTest {

    private final Resource present = new ByteArrayResource("<html></html>".getBytes());
    private final Resource missing = new ClassPathResource("/static/__does_not_exist__.html");

    @Test
    @DisplayName("프론트 빌드가 없는 jar 에서 라우트 경로는 404(null) — 없는 index.html 을 돌려 500 을 내지 않는다")
    void routeWithoutFrontendBuildIs404() {
        assertThat(WebMvcConfig.resolveSpaResource("stock-dashboard", missing, missing)).isNull();
    }

    @Test
    @DisplayName("프론트 빌드가 들어 있으면(로컬 단일 jar) 라우트 경로는 index.html")
    void routeWithFrontendBuildServesIndex() {
        assertThat(WebMvcConfig.resolveSpaResource("stock-dashboard", missing, present)).isSameAs(present);
    }

    @Test
    @DisplayName("요청한 파일이 실제로 있으면 그 파일")
    void existingFileIsServed() {
        Resource favicon = new ByteArrayResource(new byte[] {1});

        assertThat(WebMvcConfig.resolveSpaResource("favicon.ico", favicon, present)).isSameAs(favicon);
    }

    @Test
    @DisplayName("API·actuator·문서·assets 는 index.html 로 폴백하지 않는다(종전 규칙 그대로)")
    void nonRoutePathsNeverFallBack() {
        for (String p : new String[] {"api/x", "actuator/health", "v3/api-docs", "swagger-ui/index.html", "assets/app.js"}) {
            assertThat(WebMvcConfig.resolveSpaResource(p, missing, present)).as(p).isNull();
        }
    }
}
