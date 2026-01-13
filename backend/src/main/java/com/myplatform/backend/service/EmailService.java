package com.myplatform.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendPasswordResetEmail(String toEmail, String token) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject("[My Platform] 비밀번호 재설정 인증번호");
            message.setText(
                "안녕하세요.\n\n" +
                "비밀번호 재설정을 위한 인증번호는 다음과 같습니다:\n\n" +
                "인증번호: " + token + "\n\n" +
                "이 인증번호는 10분간 유효합니다.\n" +
                "본인이 요청하지 않았다면 이 이메일을 무시해주세요.\n\n" +
                "감사합니다.\n" +
                "My Platform 팀"
            );

            mailSender.send(message);
            log.info("Password reset email sent to: {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send password reset email to: {}", toEmail, e);
            throw new RuntimeException("이메일 전송에 실패했습니다.");
        }
    }

    public void sendVerificationEmail(String toEmail, String token) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject("[My Platform] 회원가입 이메일 인증");
            message.setText(
                "안녕하세요.\n\n" +
                "My Platform 회원가입을 위한 인증번호는 다음과 같습니다:\n\n" +
                "인증번호: " + token + "\n\n" +
                "이 인증번호는 10분간 유효합니다.\n" +
                "본인이 요청하지 않았다면 이 이메일을 무시해주세요.\n\n" +
                "감사합니다.\n" +
                "My Platform 팀"
            );

            mailSender.send(message);
            log.info("Verification email sent to: {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send verification email to: {}", toEmail, e);
            throw new RuntimeException("이메일 전송에 실패했습니다.");
        }
    }
}

