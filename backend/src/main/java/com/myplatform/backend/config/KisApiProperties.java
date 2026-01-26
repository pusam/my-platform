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

    // Getters and Setters
    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}

