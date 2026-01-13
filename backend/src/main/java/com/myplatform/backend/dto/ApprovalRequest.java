package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "승인 처리 요청")
public class ApprovalRequest {

    @Schema(description = "승인 상태 (APPROVED, REJECTED)", example = "APPROVED")
    private String status;

    @Schema(description = "거부 사유 (선택사항)", example = "")
    private String reason;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}

