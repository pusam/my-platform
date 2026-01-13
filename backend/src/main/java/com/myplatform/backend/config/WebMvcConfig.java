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
        // Vue.js SPA를 위한 설정
        // 모든 경로(API 제외)는 index.html로 포워딩하여 Vue Router가 처리하도록 함
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requestedResource = location.createRelative(resourcePath);

                        // 실제 파일이 존재하면 반환
                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
                        }

                        // API 요청이면 null 반환 (Spring Security가 처리)
                        if (resourcePath.startsWith("api/")) {
                            return null;
                        }

                        // 그 외의 경우 index.html 반환 (Vue Router가 처리)
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}

