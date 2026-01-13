package com.myplatform.backend.service;

import com.myplatform.backend.entity.PasswordResetToken;
import com.myplatform.backend.entity.User;
import com.myplatform.backend.repository.PasswordResetTokenRepository;
import com.myplatform.backend.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Random;

@Service
@Transactional
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

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
    public void sendResetToken(String email) {
        // 이메일로 사용자 확인
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("등록된 이메일이 아닙니다."));

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
     * 인증번호 확인
     */
    public boolean verifyToken(String email, String token) {
        return tokenRepository.findByEmailAndTokenAndUsedFalseAndExpiresAtAfter(
                email, token, LocalDateTime.now()
        ).isPresent();
    }

    /**
     * 비밀번호 재설정
     */
    public void resetPassword(String email, String token, String newPassword) {
        // 토큰 확인
        PasswordResetToken resetToken = tokenRepository
                .findByEmailAndTokenAndUsedFalseAndExpiresAtAfter(
                        email, token, LocalDateTime.now()
                )
                .orElseThrow(() -> new RuntimeException("유효하지 않거나 만료된 인증번호입니다."));

        // 사용자 확인
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        // 비밀번호 변경
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // 토큰 사용 처리
        resetToken.setUsed(true);
        tokenRepository.save(resetToken);
    }

    /**
     * 6자리 랜덤 인증번호 생성
     */
    private String generateToken() {
        Random random = new Random();
        return String.format("%06d", random.nextInt(1000000));
    }

    /**
     * 만료된 토큰 삭제 (스케줄러로 주기적 실행 가능)
     */
    public void deleteExpiredTokens() {
        tokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
    }
}

