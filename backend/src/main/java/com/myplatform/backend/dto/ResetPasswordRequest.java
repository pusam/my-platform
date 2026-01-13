package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "비밀번호 변경 요청")
public class ResetPasswordRequest {

    @Schema(description = "이메일", example = "user@example.com")
    private String email;

    @Schema(description = "인증번호", example = "123456")
    private String token;

    @Schema(description = "새 비밀번호", example = "newpassword123")
    private String newPassword;

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

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
