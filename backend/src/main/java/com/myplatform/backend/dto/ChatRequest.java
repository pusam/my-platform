package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "AI 채팅 요청")
public class ChatRequest {

    @Schema(description = "사용자 메시지")
    private String message;

    @Schema(description = "맞춤 상담 여부 (사용자 데이터 활용)")
    private boolean useContext = false;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isUseContext() {
        return useContext;
    }

    public void setUseContext(boolean useContext) {
        this.useContext = useContext;
    }
}
