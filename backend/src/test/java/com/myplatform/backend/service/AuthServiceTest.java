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
            // AuthService 는 기기 바인딩된 AT+RT 발급 (deviceId 는 로그인마다 생성되는 UUID)
            when(jwtTokenProvider.generateAccessToken(eq("admin"), anyString())).thenReturn("jwt-token-xxx");
            when(jwtTokenProvider.generateRefreshToken(eq("admin"), anyString())).thenReturn("refresh-xxx");

            LoginResponse response = authService.login(new LoginRequest("admin", "password123"));

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getToken()).isEqualTo("jwt-token-xxx");
            assertThat(response.getUsername()).isEqualTo("admin");
            verify(jwtTokenProvider).generateAccessToken(eq("admin"), anyString());
        }

        @Test
        @DisplayName("로그인 성공 시 실패 횟수 0으로 리셋")
        void loginSuccess_resetsFailedAttempts() {
            User user = buildUser("admin", "password123", "APPROVED", 5);
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(jwtTokenProvider.generateAccessToken(eq("admin"), anyString())).thenReturn("token");
            when(jwtTokenProvider.generateRefreshToken(eq("admin"), anyString())).thenReturn("refresh");

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
    @DisplayName("refresh 시나리오 — 기기별 RT + 회전 유예")
    class RefreshTests {

        @Mock private com.myplatform.jwtredis.service.RedisTokenService redisTokenService;

        private AuthService serviceWithRedis() {
            return new AuthService(userRepository, passwordEncoder, jwtTokenProvider,
                    redisTokenService, emailVerificationService);
        }

        private void stubValidRt(String rt, String username, String deviceId) {
            when(jwtTokenProvider.validateRefreshToken(rt)).thenReturn(true);
            when(jwtTokenProvider.getUsernameFromToken(rt)).thenReturn(username);
            when(jwtTokenProvider.getDeviceIdFromToken(rt)).thenReturn(deviceId);
        }

        @Test
        @DisplayName("로그아웃으로 저장 RT 삭제됨(storedRt=null, 유예도 없음) → 옛 RT 거부 (revocation 유지)")
        void deletedStoredRt_rejected() {
            stubValidRt("old-rt", "admin", "dev-A");
            when(redisTokenService.getRefreshToken("admin", "dev-A")).thenReturn(null);   // 로그아웃 후 상태
            when(redisTokenService.getPreviousRefreshToken("admin", "dev-A")).thenReturn(null);

            LoginResponse response = serviceWithRedis().refresh("old-rt");

            assertThat(response.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("불일치(유예 초과 옛 RT 재사용) → 거부 + 해당 기기 체인만 무효화 (다른 기기 세션 보존)")
        void mismatchedRt_invalidatesOnlyThatDevice() {
            stubValidRt("old-rt", "admin", "dev-A");
            when(redisTokenService.getRefreshToken("admin", "dev-A")).thenReturn("new-rt");
            when(redisTokenService.getPreviousRefreshToken("admin", "dev-A")).thenReturn(null);

            LoginResponse response = serviceWithRedis().refresh("old-rt");

            assertThat(response.isSuccess()).isFalse();
            verify(redisTokenService).deleteRefreshToken("admin", "dev-A");
            verify(redisTokenService).deletePreviousRefreshToken("admin", "dev-A");
            // 핵심: 계정 전체 무효화 금지 — 다른 기기(dev-B 등)의 세션은 살아있어야 한다
            verify(redisTokenService, never()).deleteAllRefreshTokens(anyString());
        }

        @Test
        @DisplayName("저장 RT 와 일치 → 회전 + 직전 RT 를 유예 저장 (멀티탭 경합 대비)")
        void matchedRt_rotatesAndKeepsGrace() {
            User user = buildUser("admin", "pw", "APPROVED", 0);
            stubValidRt("current-rt", "admin", "dev-A");
            when(redisTokenService.getRefreshToken("admin", "dev-A")).thenReturn("current-rt");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(jwtTokenProvider.generateAccessToken("admin", "dev-A")).thenReturn("new-at");
            when(jwtTokenProvider.generateRefreshToken("admin", "dev-A")).thenReturn("new-rt");

            LoginResponse response = serviceWithRedis().refresh("current-rt");

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getToken()).isEqualTo("new-at");
            assertThat(response.getRefreshToken()).isEqualTo("new-rt");
            // 같은 기기 키에 회전 저장 + 직전 RT 는 유예 키에 짧게 보존
            verify(redisTokenService).saveRefreshToken(eq("admin"), eq("dev-A"), eq("new-rt"), anyLong());
            verify(redisTokenService).savePreviousRefreshToken(eq("admin"), eq("dev-A"), eq("current-rt"), anyLong());
        }

        @Test
        @DisplayName("멀티탭 경합: 다른 탭이 먼저 회전한 직후 옛 RT 로 갱신 → 유예 허용, AT 만 재발급 (재현: 수정 전엔 로그아웃)")
        void multiTabRace_withinGrace_issuesAccessTokenWithoutRotation() {
            User user = buildUser("admin", "pw", "APPROVED", 0);
            stubValidRt("old-rt", "admin", "dev-A");
            // 이긴 탭이 이미 회전을 끝냄: 저장 RT 는 새것, 직전 RT 가 유예 키에 남아있음
            when(redisTokenService.getRefreshToken("admin", "dev-A")).thenReturn("rotated-rt");
            when(redisTokenService.getPreviousRefreshToken("admin", "dev-A")).thenReturn("old-rt");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(jwtTokenProvider.generateAccessToken("admin", "dev-A")).thenReturn("fresh-at");

            LoginResponse response = serviceWithRedis().refresh("old-rt");

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getToken()).isEqualTo("fresh-at");
            // RT 는 회전하지 않는다(이중 회전 방지) — 프론트는 refreshToken=null 이면 기존 저장분 유지
            assertThat(response.getRefreshToken()).isNull();
            verify(jwtTokenProvider, never()).generateRefreshToken(anyString(), anyString());
            // 세션 무효화도 없어야 한다
            verify(redisTokenService, never()).deleteRefreshToken(anyString(), anyString());
        }

        @Test
        @DisplayName("legacy RT(deviceId 없음) → 갱신 성공 시 기기 바인딩으로 마이그레이션 + legacy 키 삭제")
        void legacyRt_migratesToDeviceBinding() {
            User user = buildUser("admin", "pw", "APPROVED", 0);
            stubValidRt("legacy-rt", "admin", null);
            when(redisTokenService.getRefreshToken("admin", null)).thenReturn("legacy-rt");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(jwtTokenProvider.generateAccessToken(eq("admin"), anyString())).thenReturn("new-at");
            when(jwtTokenProvider.generateRefreshToken(eq("admin"), anyString())).thenReturn("new-rt");

            LoginResponse response = serviceWithRedis().refresh("legacy-rt");

            assertThat(response.isSuccess()).isTrue();
            // AT/RT 가 같은 새 deviceId 로 바인딩되는지 확인
            org.mockito.ArgumentCaptor<String> atDid = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.ArgumentCaptor<String> rtDid = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(jwtTokenProvider).generateAccessToken(eq("admin"), atDid.capture());
            verify(jwtTokenProvider).generateRefreshToken(eq("admin"), rtDid.capture());
            assertThat(atDid.getValue()).isNotBlank().isEqualTo(rtDid.getValue());
            // legacy 단일 키는 삭제되고 새 기기 키로 저장
            verify(redisTokenService).deleteRefreshToken("admin", null);
            verify(redisTokenService).saveRefreshToken(eq("admin"), eq(rtDid.getValue()), eq("new-rt"), anyLong());
        }

        @Test
        @DisplayName("계정 상태 변경(잠금 등) → 기기 단위가 아니라 계정 전체 RT 무효화")
        void accountStateChanged_revokesAllDevices() {
            User locked = buildUser("admin", "pw", "LOCKED", 0);
            stubValidRt("current-rt", "admin", "dev-A");
            when(redisTokenService.getRefreshToken("admin", "dev-A")).thenReturn("current-rt");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(locked));

            LoginResponse response = serviceWithRedis().refresh("current-rt");

            assertThat(response.isSuccess()).isFalse();
            verify(redisTokenService).deleteAllRefreshTokens("admin");
        }

        @Test
        @DisplayName("Redis 서비스 자체가 없는 환경(개발) → RT 자체 검증만으로 허용 (기존 동작 보존)")
        void noRedisService_allowed() {
            User user = buildUser("admin", "pw", "APPROVED", 0);
            when(jwtTokenProvider.validateRefreshToken("rt")).thenReturn(true);
            when(jwtTokenProvider.getUsernameFromToken("rt")).thenReturn("admin");
            when(jwtTokenProvider.getDeviceIdFromToken("rt")).thenReturn(null);
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(jwtTokenProvider.generateAccessToken(eq("admin"), anyString())).thenReturn("new-at");
            when(jwtTokenProvider.generateRefreshToken(eq("admin"), anyString())).thenReturn("new-rt");

            LoginResponse response = authService.refresh("rt");   // setUp 의 redis=null 인스턴스

            assertThat(response.isSuccess()).isTrue();
        }
    }

    // ========== 기기별 세션 분리 (로그인/로그아웃) ==========

    @Nested
    @DisplayName("기기별 세션 — 로그인 바인딩·로그아웃 스코프")
    class DeviceSessionTests {

        @Mock private com.myplatform.jwtredis.service.RedisTokenService redisTokenService;

        private AuthService serviceWithRedis() {
            return new AuthService(userRepository, passwordEncoder, jwtTokenProvider,
                    redisTokenService, emailVerificationService);
        }

        @Test
        @DisplayName("로그인 → AT/RT 를 같은 deviceId 로 발급하고 RT 는 기기 키에 저장 (계정 단일 키 덮어쓰기 금지)")
        void login_issuesDeviceBoundTokens() {
            User user = buildUser("admin", "password123", "APPROVED", 0);
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(jwtTokenProvider.generateAccessToken(eq("admin"), anyString())).thenReturn("at");
            when(jwtTokenProvider.generateRefreshToken(eq("admin"), anyString())).thenReturn("rt");

            LoginResponse response = serviceWithRedis().login(new LoginRequest("admin", "password123"));

            assertThat(response.isSuccess()).isTrue();
            org.mockito.ArgumentCaptor<String> atDid = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.ArgumentCaptor<String> rtDid = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(jwtTokenProvider).generateAccessToken(eq("admin"), atDid.capture());
            verify(jwtTokenProvider).generateRefreshToken(eq("admin"), rtDid.capture());
            assertThat(atDid.getValue()).isNotBlank().isEqualTo(rtDid.getValue());
            verify(redisTokenService).saveRefreshToken(eq("admin"), eq(rtDid.getValue()), eq("rt"), anyLong());
        }

        @Test
        @DisplayName("deviceId 있는 로그아웃 → 해당 기기 RT 만 삭제 (다른 기기 세션 유지)")
        void logout_withDeviceId_deletesOnlyThatDevice() {
            serviceWithRedis().logout("admin", "dev-A");

            verify(redisTokenService).deleteRefreshToken("admin", "dev-A");
            verify(redisTokenService).deletePreviousRefreshToken("admin", "dev-A");
            verify(redisTokenService, never()).deleteAllRefreshTokens(anyString());
        }

        @Test
        @DisplayName("deviceId 모르는 로그아웃(legacy AT) → 안전하게 전 기기 RT 삭제")
        void logout_withoutDeviceId_deletesAllDevices() {
            serviceWithRedis().logout("admin", null);

            verify(redisTokenService).deleteAllRefreshTokens("admin");
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
