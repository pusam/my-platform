package com.myplatform.backend.service;

import com.myplatform.backend.entity.PasswordResetToken;
import com.myplatform.backend.entity.User;
import com.myplatform.backend.repository.PasswordResetTokenRepository;
import com.myplatform.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * PasswordResetService 단위 테스트
 *
 * 검증 포인트:
 * 1. 토큰 발송 — 사용자 존재 + 이메일 일치 시 6자리 토큰 생성 + 이메일 발송
 * 2. 토큰 발송 거부 — 존재하지 않는 아이디 / 이메일 불일치
 * 3. 토큰 검증 — 유효 토큰 통과, 만료/사용된 토큰 거부
 * 4. 토큰 검증 — 5회 연속 실패 시 토큰 강제 invalidate (브루트포스 차단)
 * 5. 비밀번호 변경 — 유효 토큰으로 새 비밀번호 저장 + 토큰 used=true
 * 6. 비밀번호 변경 거부 — 만료 토큰 / 잘못된 토큰 / 이전과 동일 비밀번호
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PasswordResetServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private EmailService emailService;

    private PasswordEncoder passwordEncoder;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        service = new PasswordResetService(userRepository, tokenRepository, emailService, passwordEncoder);
    }

    private User makeUser(String username, String email, String rawPassword) {
        User u = new User();
        u.setId(1L);
        u.setUsername(username);
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setStatus("APPROVED");
        u.setFailedLoginAttempts(0);
        return u;
    }

    // ---------------------------------------------------------------
    @Nested
    @DisplayName("sendResetToken - 인증번호 발송")
    class SendResetToken {
        @Test
        @DisplayName("사용자 + 이메일 일치 → 토큰 생성 + 이메일 발송")
        void success() {
            User user = makeUser("alice", "alice@example.com", "oldpw1234");
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

            service.sendResetToken("alice", "alice@example.com");

            verify(tokenRepository).deleteByEmail("alice@example.com");
            verify(tokenRepository).save(any(PasswordResetToken.class));
            verify(emailService).sendPasswordResetEmail(eq("alice@example.com"), any(String.class));
        }

        @Test
        @DisplayName("존재하지 않는 아이디 → RuntimeException")
        void unknownUser() {
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.sendResetToken("ghost", "x@y.com"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("존재하지 않는 아이디");

            verify(emailService, never()).sendPasswordResetEmail(any(), any());
        }

        @Test
        @DisplayName("이메일 불일치 → RuntimeException")
        void emailMismatch() {
            User user = makeUser("alice", "alice@example.com", "oldpw1234");
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> service.sendResetToken("alice", "other@example.com"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("일치하지 않");

            verify(emailService, never()).sendPasswordResetEmail(any(), any());
        }
    }

    // ---------------------------------------------------------------
    @Nested
    @DisplayName("verifyToken - 인증번호 확인")
    class VerifyToken {
        @Test
        @DisplayName("유효 토큰 → true")
        void valid() {
            when(tokenRepository.findByEmailAndTokenAndUsedFalseAndExpiresAtAfter(
                    eq("a@b.com"), eq("123456"), any(LocalDateTime.class)))
                    .thenReturn(Optional.of(new PasswordResetToken()));

            assertThat(service.verifyToken("a@b.com", "123456")).isTrue();
        }

        @Test
        @DisplayName("만료/없는 토큰 → false")
        void invalid() {
            when(tokenRepository.findByEmailAndTokenAndUsedFalseAndExpiresAtAfter(
                    eq("a@b.com"), eq("999999"), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());

            assertThat(service.verifyToken("a@b.com", "999999")).isFalse();
        }

        @Test
        @DisplayName("5회 연속 실패 → 모든 토큰 강제 invalidate (브루트포스 차단)")
        void bruteForceLockout() {
            when(tokenRepository.findByEmailAndTokenAndUsedFalseAndExpiresAtAfter(
                    eq("a@b.com"), any(), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());

            for (int i = 0; i < 5; i++) {
                service.verifyToken("a@b.com", String.format("%06d", i));
            }

            // 5회째 호출에서 deleteByEmail 실행
            verify(tokenRepository, times(1)).deleteByEmail("a@b.com");
        }

        @Test
        @DisplayName("성공 시 시도 카운터 리셋")
        void successResetsCounter() {
            // 실패 3회
            when(tokenRepository.findByEmailAndTokenAndUsedFalseAndExpiresAtAfter(
                    eq("a@b.com"), eq("BAD"), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());
            for (int i = 0; i < 3; i++) service.verifyToken("a@b.com", "BAD");

            // 성공 1회 → 카운터 리셋
            when(tokenRepository.findByEmailAndTokenAndUsedFalseAndExpiresAtAfter(
                    eq("a@b.com"), eq("GOOD12"), any(LocalDateTime.class)))
                    .thenReturn(Optional.of(new PasswordResetToken()));
            service.verifyToken("a@b.com", "GOOD12");

            // 이어서 또 실패 4번 — 누적 7회지만 리셋 됐으니 invalidate 발동 안 함
            for (int i = 0; i < 4; i++) service.verifyToken("a@b.com", "BAD");

            verify(tokenRepository, never()).deleteByEmail("a@b.com");
        }
    }

    // ---------------------------------------------------------------
    @Nested
    @DisplayName("resetPassword - 비밀번호 변경")
    class ResetPassword {
        @Test
        @DisplayName("유효 토큰 + 새 비밀번호 → 저장 + 토큰 used=true")
        void success() {
            // PasswordPolicy: 최소 12자 + 대소문자 + 숫자 + 특수문자, username/email-local 미포함
            User user = makeUser("bob", "bob@example.com", "OldStrong!234");
            PasswordResetToken token = new PasswordResetToken();
            token.setEmail("bob@example.com");
            token.setToken("123456");
            token.setUsed(false);

            when(tokenRepository.findByEmailAndTokenAndUsedFalseAndExpiresAtAfter(
                    eq("bob@example.com"), eq("123456"), any(LocalDateTime.class)))
                    .thenReturn(Optional.of(token));
            when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user));

            service.resetPassword("bob", "bob@example.com", "123456", "NewStrong!234");

            assertThat(token.getUsed()).isTrue();
            verify(userRepository).save(user);
            assertThat(passwordEncoder.matches("NewStrong!234", user.getPassword())).isTrue();
        }

        @Test
        @DisplayName("만료/잘못된 토큰 → 거부")
        void invalidToken() {
            when(tokenRepository.findByEmailAndTokenAndUsedFalseAndExpiresAtAfter(
                    any(), any(), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resetPassword("alice", "a@b.com", "WRONG", "newpw"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("유효하지 않");
        }

        @Test
        @DisplayName("이전과 동일한 비밀번호 → 거부")
        void samePasswordRejected() {
            // 정책 통과하는 동일 비밀번호 (12자+대소문자+숫자+특수문자, username/email 미포함)
            String samePw = "SameStrong!234";
            User user = makeUser("bob", "bob@example.com", samePw);
            PasswordResetToken token = new PasswordResetToken();
            token.setUsed(false);

            when(tokenRepository.findByEmailAndTokenAndUsedFalseAndExpiresAtAfter(
                    any(), any(), any(LocalDateTime.class)))
                    .thenReturn(Optional.of(token));
            when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user));

            assertThatThrownBy(() ->
                    service.resetPassword("bob", "bob@example.com", "123456", samePw))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("이전 비밀번호");
        }
    }
}
