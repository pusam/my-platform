package com.myplatform.backend.service;

import com.myplatform.backend.entity.EmailVerificationToken;
import com.myplatform.backend.repository.EmailVerificationTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Random;

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
     * 인증번호 확인
     */
    public boolean verifyToken(String email, String token) {
        EmailVerificationToken verificationToken = tokenRepository
                .findByEmailAndTokenAndVerifiedFalseAndExpiresAtAfter(
                        email, token, LocalDateTime.now()
                )
                .orElse(null);

        if (verificationToken != null) {
            verificationToken.setVerified(true);
            tokenRepository.save(verificationToken);
            return true;
        }
        return false;
    }

    /**
     * 이메일 인증 여부 확인
     */
    public boolean isEmailVerified(String email) {
        return tokenRepository.findByEmailAndTokenAndVerifiedFalseAndExpiresAtAfter(
                email, "", LocalDateTime.now().minusHours(1)
        ).isEmpty();
    }

    /**
     * 6자리 랜덤 인증번호 생성
     */
    private String generateToken() {
        Random random = new Random();
        return String.format("%06d", random.nextInt(1000000));
    }

    /**
     * 만료된 토큰 삭제
     */
    public void deleteExpiredTokens() {
        tokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
    }
}

