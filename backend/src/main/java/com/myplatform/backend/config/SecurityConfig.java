package com.myplatform.backend.config;

import com.myplatform.jwtredis.entrypoint.JwtAuthenticationEntryPoint;
import com.myplatform.jwtredis.filter.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    /**
     * CORS 허용 origin (콤마 구분). 환경변수 CORS_ALLOWED_ORIGINS 로 주입.
     * 미지정 시 로컬 개발용 localhost 만 허용한다.
     */
    @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:8080,http://127.0.0.1:5173,http://127.0.0.1:8080}")
    private String allowedOriginsCsv;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                         JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // 허용할 Origin 명시 (credentials 사용 시 와일드카드 불가)
        // app.cors.allowed-origins (env: CORS_ALLOWED_ORIGINS) 로 운영 도메인 주입.
        List<String> origins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        // 와일드카드 대신 실제 사용 헤더만 명시 (정보 누출/오남용 방지)
        configuration.setAllowedHeaders(Arrays.asList(
                "Authorization", "Content-Type", "X-Requested-With", "Accept", "Origin"));
        configuration.setExposedHeaders(Arrays.asList("Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable()) // JWT 기반 Stateless → CSRF 불필요
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())                          // 클릭재킹 방지
                        .contentTypeOptions(content -> {})                             // MIME 스니핑 방지 (X-Content-Type-Options: nosniff)
                        .httpStrictTransportSecurity(hsts -> hsts                      // HTTPS 강제
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        // 불필요한 브라우저 기능 차단 (StaticHeadersWriter — Spring Security 6.x 호환)
                        .addHeaderWriter(new StaticHeadersWriter(
                                "Permissions-Policy", "camera=(), microphone=(), geolocation=()"))
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 인증 API (로그인, 회원가입 등)
                        .requestMatchers("/api/auth/**").permitAll()

                        // 비밀번호 재설정 API
                        .requestMatchers("/api/password/**").permitAll()

                        // WebAuthn 로그인 / 패스키 — 비로그인 상태에서 호출 필요
                        .requestMatchers("/api/webauthn/login/**").permitAll()
                        .requestMatchers("/api/webauthn/passkey/**").permitAll()
                        // WebAuthn 등록/관리 — 로그인 필요
                        .requestMatchers("/api/webauthn/**").authenticated()

                        // 금/은 시세 조회 API (공개)
                        .requestMatchers("/api/gold/price", "/api/silver/price").permitAll()
                        .requestMatchers("/api/gold/history/**", "/api/silver/history/**").permitAll()

                        // AI 서버 상태 확인 API (공개)
                        .requestMatchers("/api/ai/status").permitAll()

                        // Actuator health check
                        .requestMatchers("/actuator/health/**").permitAll()

                        // Docker healthcheck 전용 (actuator/health 매핑 이슈 우회)
                        .requestMatchers("/api/health").permitAll()

                        // phase 32~35 — 시스템 검증/분석 API. 종목별 매매 정보가 아니라 시스템
                        // 성능 메타데이터(시그널 hit-rate, alpha, 카테고리 빈도) 라 외부 노출 OK.
                        // 운영자가 토큰 없이 curl 로 빠르게 검증할 수 있도록 permitAll.
                        .requestMatchers(
                                "/api/signal-outcomes/accuracy",
                                "/api/signal-outcomes/compare",
                                "/api/signal-outcomes/timeseries",
                                "/api/recommendation/strong-value-frequency",
                                "/api/diagnostics/**"
                        ).permitAll()

                        // 테스트 API - public 엔드포인트
                        .requestMatchers("/api/test/public").permitAll()

                        // Swagger UI
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()

                        // SSE (Server-Sent Events) - JwtAuthenticationFilter가 ?token= 쿼리 파라미터 허용
                        .requestMatchers("/api/sse/**").authenticated()

                        // 자동매매 봇 + 실전투자 API - ADMIN만 허용
                        .requestMatchers("/api/paper-trading/bot/**").hasRole("ADMIN")
                        .requestMatchers("/api/paper-trading/bot-performance").hasRole("ADMIN")
                        .requestMatchers("/api/paper-trading/real/**").hasRole("ADMIN")
                        .requestMatchers("/api/investor/test-api").hasRole("ADMIN")
                        .requestMatchers("/api/investor/collect").hasRole("ADMIN")

                        // Telegram 메시지 발송 — 운영자 전용. 일반 인증만이면 임의 사용자가 봇 알림 폭주 가능.
                        .requestMatchers("/api/telegram/**").hasRole("ADMIN")

                        // Admin API는 ADMIN 권한 필요
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/test/admin").hasRole("ADMIN")
                        // 사용자 승인 관리는 ADMIN만 가능
                        .requestMatchers("/api/user/pending", "/api/user/*/approval").hasRole("ADMIN")

                        // 나머지 API는 인증 필요
                        .requestMatchers("/api/**").authenticated()

                        // API가 아닌 모든 경로는 허용 (Vue.js SPA)
                        // WebMvcConfig가 이들을 index.html로 포워딩하고
                        // Vue Router의 beforeEach가 클라이언트에서 인증 체크함
                        .anyRequest().permitAll()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);


        return http.build();
    }
}

