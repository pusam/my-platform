package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "인증번호 확인 요청")
public class VerifyTokenRequest {

    @Schema(description = "아이디", example = "testuser")
    private String username;

    @Schema(description = "이메일", example = "user@example.com")
    private String email;

    @Schema(description = "인증번호", example = "123456")
    private String token;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}

