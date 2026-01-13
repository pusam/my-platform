package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "비밀번호 재설정 요청 (인증번호 발송)")
public class PasswordResetRequest {

    @Schema(description = "아이디", example = "testuser")
    private String username;

    @Schema(description = "이메일", example = "user@example.com")
    private String email;

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
}

