package com.myplatform.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 헬스체크는 앱 자신만 본다 — 메일 지시자는 끈다(2026-11-04).
 *
 * <p>켜져 있으면 컨테이너 헬스체크(30초)마다 Gmail SMTP 에 접속·로그인한다. 11/3 23:56 22초, 11/4 06:52 11초가
 * 걸려 헬스체크 시한(10초)을 넘겼고, 메일이 막히면 앱이 멀쩡해도 unhealthy·배포 확인 실패로 보인다.
 */
class ActuatorMailHealthOffTest {

    @Test
    @DisplayName("management.health.mail.enabled 는 false")
    void mailHealthIndicatorIsOff() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        assertThat(sources)
                .extracting(s -> String.valueOf(s.getProperty("management.health.mail.enabled")))
                .contains("false");
    }
}
