package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회원가입 응답")
public class SignupResponse {

    @Schema(description = "성공 여부")
    private boolean success;

    @Schema(description = "메시지")
    private String message;

    public SignupResponse() {
    }

    public SignupResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
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
}

