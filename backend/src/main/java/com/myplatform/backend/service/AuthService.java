package com.myplatform.backend.service;

import com.myplatform.backend.dto.LoginRequest;
import com.myplatform.backend.dto.LoginResponse;
import com.myplatform.backend.dto.SignupRequest;
import com.myplatform.backend.dto.SignupResponse;
import com.myplatform.backend.entity.User;
import com.myplatform.backend.repository.UserRepository;
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

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElse(null);

        if (user == null) {
            return new LoginResponse(false, "존재하지 않는 사용자입니다.");
        }

        // 승인되지 않은 사용자 체크
        if (!"APPROVED".equals(user.getStatus())) {
            if ("PENDING".equals(user.getStatus())) {
                return new LoginResponse(false, "관리자 승인 대기 중입니다.");
            } else if ("REJECTED".equals(user.getStatus())) {
                return new LoginResponse(false, "가입이 거부되었습니다. 관리자에게 문의하세요.");
            }
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            return new LoginResponse(false, "비밀번호가 일치하지 않습니다.");
        }

        String token = jwtTokenProvider.generateToken(user.getUsername());

        // Redis가 있으면 토큰 저장
        redisTokenService.ifPresent(service ->
            service.saveToken(user.getUsername(), token, jwtTokenProvider.getExpirationTime())
        );

        return new LoginResponse(true, "로그인 성공", token, user.getUsername(), user.getName(), user.getRole());
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

        // 3. 비밀번호 길이 검증
        if (request.getPassword().length() < 4) {
            return new SignupResponse(false, "비밀번호는 최소 4자 이상이어야 합니다.");
        }

        // 4. 이메일 형식 검증
        if (!request.getEmail().matches("^[A-Za-z0-9+_.-]+@(.+)$")) {
            return new SignupResponse(false, "올바른 이메일 형식이 아닙니다.");
        }

        // 5. 핸드폰번호 형식 검증
        if (!request.getPhone().matches("^01[0-9]-[0-9]{4}-[0-9]{4}$")) {
            return new SignupResponse(false, "핸드폰번호 형식이 올바르지 않습니다. (예: 010-1234-5678)");
        }

        // 6. 이메일 인증 확인
        boolean isVerified = emailVerificationService.verifyToken(
            request.getEmail(),
            request.getVerificationToken()
        );
        if (!isVerified) {
            return new SignupResponse(false, "이메일 인증번호가 올바르지 않거나 만료되었습니다.");
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
        // Redis가 있으면 토큰 삭제
        redisTokenService.ifPresent(service -> service.deleteToken(username));
    }
}

