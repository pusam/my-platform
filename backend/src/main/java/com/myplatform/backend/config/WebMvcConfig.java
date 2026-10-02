package com.myplatform.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // ResourceHandler 가 actuator endpoint / 컨트롤러 매핑을 가로채지 않도록 가장 낮은 우선순위로 명시.
        // 운영 사고: /actuator/health 요청이 ResourceHttpRequestHandler 에 먼저 잡혀
        // NoResourceFoundException 발생 → GlobalExceptionHandler 가 500 으로 응답.
        registry.setOrder(Ordered.LOWEST_PRECEDENCE);

        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCachePeriod(3600)
                .resourceChain(true);

        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        return resolveSpaResource(resourcePath, location.createRelative(resourcePath),
                                new ClassPathResource("/static/index.html"));
                    }
                });
    }

    /**
     * 백엔드가 직접 받은 비-API 경로의 정적 파일·SPA 폴백 판정(순수).
     *
     * <p>⚠ 운영 화면은 백엔드가 아니라 nginx 가 {@code frontend/dist} 로 서빙한다 — 운영 jar 는 CI 가 {@code -PskipFrontend} 로
     * 만들어 정적 파일이 없다(2026-10-02 까지는 3월 빌드가 {@code src/main/resources/static} 에 추적돼 jar 에 실려 있었다).
     * 로컬 단일 jar({@code -PskipFrontend} 없이 빌드)만 {@code copyFrontend} 가 새 빌드를 넣는다.
     * 그래서 index.html 이 없을 수 있다 — 없으면 null(404). 존재하지 않는 리소스를 돌려주면 응답 쓰기에서 500 이 난다.
     *
     * @return 돌려줄 리소스, 없으면 null(404)
     */
    static Resource resolveSpaResource(String resourcePath, Resource requested, Resource index) {
        // 파일이 진짜 있으면 반환 (index.html, favicon.ico 등)
        if (requested != null && requested.exists() && requested.isReadable()) {
            return requested;
        }

        // API / actuator / docs 요청은 SPA fallback 안 됨.
        // 이전: /actuator/health 도 index.html HTML 반환 → health check 무용지물.
        if (resourcePath.startsWith("api/")
                || resourcePath.startsWith("actuator/")
                || resourcePath.startsWith("v3/api-docs")
                || resourcePath.startsWith("swagger-ui")) {
            return null;
        }

        // /assets/ 로 시작하는 요청이 여기까지 왔다면 파일이 없는 것임 -> null 반환 (404 발생)
        // 이렇게 해야 브라우저가 html을 css로 착각하지 않음
        if (resourcePath.startsWith("assets/")) {
            return null;
        }

        // 그 외(프론트엔드 라우트 경로)는 index.html — 프론트 빌드가 들어 있을 때만
        return index != null && index.exists() && index.isReadable() ? index : null;
    }
}