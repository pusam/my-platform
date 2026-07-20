package com.myplatform.backend.service;

import com.myplatform.backend.dto.LoginRequest;
import com.myplatform.backend.dto.LoginResponse;
import com.myplatform.backend.dto.SignupRequest;
import com.myplatform.backend.dto.SignupResponse;
import com.myplatform.backend.entity.User;
import com.myplatform.backend.repository.UserRepository;
import com.myplatform.backend.util.PasswordPolicy;
import com.myplatform.jwtredis.provider.JwtTokenProvider;
import com.myplatform.jwtredis.service.RedisTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final Optional<RedisTokenService> redisTokenService;
    private final EmailVerificationService emailVerificationService;

    public AuthService(UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      JwtTokenProvider jwtTokenProvider,
                      @Autowired(required = false) RedisTokenService redisTokenService,
                      EmailVerificationService emailVerificationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.redisTokenService = Optional.ofNullable(redisTokenService);
        this.emailVerificationService = emailVerificationService;
    }

    private static final int MAX_FAILED_ATTEMPTS = 10;
    private static final String INVALID_CREDENTIALS_MESSAGE = "아이디 또는 비밀번호가 올바르지 않습니다.";

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElse(null);

        // 계정 열거(account enumeration) 방지: 사용자 부재와 비밀번호 불일치를 동일 메시지로 응답
        if (user == null) {
            return new LoginResponse(false, INVALID_CREDENTIALS_MESSAGE);
        }

        // 계정 잠금 체크
        if ("LOCKED".equals(user.getStatus())) {
            return new LoginResponse(false, "계정이 잠겼습니다. 관리자에게 문의하세요.");
        }

        // 승인되지 않은 사용자 체크 — APPROVED 화이트리스트. PENDING/REJECTED 외의 상태
        // (관리자 임의 정지 문자열, null 레거시 등)도 전부 거부해야 접근제어가 fail-closed.
        if (!"APPROVED".equals(user.getStatus())) {
            if ("PENDING".equals(user.getStatus())) {
                return new LoginResponse(false, "관리자 승인 대기 중입니다.");
            } else if ("REJECTED".equals(user.getStatus())) {
                return new LoginResponse(false, "가입이 거부되었습니다. 관리자에게 문의하세요.");
            }
            return new LoginResponse(false, "로그인할 수 없는 계정 상태입니다. 관리자에게 문의하세요.");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            // 로그인 실패 횟수 증가
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                user.setStatus("LOCKED");
                userRepository.save(user);
                return new LoginResponse(false, "로그인 " + MAX_FAILED_ATTEMPTS + "회 실패로 계정이 잠겼습니다. 관리자에게 문의하세요.");
            }
            userRepository.save(user);
            // 시도 횟수 노출도 계정 존재 여부 누설로 이어짐 → 동일 메시지로 통합
            return new LoginResponse(false, INVALID_CREDENTIALS_MESSAGE);
        }

        // 로그인 성공 시 실패 횟수 초기화
        if (user.getFailedLoginAttempts() > 0) {
            user.setFailedLoginAttempts(0);
            userRepository.save(user);
        }

        String accessToken = jwtTokenProvider.generateAccessToken(user.getUsername());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getUsername());

        // Redis가 있으면 AT/RT 둘 다 저장 — RT 는 회전(rotation) 시 매칭 검증용.
        redisTokenService.ifPresent(service -> {
            service.saveToken(user.getUsername(), accessToken, jwtTokenProvider.getAccessExpiration());
            service.saveRefreshToken(user.getUsername(), refreshToken, jwtTokenProvider.getRefreshExpiration());
        });

        return new LoginResponse(true, "로그인 성공", accessToken, refreshToken,
                user.getUsername(), user.getName(), user.getRole());
    }

    /**
     * Refresh Token 으로 새 Access/Refresh Token 발급.
     * - RT 검증: 서명·만료·type=REFRESH 모두 통과해야 함
     * - Redis 매칭: 저장된 RT 와 일치해야 함 (회전 후 옛 RT 재사용 차단)
     * - 매칭 실패 시 모든 토큰 무효화 (탈취 의심 — 안전한 쪽)
     * - 성공 시 새 AT + 새 RT 동시 발급 (rotation)
     */
    public LoginResponse refresh(String refreshToken) {
        if (refreshToken == null || !jwtTokenProvider.validateRefreshToken(refreshToken)) {
            return new LoginResponse(false, "유효하지 않은 refresh token 입니다. 다시 로그인 해주세요.");
        }

        String username = jwtTokenProvider.getUsernameFromToken(refreshToken);

        // Redis 서비스가 있으면 저장된 RT 와 일치해야만 갱신 — storedRt=null(로그아웃 삭제·만료·Redis 블립)도
        // 거부해야 logout() 의 revocation 이 실제로 동작한다(이전 "null=허용"은 로그아웃 후 옛 RT 재인증 구멍).
        // Redis 장애 중엔 refresh 가 막혀 재로그인이 필요해지는 트레이드오프 — 보안 우선(개인 플랫폼, 드문 케이스).
        // Redis 서비스 빈 자체가 없는 환경(개발)만 RT 자체 검증으로 통과.
        if (redisTokenService.isPresent()) {
            String storedRt = redisTokenService.get().getRefreshToken(username);
            if (!refreshToken.equals(storedRt)) {
                redisTokenService.ifPresent(service -> {
                    service.deleteToken(username);
                    service.deleteRefreshToken(username);
                });
                return new LoginResponse(false, "refresh token 이 갱신되었습니다. 다시 로그인 해주세요.");
            }
        }

        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !"APPROVED".equals(user.getStatus()) || "LOCKED".equals(user.getStatus())) {
            redisTokenService.ifPresent(service -> {
                service.deleteToken(username);
                service.deleteRefreshToken(username);
            });
            return new LoginResponse(false, "계정 상태가 변경되었습니다. 다시 로그인 해주세요.");
        }

        String newAccessToken = jwtTokenProvider.generateAccessToken(username);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(username);
        redisTokenService.ifPresent(service -> {
            service.saveToken(username, newAccessToken, jwtTokenProvider.getAccessExpiration());
            service.saveRefreshToken(username, newRefreshToken, jwtTokenProvider.getRefreshExpiration());
        });

        return new LoginResponse(true, "토큰 갱신 성공", newAccessToken, newRefreshToken,
                username, user.getName(), user.getRole());
    }

    public SignupResponse signup(SignupRequest request) {
        // 1. 필수 입력 필드 검증
        if (request.getUsername() == null || request.getUsername().trim().isEmpty()) {
            return new SignupResponse(false, "아이디를 입력해주세요.");
        }
        if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            return new SignupResponse(false, "비밀번호를 입력해주세요.");
        }
        if (request.getPasswordConfirm() == null || request.getPasswordConfirm().trim().isEmpty()) {
            return new SignupResponse(false, "비밀번호 확인을 입력해주세요.");
        }
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            return new SignupResponse(false, "이름을 입력해주세요.");
        }
        if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
            return new SignupResponse(false, "이메일을 입력해주세요.");
        }
        if (request.getPhone() == null || request.getPhone().trim().isEmpty()) {
            return new SignupResponse(false, "핸드폰번호를 입력해주세요.");
        }
        if (request.getVerificationToken() == null || request.getVerificationToken().trim().isEmpty()) {
            return new SignupResponse(false, "이메일 인증번호를 입력해주세요.");
        }

        // 2. 비밀번호 일치 검증
        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            return new SignupResponse(false, "비밀번호가 일치하지 않습니다. 다시 확인해주세요.");
        }

        // 3. 비밀번호 정책 검증 (12자 이상 + 대소문자/숫자/특수문자 + 아이디/이메일 포함 금지)
        String pwError = PasswordPolicy.validate(request.getPassword(), request.getUsername(), request.getEmail());
        if (pwError != null) {
            return new SignupResponse(false, pwError);
        }

        // 4. 이메일 형식 검증 (도메인 부분에 최소 1글자.1글자 필요)
        if (!request.getEmail().matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")) {
            return new SignupResponse(false, "올바른 이메일 형식이 아닙니다.");
        }

        // 5. 핸드폰번호 형식 검증
        if (!request.getPhone().matches("^01[0-9]-[0-9]{4}-[0-9]{4}$")) {
            return new SignupResponse(false, "핸드폰번호 형식이 올바르지 않습니다. (예: 010-1234-5678)");
        }

        // 6. 이메일 인증 확인 (verify-email API에서 이미 인증 완료된 상태 확인)
        if (!emailVerificationService.isEmailVerified(request.getEmail())) {
            return new SignupResponse(false, "이메일 인증이 완료되지 않았습니다. 인증번호를 먼저 확인해주세요.");
        }

        // 7. 중복 체크
        if (userRepository.existsByUsername(request.getUsername())) {
            return new SignupResponse(false, "이미 사용 중인 아이디입니다. 다른 아이디를 사용해주세요.");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            return new SignupResponse(false, "이미 가입된 이메일입니다. 다른 이메일을 사용하거나 로그인해주세요.");
        }

        // 8. 사용자 생성
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setRole("USER");
        user.setStatus("PENDING"); // 승인 대기 상태

        userRepository.save(user);

        return new SignupResponse(true, "✅ 회원가입이 완료되었습니다!\n관리자 승인 후 로그인하실 수 있습니다.\n승인 완료 시 이메일로 알림을 보내드리겠습니다.");
    }

    public void logout(String username) {
        // Redis 가 있으면 AT + RT 둘 다 삭제 — 로그아웃 후 옛 RT 로 재인증 차단.
        redisTokenService.ifPresent(service -> {
            service.deleteToken(username);
            service.deleteRefreshToken(username);
        });
    }
}

