package com.myplatform.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.LoginRequest;
import com.myplatform.backend.dto.LoginResponse;
import com.myplatform.backend.dto.SignupRequest;
import com.myplatform.backend.dto.SignupResponse;
import com.myplatform.backend.service.AuthService;
import com.myplatform.backend.service.EmailVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthController 단위 테스트 (Standalone MockMvc)
 *
 * 검증 포인트:
 * 1. 로그인 성공 시 JWT 토큰 + 사용자 정보 반환
 * 2. 로그인 실패 시 401 + 에러 메시지
 * 3. 회원가입 성공/실패 응답 구조
 * 4. JSON 요청/응답 스펙 준수
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private AuthService authService;
    @Mock private EmailVerificationService emailVerificationService;

    @InjectMocks
    private AuthController authController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(authController).build();
    }

    // ========== 로그인 ==========

    @Nested
    @DisplayName("POST /api/auth/login")
    class LoginTests {

        @Test
        @DisplayName("로그인 성공 → 200 + JWT + 사용자 정보")
        void loginSuccess_returnsToken() throws Exception {
            LoginRequest request = new LoginRequest("admin", "password123");
            LoginResponse response = new LoginResponse(
                    true, "로그인 성공", "eyJhbGciOiJ...", "admin", "관리자", "ADMIN");
            when(authService.login(any(LoginRequest.class))).thenReturn(response);

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.token").value("eyJhbGciOiJ..."))
                    .andExpect(jsonPath("$.username").value("admin"))
                    .andExpect(jsonPath("$.role").value("ADMIN"));
        }

        @Test
        @DisplayName("로그인 실패 (잘못된 비밀번호) → success:false")
        void loginFail_returnsFailResponse() throws Exception {
            LoginRequest request = new LoginRequest("admin", "wrong");
            LoginResponse failResponse = new LoginResponse(false, "비밀번호가 일치하지 않습니다.");
            when(authService.login(any(LoginRequest.class))).thenReturn(failResponse);

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("비밀번호가 일치하지 않습니다."));
        }

        @Test
        @DisplayName("로그인 예외 발생 시 서비스에서 예외 전파")
        void loginException_throwsException() {
            // given
            LoginRequest request = new LoginRequest("user", "pass");
            when(authService.login(any(LoginRequest.class)))
                    .thenThrow(new RuntimeException("DB 연결 실패"));

            // when & then — Standalone MockMvc에서 예외는 ServletException으로 래핑
            org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                    mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))));
        }
    }

    // ========== 회원가입 ==========

    @Nested
    @DisplayName("POST /api/auth/signup")
    class SignupTests {

        @Test
        @DisplayName("회원가입 성공 → 200 + success:true")
        void signupSuccess() throws Exception {
            SignupRequest request = new SignupRequest();
            request.setUsername("newuser");
            request.setPassword("password123");
            request.setPasswordConfirm("password123");
            request.setName("홍길동");
            request.setEmail("test@example.com");
            request.setPhone("010-1234-5678");
            request.setVerificationToken("123456");

            when(authService.signup(any(SignupRequest.class)))
                    .thenReturn(new SignupResponse(true, "회원가입이 완료되었습니다."));

            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다."));
        }

        @Test
        @DisplayName("회원가입 실패 (중복 아이디) → success:false")
        void signupFail_duplicateUsername() throws Exception {
            SignupRequest request = new SignupRequest();
            request.setUsername("admin");
            request.setPassword("pass");
            request.setPasswordConfirm("pass");
            request.setName("테스트");
            request.setEmail("t@t.com");
            request.setPhone("010-1234-5678");
            request.setVerificationToken("123456");

            when(authService.signup(any(SignupRequest.class)))
                    .thenReturn(new SignupResponse(false, "이미 사용 중인 아이디입니다."));

            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }
}
