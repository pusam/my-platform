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

    public AuthService(UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      JwtTokenProvider jwtTokenProvider,
                      @Autowired(required = false) RedisTokenService redisTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.redisTokenService = Optional.ofNullable(redisTokenService);
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
        // 유효성 검증
        if (request.getUsername() == null || request.getUsername().trim().isEmpty()) {
            return new SignupResponse(false, "아이디를 입력해주세요.");
        }
        if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            return new SignupResponse(false, "비밀번호를 입력해주세요.");
        }
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            return new SignupResponse(false, "이름을 입력해주세요.");
        }
        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            return new SignupResponse(false, "비밀번호가 일치하지 않습니다.");
        }

        // 중복 체크
        if (userRepository.existsByUsername(request.getUsername())) {
            return new SignupResponse(false, "이미 사용 중인 아이디입니다.");
        }
        if (request.getEmail() != null && !request.getEmail().isEmpty()
                && userRepository.existsByEmail(request.getEmail())) {
            return new SignupResponse(false, "이미 사용 중인 이메일입니다.");
        }

        // 사용자 생성
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setRole("USER");
        user.setStatus("PENDING"); // 승인 대기 상태

        userRepository.save(user);

        return new SignupResponse(true, "회원가입이 완료되었습니다. 관리자 승인 후 로그인할 수 있습니다.");
    }

    public void logout(String username) {
        // Redis가 있으면 토큰 삭제
        redisTokenService.ifPresent(service -> service.deleteToken(username));
    }
}

