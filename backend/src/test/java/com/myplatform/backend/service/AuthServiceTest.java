package com.myplatform.backend.service;

import com.myplatform.backend.dto.LoginRequest;
import com.myplatform.backend.dto.LoginResponse;
import com.myplatform.backend.dto.SignupRequest;
import com.myplatform.backend.dto.SignupResponse;
import com.myplatform.backend.entity.User;
import com.myplatform.backend.repository.UserRepository;
import com.myplatform.jwtredis.provider.JwtTokenProvider;
// RedisTokenService는 optional → null로 테스트
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AuthService 단위 테스트
 *
 * 검증 포인트:
 * 1. 로그인 성공 시 JWT 발급 + 실패 횟수 리셋
 * 2. 비밀번호 오류 시 실패 횟수 증가
 * 3. 10회 실패 → 계정 잠금 (LOCKED)
 * 4. 잠긴 계정 로그인 거부
 * 5. 미승인(PENDING/REJECTED) 계정 로그인 거부
 * 6. 회원가입 유효성 검증 (비밀번호 불일치, 이메일 형식, 중복 체크)
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private EmailVerificationService emailVerificationService;

    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        authService = new AuthService(userRepository, passwordEncoder, jwtTokenProvider, null, emailVerificationService);
    }

    private User buildUser(String username, String rawPassword, String status, int failedAttempts) {
        User user = new User();
        user.setId(1L);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setName("테스트");
        user.setEmail(username + "@test.com");
        user.setRole("USER");
        user.setStatus(status);
        user.setFailedLoginAttempts(failedAttempts);
        return user;
    }

    // ========== 로그인 성공 ==========

    @Nested
    @DisplayName("로그인 성공 시나리오")
    class LoginSuccessTests {

        @Test
        @DisplayName("승인된 계정 + 올바른 비밀번호 → JWT 토큰 반환")
        void approvedUser_correctPassword_returnsToken() {
            User user = buildUser("admin", "password123", "APPROVED", 0);
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            // 현재 AuthService 는 generateAccessToken + generateRefreshToken 두 토큰 발급
            when(jwtTokenProvider.generateAccessToken("admin")).thenReturn("jwt-token-xxx");
            when(jwtTokenProvider.generateRefreshToken("admin")).thenReturn("refresh-xxx");

            LoginResponse response = authService.login(new LoginRequest("admin", "password123"));

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getToken()).isEqualTo("jwt-token-xxx");
            assertThat(response.getUsername()).isEqualTo("admin");
            verify(jwtTokenProvider).generateAccessToken("admin");
        }

        @Test
        @DisplayName("로그인 성공 시 실패 횟수 0으로 리셋")
        void loginSuccess_resetsFailedAttempts() {
            User user = buildUser("admin", "password123", "APPROVED", 5);
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(jwtTokenProvider.generateAccessToken("admin")).thenReturn("token");
            when(jwtTokenProvider.generateRefreshToken("admin")).thenReturn("refresh");

            authService.login(new LoginRequest("admin", "password123"));

            assertThat(user.getFailedLoginAttempts()).isEqualTo(0);
            verify(userRepository).save(user);
        }
    }

    // ========== 로그인 실패 ==========

    @Nested
    @DisplayName("로그인 실패 시나리오")
    class LoginFailTests {

        @Test
        @DisplayName("존재하지 않는 사용자 → 실패 응답")
        void unknownUser_returnsFail() {
            when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

            LoginResponse response = authService.login(new LoginRequest("nobody", "pass"));

            assertThat(response.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("잘못된 비밀번호 → 실패 + failedAttempts 증가")
        void wrongPassword_incrementsFailCount() {
            User user = buildUser("admin", "correct", "APPROVED", 2);
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

            LoginResponse response = authService.login(new LoginRequest("admin", "wrong"));

            assertThat(response.isSuccess()).isFalse();
            assertThat(user.getFailedLoginAttempts()).isEqualTo(3);
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("10회 실패 → 계정 잠금 (LOCKED)")
        void tenthFailure_locksAccount() {
            User user = buildUser("admin", "correct", "APPROVED", 9);
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

            LoginResponse response = authService.login(new LoginRequest("admin", "wrong"));

            assertThat(response.isSuccess()).isFalse();
            assertThat(user.getStatus()).isEqualTo("LOCKED");
            assertThat(user.getFailedLoginAttempts()).isEqualTo(10);
        }

        @Test
        @DisplayName("잠긴 계정(LOCKED) → 로그인 거부")
        void lockedAccount_rejected() {
            User user = buildUser("admin", "password", "LOCKED", 10);
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

            LoginResponse response = authService.login(new LoginRequest("admin", "password"));

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage()).contains("잠");
        }

        @Test
        @DisplayName("미승인(PENDING) 계정 → 로그인 거부")
        void pendingAccount_rejected() {
            User user = buildUser("newuser", "pass", "PENDING", 0);
            when(userRepository.findByUsername("newuser")).thenReturn(Optional.of(user));

            LoginResponse response = authService.login(new LoginRequest("newuser", "pass"));

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage()).contains("승인");
        }

        @Test
        @DisplayName("거부된(REJECTED) 계정 → 로그인 거부")
        void rejectedAccount_rejected() {
            User user = buildUser("baduser", "pass", "REJECTED", 0);
            when(userRepository.findByUsername("baduser")).thenReturn(Optional.of(user));

            LoginResponse response = authService.login(new LoginRequest("baduser", "pass"));

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage()).contains("거부");
        }

        @Test
        @DisplayName("미정의 상태(SUSPENDED 등) 계정 → 로그인 거부 — APPROVED 화이트리스트(fall-through 금지)")
        void unknownStatus_rejected() {
            // PENDING/REJECTED 블랙리스트만 있으면 그 외 상태(관리자 임의 정지 등)가 통과해버린다
            User user = buildUser("suspended", "pass", "SUSPENDED", 0);
            when(userRepository.findByUsername("suspended")).thenReturn(Optional.of(user));

            LoginResponse response = authService.login(new LoginRequest("suspended", "pass"));

            assertThat(response.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("status=null 레거시 계정 → 로그인 거부 (APPROVED 아님)")
        void nullStatus_rejected() {
            User user = buildUser("legacy", "pass", null, 0);
            when(userRepository.findByUsername("legacy")).thenReturn(Optional.of(user));

            LoginResponse response = authService.login(new LoginRequest("legacy", "pass"));

            assertThat(response.isSuccess()).isFalse();
        }
    }

    // ========== 토큰 갱신 (refresh) ==========

    @Nested
    @DisplayName("refresh 시나리오 — RT revocation")
    class RefreshTests {

        @Mock private com.myplatform.jwtredis.service.RedisTokenService redisTokenService;

        private AuthService serviceWithRedis() {
            return new AuthService(userRepository, passwordEncoder, jwtTokenProvider,
                    redisTokenService, emailVerificationService);
        }

        private void stubValidRt(String rt, String username) {
            when(jwtTokenProvider.validateRefreshToken(rt)).thenReturn(true);
            when(jwtTokenProvider.getUsernameFromToken(rt)).thenReturn(username);
        }

        @Test
        @DisplayName("로그아웃으로 저장 RT 삭제됨(storedRt=null) → 옛 RT 거부 (revocation 유지)")
        void deletedStoredRt_rejected() {
            stubValidRt("old-rt", "admin");
            when(redisTokenService.getRefreshToken("admin")).thenReturn(null);   // 로그아웃 후 상태

            LoginResponse response = serviceWithRedis().refresh("old-rt");

            assertThat(response.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("저장 RT 와 불일치(회전 후 옛 RT 재사용) → 거부 + 토큰 전부 무효화")
        void mismatchedRt_rejectedAndInvalidated() {
            stubValidRt("old-rt", "admin");
            when(redisTokenService.getRefreshToken("admin")).thenReturn("new-rt");

            LoginResponse response = serviceWithRedis().refresh("old-rt");

            assertThat(response.isSuccess()).isFalse();
            verify(redisTokenService).deleteToken("admin");
            verify(redisTokenService).deleteRefreshToken("admin");
        }

        @Test
        @DisplayName("저장 RT 와 일치 → 새 AT/RT 발급 (rotation)")
        void matchedRt_rotates() {
            User user = buildUser("admin", "pw", "APPROVED", 0);
            stubValidRt("current-rt", "admin");
            when(redisTokenService.getRefreshToken("admin")).thenReturn("current-rt");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(jwtTokenProvider.generateAccessToken("admin")).thenReturn("new-at");
            when(jwtTokenProvider.generateRefreshToken("admin")).thenReturn("new-rt");

            LoginResponse response = serviceWithRedis().refresh("current-rt");

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getToken()).isEqualTo("new-at");
        }

        @Test
        @DisplayName("Redis 서비스 자체가 없는 환경(개발) → RT 자체 검증만으로 허용 (기존 동작 보존)")
        void noRedisService_allowed() {
            User user = buildUser("admin", "pw", "APPROVED", 0);
            when(jwtTokenProvider.validateRefreshToken("rt")).thenReturn(true);
            when(jwtTokenProvider.getUsernameFromToken("rt")).thenReturn("admin");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(jwtTokenProvider.generateAccessToken("admin")).thenReturn("new-at");
            when(jwtTokenProvider.generateRefreshToken("admin")).thenReturn("new-rt");

            LoginResponse response = authService.refresh("rt");   // setUp 의 redis=null 인스턴스

            assertThat(response.isSuccess()).isTrue();
        }
    }

    // ========== 회원가입 ==========

    @Nested
    @DisplayName("회원가입 시나리오")
    class SignupTests {

        private SignupRequest validRequest() {
            SignupRequest req = new SignupRequest();
            req.setUsername("newuser");
            // PasswordPolicy: 12자+대소문자+숫자+특수문자, username("newuser")/email-local("hong") 미포함
            req.setPassword("Strong!Pass123");
            req.setPasswordConfirm("Strong!Pass123");
            req.setName("홍길동");
            req.setEmail("hong@example.com");
            req.setPhone("010-1234-5678");
            req.setVerificationToken("123456");
            return req;
        }

        @Test
        @DisplayName("정상 회원가입 → 성공 + PENDING 상태로 생성")
        void validSignup_success() {
            SignupRequest req = validRequest();
            when(emailVerificationService.isEmailVerified("hong@example.com")).thenReturn(true);
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            when(userRepository.existsByEmail("hong@example.com")).thenReturn(false);
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            SignupResponse response = authService.signup(req);

            assertThat(response.isSuccess()).isTrue();
            verify(userRepository).save(argThat(user ->
                    "USER".equals(user.getRole()) && "PENDING".equals(user.getStatus())));
        }

        @Test
        @DisplayName("비밀번호 불일치 → 실패")
        void passwordMismatch_fails() {
            SignupRequest req = validRequest();
            req.setPasswordConfirm("different");

            SignupResponse response = authService.signup(req);

            assertThat(response.isSuccess()).isFalse();
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("비밀번호 4자 미만 → 실패")
        void shortPassword_fails() {
            SignupRequest req = validRequest();
            req.setPassword("abc");
            req.setPasswordConfirm("abc");

            SignupResponse response = authService.signup(req);

            assertThat(response.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("이메일 형식 오류 → 실패")
        void invalidEmail_fails() {
            SignupRequest req = validRequest();
            req.setEmail("not-an-email");

            SignupResponse response = authService.signup(req);

            assertThat(response.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("전화번호 형식 오류 → 실패")
        void invalidPhone_fails() {
            SignupRequest req = validRequest();
            req.setPhone("12345678");

            SignupResponse response = authService.signup(req);

            assertThat(response.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("중복 아이디 → 실패")
        void duplicateUsername_fails() {
            SignupRequest req = validRequest();
            when(emailVerificationService.isEmailVerified("hong@example.com")).thenReturn(true);
            when(userRepository.existsByUsername("newuser")).thenReturn(true);

            SignupResponse response = authService.signup(req);

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage()).contains("아이디");
        }

        @Test
        @DisplayName("중복 이메일 → 실패")
        void duplicateEmail_fails() {
            SignupRequest req = validRequest();
            when(emailVerificationService.isEmailVerified("hong@example.com")).thenReturn(true);
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            when(userRepository.existsByEmail("hong@example.com")).thenReturn(true);

            SignupResponse response = authService.signup(req);

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage()).contains("이메일");
        }

        @Test
        @DisplayName("이메일 미인증 → 실패")
        void emailNotVerified_fails() {
            SignupRequest req = validRequest();
            when(emailVerificationService.isEmailVerified("hong@example.com")).thenReturn(false);

            SignupResponse response = authService.signup(req);

            assertThat(response.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("필수 필드 누락 → 실패")
        void missingFields_fails() {
            SignupRequest req = new SignupRequest();
            // 모든 필드가 null

            SignupResponse response = authService.signup(req);

            assertThat(response.isSuccess()).isFalse();
        }
    }
}
