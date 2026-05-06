package com.myplatform.backend.service;

import com.myplatform.backend.entity.EmailVerificationToken;
import com.myplatform.backend.repository.EmailVerificationTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
@Transactional
public class EmailVerificationService {

    private final EmailVerificationTokenRepository tokenRepository;
    private final EmailService emailService;

    public EmailVerificationService(
            EmailVerificationTokenRepository tokenRepository,
            EmailService emailService) {
        this.tokenRepository = tokenRepository;
        this.emailService = emailService;
    }

    /**
     * 이메일 인증번호 발송
     */
    public void sendVerificationToken(String email) {
        // 기존 토큰 삭제
        tokenRepository.deleteByEmail(email);

        // 6자리 인증번호 생성
        String token = generateToken();

        // 토큰 저장 (10분 유효)
        EmailVerificationToken verificationToken = new EmailVerificationToken();
        verificationToken.setEmail(email);
        verificationToken.setToken(token);
        verificationToken.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        verificationToken.setVerified(false);
        tokenRepository.save(verificationToken);

        // 이메일 발송
        emailService.sendVerificationEmail(email, token);
    }

    /**
     * 인증번호 확인 — 검증 후 만료시간을 30분 연장하여 회원가입 윈도우 확보
     */
    public boolean verifyToken(String email, String token) {
        EmailVerificationToken verificationToken = tokenRepository
                .findByEmailAndTokenAndVerifiedFalseAndExpiresAtAfter(
                        email, token, LocalDateTime.now()
                )
                .orElse(null);

        if (verificationToken != null) {
            verificationToken.setVerified(true);
            // 회원가입 게이트는 expiresAt 만료 전까지만 통과 — 30분 윈도우
            verificationToken.setExpiresAt(LocalDateTime.now().plusMinutes(30));
            tokenRepository.save(verificationToken);
            return true;
        }
        return false;
    }

    /**
     * 이메일 인증 완료 여부 확인 (회원가입 시 사용)
     * verified=true 이면서 expiresAt 안 지났을 때만 true — 오래된 verified 토큰 재사용 차단
     */
    public boolean isEmailVerified(String email) {
        return tokenRepository.existsByEmailAndVerifiedTrueAndExpiresAtAfter(email, LocalDateTime.now());
    }

    /**
     * 6자리 랜덤 인증번호 생성
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private String generateToken() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1000000));
    }

    /**
     * 만료된 토큰 삭제
     */
    public void deleteExpiredTokens() {
        tokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
    }
}

