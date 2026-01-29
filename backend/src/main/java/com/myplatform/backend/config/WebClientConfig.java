package com.myplatform.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 클라이언트 설정
 * - RestTemplate 타임아웃 설정으로 무한 대기 방지
 * - ObjectMapper JSON 직렬화 설정
 */
@Configuration
@Slf4j
public class WebClientConfig {

    private static final int CONNECT_TIMEOUT = 5000;  // 5초 - 연결 타임아웃
    private static final int READ_TIMEOUT = 10000;    // 10초 - 읽기 타임아웃

    /**
     * RestTemplate 빈 설정
     * - Connection Timeout: 5초 (서버 연결까지 대기 시간)
     * - Read Timeout: 10초 (응답 데이터 수신 대기 시간)
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);

        log.info("RestTemplate 초기화 완료 - connectTimeout: {}ms, readTimeout: {}ms",
                CONNECT_TIMEOUT, READ_TIMEOUT);

        return new RestTemplate(factory);
    }

    /**
     * ObjectMapper 빈 설정
     * - Java 8 날짜/시간 타입 지원
     * - 날짜를 timestamp 대신 ISO-8601 문자열로 직렬화
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
