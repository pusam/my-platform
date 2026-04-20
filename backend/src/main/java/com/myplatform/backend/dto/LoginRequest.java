package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "로그인 요청")
public class LoginRequest {

    @Schema(description = "사용자 아이디", example = "admin", required = true)
    @NotBlank(message = "아이디는 필수입니다")
    @Size(max = 50, message = "아이디는 50자 이하여야 합니다")
    private String username;

    @Schema(description = "비밀번호", example = "admintest", required = true)
    @NotBlank(message = "비밀번호는 필수입니다")
    @Size(max = 100, message = "비밀번호는 100자 이하여야 합니다")
    private String password;

    public LoginRequest() {
    }

    public LoginRequest(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
