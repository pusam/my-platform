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
                        Resource requestedResource = location.createRelative(resourcePath);

                        // 파일이 진짜 있으면 반환 (index.html, favicon.ico 등)
                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
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

                        // 그 외(프론트엔드 라우트 경로)는 index.html 반환
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}