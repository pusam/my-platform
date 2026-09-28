package com.myplatform.backend.youtubeopinion;

import com.myplatform.backend.config.SecurityConfig;
import com.myplatform.jwtredis.entrypoint.JwtAuthenticationEntryPoint;
import com.myplatform.jwtredis.filter.JwtAuthenticationFilter;
import com.myplatform.jwtredis.provider.JwtTokenProvider;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 권한은 <b>실제 SecurityConfig 필터 체인</b>으로 확인한다 — 이 코드베이스엔 {@code @EnableMethodSecurity} 가 없어
 * {@code @PreAuthorize} 는 무효다(CLAUDE.md §7). 컨트롤러 단위 테스트(standalone MockMvc)는 필터를 안 거쳐 권한을 못 본다.
 *
 * <p>토큰은 진짜 {@link JwtTokenProvider} 로 발급해 {@link JwtAuthenticationFilter} 가 그대로 검증한다. 서비스만 가짜.
 * 관리자 경로({@code /api/admin/youtube-opinions/**}): 토큰 없음 401 · 일반 사용자 403 · 관리자 통과.
 * 조회 경로({@code /api/youtube-opinions/**}): 토큰 없음 401 · 로그인 사용자 통과.
 */
class YoutubeOpinionSecurityTest {

    static final JwtTokenProvider TOKENS =
            new JwtTokenProvider("test-only-secret-for-youtube-opinion-security-0123456789", 60_000, 60_000, 120_000);

    static YoutubeOpinionQueryService query = mock(YoutubeOpinionQueryService.class);
    static YoutubeOpinionAdminService adminService = mock(YoutubeOpinionAdminService.class);
    static YoutubeOpinionAnalysisService analysis = mock(YoutubeOpinionAnalysisService.class);

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class Web {
        @Bean
        JwtTokenProvider jwtTokenProvider() { return TOKENS; }

        @Bean
        UserDetailsService userDetailsService() {
            return username -> switch (username) {
                case "test-admin" -> User.withUsername(username).password("n/a").roles("ADMIN").build();
                case "test-user" -> User.withUsername(username).password("n/a").roles("USER").build();
                default -> throw new UsernameNotFoundException(username);
            };
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider p, UserDetailsService u) {
            return new JwtAuthenticationFilter(p, u);
        }

        @Bean
        JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint() { return new JwtAuthenticationEntryPoint(); }

        @Bean
        YoutubeOpinionController youtubeOpinionController() { return new YoutubeOpinionController(query); }

        @Bean
        YoutubeOpinionAdminController youtubeOpinionAdminController() {
            return new YoutubeOpinionAdminController(adminService, analysis);
        }
    }

    static AnnotationConfigWebApplicationContext ctx;
    static MockMvc mvc;
    static final String ADMIN = "Bearer " + TOKENS.generateAccessToken("test-admin");
    static final String USER = "Bearer " + TOKENS.generateAccessToken("test-user");
    static final String REFRESH_AS_ADMIN = "Bearer " + TOKENS.generateRefreshToken("test-admin");

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.register(Web.class);
        ctx.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(ctx)
                .addFilters(ctx.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String bearer) {
        return bearer == null ? req : req.header("Authorization", bearer);
    }

    static final String REGISTER_BODY = """
            {"url":"https://www.youtube.com/watch?v=TESTvid0001","title":"t","channelId":"UC_test_ch1",
             "publishedAt":"2026-09-27T20:00","sourceNote":"테스트","participants":[]}""";

    @Nested
    @DisplayName("관리자 경로 — URL 규칙이 막는다")
    class AdminPaths {

        @Test
        @DisplayName("토큰 없음 → 401, 서비스는 호출되지 않는다")
        void anonymous() throws Exception {
            reset(adminService, analysis);
            mvc.perform(get("/api/admin/youtube-opinions/config")).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/admin/youtube-opinions/videos").contentType(MediaType.APPLICATION_JSON).content(REGISTER_BODY))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/admin/youtube-opinions/videos/TESTvid0001/analyze")).andExpect(status().isUnauthorized());
            verifyNoInteractions(adminService, analysis);
        }

        @Test
        @DisplayName("일반 사용자 → 403 (등록·자막·분석·검토·목록 전부)")
        void userForbidden() throws Exception {
            reset(adminService, analysis);
            mvc.perform(as(get("/api/admin/youtube-opinions/config"), USER)).andExpect(status().isForbidden());
            mvc.perform(as(get("/api/admin/youtube-opinions/videos"), USER)).andExpect(status().isForbidden());
            mvc.perform(as(post("/api/admin/youtube-opinions/videos"), USER)
                    .contentType(MediaType.APPLICATION_JSON).content(REGISTER_BODY)).andExpect(status().isForbidden());
            mvc.perform(as(post("/api/admin/youtube-opinions/videos/TESTvid0001/transcript"), USER)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"format\":\"TEXT\",\"content\":\"[00:01] a\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(as(post("/api/admin/youtube-opinions/videos/TESTvid0001/analyze"), USER)).andExpect(status().isForbidden());
            mvc.perform(as(get("/api/admin/youtube-opinions/review"), USER)).andExpect(status().isForbidden());
            mvc.perform(as(post("/api/admin/youtube-opinions/opinions/1/review"), USER)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}")).andExpect(status().isForbidden());
            verifyNoInteractions(adminService, analysis);
        }

        @Test
        @DisplayName("리프레시 토큰을 Authorization 에 넣어도 통과하지 못한다")
        void refreshTokenRejected() throws Exception {
            mvc.perform(as(get("/api/admin/youtube-opinions/config"), REFRESH_AS_ADMIN)).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("관리자 → 통과(조회 200, 분석 요청 202)")
        void adminAllowed() throws Exception {
            reset(adminService, analysis);
            when(analysis.start(anyString(), anyString())).thenReturn(new YoutubeOpinionDtos.StartResult(7L, 1));
            mvc.perform(as(get("/api/admin/youtube-opinions/config"), ADMIN)).andExpect(status().isOk());
            mvc.perform(as(post("/api/admin/youtube-opinions/videos/TESTvid0001/analyze"), ADMIN))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.runId").value(7));
        }
    }

    @Nested
    @DisplayName("관리자 입력 — 크기·형식·기능 꺼짐")
    class AdminInput {

        @Test
        @DisplayName("자막 본문이 상한을 넘으면 413 — 서비스까지 가지 않는다")
        void transcriptTooLarge() throws Exception {
            reset(adminService);
            String big = "{\"format\":\"TEXT\",\"content\":\"" + "a".repeat(YoutubeOpinionAdminController.MAX_TRANSCRIPT_BODY_BYTES) + "\"}";
            mvc.perform(as(post("/api/admin/youtube-opinions/videos/TESTvid0001/transcript"), ADMIN)
                    .contentType(MediaType.APPLICATION_JSON).content(big)).andExpect(status().isPayloadTooLarge());
            verifyNoInteractions(adminService);
        }

        @Test
        @DisplayName("JSON 이 아닌 자막 요청은 400")
        void transcriptNotJson() throws Exception {
            mvc.perform(as(post("/api/admin/youtube-opinions/videos/TESTvid0001/transcript"), ADMIN)
                    .contentType(MediaType.APPLICATION_JSON).content("not json")).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("서비스 예외 → 상태코드: 형식 오류 400 · 꺼짐 503 · 처리량 429 · 진행 중 409")
        void errorMapping() throws Exception {
            reset(adminService, analysis);
            when(adminService.register(any(), anyString())).thenThrow(new IllegalArgumentException("YouTube 영상 주소가 아닙니다"));
            when(adminService.config()).thenThrow(new YoutubeOpinionDtos.FeatureDisabledException());
            when(analysis.start(anyString(), anyString())).thenThrow(new YoutubeOpinionDtos.TooManyRequestsException("많다"));
            when(adminService.reviewQueue()).thenThrow(new IllegalStateException("진행 중"));

            mvc.perform(as(post("/api/admin/youtube-opinions/videos"), ADMIN).contentType(MediaType.APPLICATION_JSON)
                    .content(REGISTER_BODY)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
            mvc.perform(as(get("/api/admin/youtube-opinions/config"), ADMIN)).andExpect(status().isServiceUnavailable());
            mvc.perform(as(post("/api/admin/youtube-opinions/videos/TESTvid0001/analyze"), ADMIN)).andExpect(status().isTooManyRequests());
            mvc.perform(as(get("/api/admin/youtube-opinions/review"), ADMIN)).andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("조회 경로 — 로그인 사용자 전용")
    class ReadPaths {

        @Test
        void anonymousRejected() throws Exception {
            mvc.perform(get("/api/youtube-opinions/stocks/005930")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/youtube-opinions/summary").param("codes", "005930")).andExpect(status().isUnauthorized());
        }

        @Test
        void userAllowed() throws Exception {
            reset(query);
            mvc.perform(as(get("/api/youtube-opinions/stocks/005930"), USER)).andExpect(status().isOk());
            mvc.perform(as(get("/api/youtube-opinions/summary").param("codes", "005930,000660"), USER)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("형식 오류는 400(500 아님)")
        void badCode() throws Exception {
            reset(query);
            when(query.stockView("BAD")).thenThrow(new IllegalArgumentException("종목코드 형식이 아닙니다."));
            mvc.perform(as(get("/api/youtube-opinions/stocks/BAD"), USER)).andExpect(status().isBadRequest());
        }
    }
}
