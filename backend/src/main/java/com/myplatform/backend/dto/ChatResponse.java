package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "AI 채팅 응답")
public class ChatResponse {

    @Schema(description = "AI 응답 메시지")
    private String message;

    @Schema(description = "응답 성공 여부")
    private boolean success;

    public ChatResponse() {}

    public ChatResponse(String message, boolean success) {
        this.message = message;
        this.success = success;
    }

    public static ChatResponse success(String message) {
        return new ChatResponse(message, true);
    }

    public static ChatResponse error(String message) {
        return new ChatResponse(message, false);
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }
}
