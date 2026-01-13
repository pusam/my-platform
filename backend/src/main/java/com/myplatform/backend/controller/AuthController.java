package com.myplatform.backend.controller;

import com.myplatform.backend.dto.LoginRequest;
import com.myplatform.backend.dto.LoginResponse;
import com.myplatform.backend.dto.SignupRequest;
import com.myplatform.backend.dto.SignupResponse;
import com.myplatform.backend.service.AuthService;
import com.myplatform.backend.service.EmailVerificationService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "인증", description = "사용자 인증 관련 API")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;

    public AuthController(AuthService authService, EmailVerificationService emailVerificationService) {
        this.authService = authService;
        this.emailVerificationService = emailVerificationService;
    }

    @Operation(summary = "로그인", description = "사용자 로그인을 수행하고 JWT 토큰을 발급합니다.")
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "회원가입", description = "새로운 사용자를 등록합니다. 관리자 승인 후 로그인할 수 있습니다.")
    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@RequestBody SignupRequest request) {
        SignupResponse response = authService.signup(request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "이메일 인증번호 발송", description = "회원가입을 위한 이메일 인증번호를 발송합니다.")
    @PostMapping("/send-verification")
    public ResponseEntity<ApiResponse<String>> sendVerification(@RequestBody Map<String, String> request) {
        try {
            String email = request.get("email");
            if (email == null || email.trim().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.fail("이메일을 입력해주세요."));
            }

            emailVerificationService.sendVerificationToken(email);
            return ResponseEntity.ok(ApiResponse.success("인증번호가 이메일로 발송되었습니다.", null));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("이메일 발송에 실패했습니다: " + e.getMessage()));
        }
    }

    @Operation(summary = "이메일 인증번호 확인", description = "발송된 인증번호를 확인합니다.")
    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<String>> verifyEmail(@RequestBody Map<String, String> request) {
        try {
            String email = request.get("email");
            String token = request.get("token");

            if (email == null || token == null) {
                return ResponseEntity.ok(ApiResponse.fail("이메일과 인증번호를 모두 입력해주세요."));
            }

            boolean isVerified = emailVerificationService.verifyToken(email, token);
            if (isVerified) {
                return ResponseEntity.ok(ApiResponse.success("이메일 인증이 완료되었습니다.", null));
            } else {
                return ResponseEntity.ok(ApiResponse.fail("인증번호가 올바르지 않거나 만료되었습니다."));
            }
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("인증 확인에 실패했습니다: " + e.getMessage()));
        }
    }
}

