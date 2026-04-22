package com.myplatform.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * WebAuthn 설정. application.yml 의 webauthn.* 또는 env var WEBAUTHN_*
 *   webauthn.rp-id      = dhkim-lab.duckdns.org
 *   webauthn.rp-name    = MyPlatform
 *   webauthn.origin     = https://dhkim-lab.duckdns.org
 *   webauthn.timeout-ms = 60000
 */
@Configuration
@ConfigurationProperties(prefix = "webauthn")
public class WebauthnProperties {

    private String rpId = "localhost";
    private String rpName = "MyPlatform";
    private String origin = "http://localhost:5173";
    private long timeoutMs = 60_000L;

    public String getRpId() { return rpId; }
    public void setRpId(String rpId) { this.rpId = rpId; }
    public String getRpName() { return rpName; }
    public void setRpName(String rpName) { this.rpName = rpName; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
}
