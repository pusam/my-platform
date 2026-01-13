package com.myplatform.backend.controller;

import com.myplatform.backend.dto.*;
import com.myplatform.backend.service.PasswordResetService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Password Reset", description = "비밀번호 재설정 API")
@RestController
@RequestMapping("/api/password")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    @Operation(summary = "비밀번호 재설정 인증번호 발송", description = "아이디와 이메일로 6자리 인증번호를 발송합니다.")
    @PostMapping("/reset/send")
    public ResponseEntity<ApiResponse<String>> sendResetToken(@RequestBody PasswordResetRequest request) {
        try {
            passwordResetService.sendResetToken(request.getUsername(), request.getEmail());
            return ResponseEntity.ok(ApiResponse.success("인증번호가 이메일로 발송되었습니다.", null));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail(e.getMessage()));
        }
    }

    @Operation(summary = "인증번호 확인", description = "이메일로 받은 인증번호를 확인합니다.")
    @PostMapping("/reset/verify")
    public ResponseEntity<ApiResponse<String>> verifyToken(@RequestBody VerifyTokenRequest request) {
        try {
            boolean valid = passwordResetService.verifyToken(request.getEmail(), request.getToken());
            if (valid) {
                return ResponseEntity.ok(ApiResponse.success("인증번호가 확인되었습니다.", null));
            } else {
                return ResponseEntity.ok(ApiResponse.fail("유효하지 않거나 만료된 인증번호입니다."));
            }
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail(e.getMessage()));
        }
    }

    @Operation(summary = "비밀번호 변경", description = "인증번호 확인 후 비밀번호를 변경합니다.")
    @PostMapping("/reset/confirm")
    public ResponseEntity<ApiResponse<String>> resetPassword(@RequestBody ResetPasswordRequest request) {
        try {
            passwordResetService.resetPassword(request.getUsername(), request.getEmail(), request.getToken(), request.getNewPassword());
            return ResponseEntity.ok(ApiResponse.success("비밀번호가 변경되었습니다.", null));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail(e.getMessage()));
        }
    }
}

