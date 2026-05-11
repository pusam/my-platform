package com.myplatform.backend.controller;

import com.myplatform.backend.dto.LoginResponse;
import com.myplatform.backend.dto.webauthn.AuthenticationOptionsResponse;
import com.myplatform.backend.dto.webauthn.AuthenticationVerifyRequest;
import com.myplatform.backend.dto.webauthn.RegisteredCredentialDto;
import com.myplatform.backend.dto.webauthn.RegistrationOptionsResponse;
import com.myplatform.backend.dto.webauthn.RegistrationVerifyRequest;
import com.myplatform.backend.entity.User;
import com.myplatform.backend.repository.UserRepository;
import com.myplatform.backend.service.WebauthnService;
import com.myplatform.core.dto.ApiResponse;
import com.myplatform.core.util.ApiResponses;
import com.myplatform.jwtredis.provider.JwtTokenProvider;
import com.myplatform.jwtredis.service.RedisTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Tag(name = "WebAuthn", description = "지문/Face ID/패스키 인증")
@RestController
@RequestMapping("/api/webauthn")
public class WebauthnController {

    private final WebauthnService webauthnService;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final Optional<RedisTokenService> redisTokenService;

    public WebauthnController(WebauthnService webauthnService,
                              UserRepository userRepository,
                              JwtTokenProvider jwtTokenProvider,
                              Optional<RedisTokenService> redisTokenService) {
        this.webauthnService = webauthnService;
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.redisTokenService = redisTokenService;
    }

    // ---- 등록 (인증 필요) ----

    @Operation(summary = "등록 options 발급", description = "로그인한 사용자의 현재 기기를 자격증명으로 등록하기 위한 options")
    @PostMapping("/register/options")
    public ResponseEntity<ApiResponse<RegistrationOptionsResponse>> registerOptions() {
        User user = currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(webauthnService.startRegistration(user)));
    }

    @Operation(summary = "등록 검증", description = "브라우저가 생성한 attestation 을 검증하고 credential 저장")
    @PostMapping("/register/verify")
    public ResponseEntity<ApiResponse<RegisteredCredentialDto>> registerVerify(
            @RequestBody RegistrationVerifyRequest req) {
        User user = currentUserOrThrow();
        try {
            RegisteredCredentialDto dto = webauthnService.finishRegistration(user, req);
            return ResponseEntity.ok(ApiResponse.success("지문/생체인증 등록 완료", dto));
        } catch (IllegalStateException e) {
            return ApiResponses.error(e, "");
        }
    }

    // ---- 로그인 (비로그인 상태에서 호출 가능) ----

    @Operation(summary = "로그인 options 발급")
    @PostMapping("/login/options")
    public ResponseEntity<ApiResponse<AuthenticationOptionsResponse>> loginOptions(
            @RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        if (username.isEmpty()) {
            return ApiResponses.error(HttpStatus.BAD_REQUEST, "아이디를 입력해주세요.");
        }
        return ResponseEntity.ok(ApiResponse.success(webauthnService.startAuthentication(username)));
    }

    @Operation(summary = "로그인 검증", description = "생체인증 결과 검증 후 JWT 발급")
    @PostMapping("/login/verify")
    public ResponseEntity<LoginResponse> loginVerify(
            @RequestBody Map<String, Object> body) {
        try {
            String username = (String) body.get("username");
            if (username == null || username.isBlank()) {
                return ResponseEntity.ok(new LoginResponse(false, "아이디가 없습니다."));
            }
            AuthenticationVerifyRequest credential = mapToVerifyRequest(body);
            User user = webauthnService.finishAuthentication(username, credential);
            return issueLoginResponse(user);
        } catch (IllegalStateException e) {
            return ResponseEntity.ok(new LoginResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(new LoginResponse(false, "인증 처리 중 오류: " + e.getMessage()));
        }
    }

    @Operation(summary = "패스키 로그인 options", description = "사용자명 없이 OS 패스키 picker 트리거")
    @PostMapping("/passkey/options")
    public ResponseEntity<ApiResponse<AuthenticationOptionsResponse>> passkeyOptions() {
        return ResponseEntity.ok(ApiResponse.success(webauthnService.startPasskeyAuthentication()));
    }

    @Operation(summary = "패스키 로그인 검증")
    @PostMapping("/passkey/verify")
    public ResponseEntity<LoginResponse> passkeyVerify(@RequestBody Map<String, Object> body) {
        try {
            AuthenticationVerifyRequest credential = mapToVerifyRequest(body);
            User user = webauthnService.finishPasskeyAuthentication(credential);
            return issueLoginResponse(user);
        } catch (IllegalStateException e) {
            return ResponseEntity.ok(new LoginResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(new LoginResponse(false, "인증 처리 중 오류: " + e.getMessage()));
        }
    }

    private ResponseEntity<LoginResponse> issueLoginResponse(User user) {
        if (!"APPROVED".equals(user.getStatus())) {
            return ResponseEntity.ok(new LoginResponse(false, "승인되지 않은 계정입니다."));
        }
        String token = jwtTokenProvider.generateToken(user.getUsername());
        redisTokenService.ifPresent(s ->
                s.saveToken(user.getUsername(), token, jwtTokenProvider.getExpirationTime()));
        return ResponseEntity.ok(new LoginResponse(true, "로그인 성공",
                token, user.getUsername(), user.getName(), user.getRole()));
    }

    // ---- 등록된 credential 관리 (인증 필요) ----

    @Operation(summary = "등록된 자격증명 목록")
    @GetMapping("/credentials")
    public ResponseEntity<ApiResponse<List<RegisteredCredentialDto>>> listCredentials() {
        User user = currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(webauthnService.listCredentials(user.getId())));
    }

    @Operation(summary = "자격증명 삭제")
    @DeleteMapping("/credentials/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCredential(@PathVariable Long id) {
        User user = currentUserOrThrow();
        webauthnService.deleteCredential(user.getId(), id);
        return ResponseEntity.ok(ApiResponse.success("삭제되었습니다.", null));
    }

    // ---- 내부 헬퍼 ----

    private User currentUserOrThrow() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new IllegalStateException("로그인이 필요합니다.");
        }
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다."));
    }

    @SuppressWarnings("unchecked")
    private AuthenticationVerifyRequest mapToVerifyRequest(Map<String, Object> body) {
        Map<String, Object> resp = (Map<String, Object>) body.get("response");
        AuthenticationVerifyRequest.Response response = new AuthenticationVerifyRequest.Response(
                (String) resp.get("clientDataJSON"),
                (String) resp.get("authenticatorData"),
                (String) resp.get("signature"),
                (String) resp.get("userHandle")
        );
        return new AuthenticationVerifyRequest(
                (String) body.get("id"),
                (String) body.get("rawId"),
                (String) body.get("type"),
                response
        );
    }
}
