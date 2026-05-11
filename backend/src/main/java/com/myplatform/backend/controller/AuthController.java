package com.myplatform.backend.controller;

import com.myplatform.backend.dto.LoginRequest;
import com.myplatform.backend.dto.LoginResponse;
import com.myplatform.backend.dto.SignupRequest;
import com.myplatform.backend.dto.SignupResponse;
import com.myplatform.backend.service.AuthService;
import com.myplatform.backend.service.EmailVerificationService;
import com.myplatform.core.dto.ApiResponse;
import com.myplatform.core.util.ApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

    @Operation(summary = "로그인", description = "사용자 로그인을 수행하고 access + refresh JWT 토큰을 발급합니다.")
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "토큰 갱신",
            description = "Refresh Token 으로 새 Access/Refresh Token 발급. 매번 RT 도 회전(rotation)되므로 응답의 새 RT 로 교체 저장 필요.")
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.badRequest().body(new LoginResponse(false, "refreshToken 이 누락되었습니다."));
        }
        return ResponseEntity.ok(authService.refresh(refreshToken));
    }

    @Operation(summary = "회원가입", description = "새로운 사용자를 등록합니다. 관리자 승인 후 로그인할 수 있습니다.")
    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        SignupResponse response = authService.signup(request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "이메일 인증번호 발송", description = "회원가입을 위한 이메일 인증번호를 발송합니다.")
    @PostMapping("/send-verification")
    public ResponseEntity<ApiResponse<String>> sendVerification(@RequestBody Map<String, String> request) {
        try {
            String email = request.get("email");
            if (email == null || email.trim().isEmpty()) {
                return ApiResponses.error(HttpStatus.BAD_REQUEST, "이메일을 입력해주세요.");
            }

            emailVerificationService.sendVerificationToken(email);
            return ResponseEntity.ok(ApiResponse.success("인증번호가 이메일로 발송되었습니다.", null));
        } catch (Exception e) {
            return ApiResponses.error(e, "이메일 발송에 실패했습니다: ");
        }
    }

    @Operation(summary = "이메일 인증번호 확인", description = "발송된 인증번호를 확인합니다.")
    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<String>> verifyEmail(@RequestBody Map<String, String> request) {
        try {
            String email = request.get("email");
            String token = request.get("token");

            if (email == null || token == null) {
                return ApiResponses.error(HttpStatus.BAD_REQUEST, "이메일과 인증번호를 모두 입력해주세요.");
            }

            boolean isVerified = emailVerificationService.verifyToken(email, token);
            if (isVerified) {
                return ResponseEntity.ok(ApiResponse.success("이메일 인증이 완료되었습니다.", null));
            } else {
                return ApiResponses.error(HttpStatus.BAD_REQUEST, "인증번호가 올바르지 않거나 만료되었습니다.");
            }
        } catch (Exception e) {
            return ApiResponses.error(e, "인증 확인에 실패했습니다: ");
        }
    }
}

