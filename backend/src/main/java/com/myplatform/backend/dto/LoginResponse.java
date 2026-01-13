package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 응답")
public class LoginResponse {

    @Schema(description = "성공 여부", example = "true")
    private boolean success;

    @Schema(description = "응답 메시지", example = "로그인 성공")
    private String message;

    @Schema(description = "JWT 토큰", example = "eyJhbGciOiJIUzUxMiJ9...")
    private String token;

    @Schema(description = "사용자 아이디", example = "admin")
    private String username;

    @Schema(description = "사용자 이름", example = "관리자")
    private String name;

    @Schema(description = "사용자 역할", example = "ADMIN")
    private String role;

    public LoginResponse() {
    }

    public LoginResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public LoginResponse(boolean success, String message, String token, String username, String name, String role) {
        this.success = success;
        this.message = message;
        this.token = token;
        this.username = username;
        this.name = name;
        this.role = role;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}

