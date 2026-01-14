package com.myplatform.backend.config;

import org.springframework.context.annotation.Configuration;
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

                        // API 요청은 제외
                        if (resourcePath.startsWith("api/")) {
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