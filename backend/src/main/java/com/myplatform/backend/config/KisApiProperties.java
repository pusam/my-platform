package com.myplatform.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 한국투자증권 API 설정
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "kis.api")
public class KisApiProperties {

    private String appKey;
    private String appSecret;
    private String baseUrl;
    private String accountNumber;
    private String accountProductCode;
}
