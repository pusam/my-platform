package com.myplatform.backend.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.myplatform.backend.entity.PasswordResetToken;
import com.myplatform.backend.entity.User;
import com.myplatform.backend.repository.PasswordResetTokenRepository;
import com.myplatform.backend.repository.UserRepository;
import com.myplatform.backend.util.PasswordPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Transactional
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    // 이메일별 verifyToken 시도 횟수 — 5회 실패 시 토큰 강제 invalidate (브루트포스 차단)
    private static final int MAX_VERIFY_ATTEMPTS = 5;
    private final Cache<String, AtomicInteger> verifyAttempts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(10_000)
            .build();

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository tokenRepository,
            EmailService emailService,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 비밀번호 재설정 인증번호 발송
     */
    public void sendResetToken(String username, String email) {
        // 아이디와 이메일로 사용자 확인
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("존재하지 않는 아이디입니다."));

        if (!email.equals(user.getEmail())) {
            throw new RuntimeException("아이디와 이메일이 일치하지 않습니다.");
        }

        // 기존 토큰 삭제
        tokenRepository.deleteByEmail(email);

        // 6자리 인증번호 생성
        String token = generateToken();

        // 토큰 저장 (10분 유효)
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setEmail(email);
        resetToken.setToken(token);
        resetToken.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        resetToken.setUsed(false);
        tokenRepository.save(resetToken);

        // 이메일 발송
        emailService.sendPasswordResetEmail(email, token);
    }

    /**
     * 인증번호 확인 — 5회 실패 시 토큰 자체 invalidate (1M 조합 brute force 차단)
     */
    public boolean verifyToken(String email, String token) {
        boolean valid = tokenRepository.findByEmailAndTokenAndUsedFalseAndExpiresAtAfter(
                email, token, LocalDateTime.now()
        ).isPresent();

        if (!valid) {
            AtomicInteger count = verifyAttempts.get(email, k -> new AtomicInteger(0));
            if (count.incrementAndGet() >= MAX_VERIFY_ATTEMPTS) {
                // N회 연속 실패 → 해당 이메일의 모든 토큰 사용 처리하여 강제 invalidate
                tokenRepository.deleteByEmail(email);
                verifyAttempts.invalidate(email);
            }
        } else {
            verifyAttempts.invalidate(email);
        }
        return valid;
    }

    /**
     * 비밀번호 재설정
     */
    public void resetPassword(String username, String email, String token, String newPassword) {
        // 토큰 확인
        PasswordResetToken resetToken = tokenRepository
                .findByEmailAndTokenAndUsedFalseAndExpiresAtAfter(
                        email, token, LocalDateTime.now()
                )
                .orElseThrow(() -> new RuntimeException("유효하지 않거나 만료된 인증번호입니다."));

        // 사용자 확인 (아이디 + 이메일)
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("존재하지 않는 아이디입니다."));

        if (!email.equals(user.getEmail())) {
            throw new RuntimeException("아이디와 이메일이 일치하지 않습니다.");
        }

        // 비밀번호 정책 검증
        String pwError = PasswordPolicy.validate(newPassword, username, email);
        if (pwError != null) {
            throw new RuntimeException(pwError);
        }

        // 이전 비밀번호와 동일 여부 차단
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new RuntimeException("이전 비밀번호와 동일한 비밀번호로 변경할 수 없습니다.");
        }

        // 비밀번호 변경 + 잠긴 계정/실패 카운터 리셋
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setFailedLoginAttempts(0);
        if ("LOCKED".equals(user.getStatus())) {
            user.setStatus("APPROVED");
        }
        userRepository.save(user);

        // 토큰 사용 처리
        resetToken.setUsed(true);
        tokenRepository.save(resetToken);
    }

    /**
     * 6자리 랜덤 인증번호 생성
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private String generateToken() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1000000));
    }

    /**
     * 만료된 토큰 삭제 (스케줄러로 주기적 실행 가능)
     */
    public void deleteExpiredTokens() {
        tokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
    }
}

