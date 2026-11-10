package com.myplatform.backend.config;

import com.myplatform.jwtredis.entrypoint.JwtAuthenticationEntryPoint;
import com.myplatform.jwtredis.filter.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
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
                        // ── 로그인 없이 어디서나 — 로그인 전 화면이 쓰는 것과 헬스체크뿐 (2026-11-10 정리) ──
                        // 목록은 ApiAccessPolicyTest.PUBLIC 이 고정한다. 공개 주소를 늘리면 그 테스트가 실패한다.
                        // 인증 API (로그인, 회원가입, 토큰 갱신 등)
                        .requestMatchers("/api/auth/**").permitAll()

                        // 비밀번호 재설정 API — 로그인 못 하는 사람이 쓴다
                        .requestMatchers("/api/password/**").permitAll()

                        // WebAuthn 로그인 / 패스키 — 비로그인 상태에서 호출 필요
                        .requestMatchers("/api/webauthn/login/**").permitAll()
                        .requestMatchers("/api/webauthn/passkey/**").permitAll()
                        // WebAuthn 등록/관리 — 로그인 필요
                        .requestMatchers("/api/webauthn/**").authenticated()

                        // 헬스체크 — 컨테이너(curl localhost:8080/actuator/health)·배포(curl localhost:8080/api/health)
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/health").permitAll()

                        // ── 서버 안에서만 로그인 없이 (InternalCaller: 루프백·사설 주소 + nginx 표시 헤더 없음) ──
                        // 운영자 점검(서버 안 curl). 밖에서는 관리자만 — 진단은 화면이 쓰지 않는다.
                        // /data?refresh=true 는 종합추천 재계산을 일으키고, price-scaling·stale-feeds 는 시세 이력을 크게 읽는다.
                        .requestMatchers("/api/diagnostics/**").access(AuthorizationManagers.anyOf(
                                InternalCaller.authorizationManager(), AuthorityAuthorizationManager.hasRole("ADMIN")))
                        // 액추에이터의 health 밖(prometheus·metrics·info)과 API 문서 — 서버 안에서만 (prod 는 API 문서 꺼짐)
                        .requestMatchers("/actuator/**", "/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml")
                                .access(InternalCaller.authorizationManager())
                        // 서버 안에서는 토큰 없이, 밖에서는 로그인 사용자:
                        //  · KOSPI 지수 일봉 — python 시장 국면·섹터 강도가 토큰 없이 부른다(python app/utils/index_source.py)
                        //  · 측정 메타데이터 — 예전엔 '운영자 curl 검증용'으로 누구에게나 열려 있었다. 화면은 로그인한 채로 부른다.
                        .requestMatchers(
                                "/api/market/index/**",
                                "/api/signal-outcomes/accuracy",
                                "/api/signal-outcomes/compare",
                                "/api/signal-outcomes/timeseries",
                                "/api/recommendation/strong-value-frequency",
                                "/api/recommendation/sector-strength",
                                "/api/recommendation/trend-pullback-top10"
                        ).access(AuthorizationManagers.anyOf(
                                InternalCaller.authorizationManager(), AuthenticatedAuthorizationManager.authenticated()))

                        // SSE (Server-Sent Events) - JwtAuthenticationFilter가 ?token= 쿼리 파라미터 허용
                        .requestMatchers("/api/sse/**").authenticated()

                        // ── 수집·배치를 일으키는 쓰기 주소 — ADMIN 만 (2026-11-10) ──
                        // 재무 배치(약 38분, 추천 입력을 바꾼다)·성장률 계산·종목 단건 수집 — 스크리너의 POST 는 전부 수집이다
                        .requestMatchers(HttpMethod.POST, "/api/screener/**").hasRole("ADMIN")
                        .requestMatchers("/api/market/collect").hasRole("ADMIN")
                        .requestMatchers("/api/ai-strategy/collect", "/api/ai-strategy/collect/**").hasRole("ADMIN")
                        .requestMatchers("/api/short-selling/collect").hasRole("ADMIN")
                        .requestMatchers("/api/sector/trading/refresh").hasRole("ADMIN")
                        .requestMatchers("/api/paper-trading/portfolio/refresh").hasRole("ADMIN")
                        // 공유 모의계좌 수동 매수·매도 — 화면(매매 탭)은 관리자 전용. 거래 내역 조회(GET)는 로그인 사용자 그대로.
                        .requestMatchers(HttpMethod.POST, "/api/paper-trading/trades").hasRole("ADMIN")
                        // QuantTaController 의 @PreAuthorize 는 무효다(@EnableMethodSecurity 없음) — 여기가 실제 게이트
                        .requestMatchers(HttpMethod.POST, "/api/quant-ta/collect-history", "/api/quant-ta/backfill-names")
                                .hasRole("ADMIN")

                        // 자동매매 봇 + 실전투자 API - ADMIN만 허용
                        // V59 교정 평가 실행·비교표 — KIS 호출·DB 쓰기라 ADMIN 한정(2026-09-17)
                        .requestMatchers("/api/signal-outcomes/d3/**").hasRole("ADMIN")
                        .requestMatchers("/api/paper-trading/bot/**").hasRole("ADMIN")
                        .requestMatchers("/api/paper-trading/bot-performance").hasRole("ADMIN")
                        .requestMatchers("/api/paper-trading/real/**").hasRole("ADMIN")
                        .requestMatchers("/api/investor/test-api").hasRole("ADMIN")
                        // 수집/재수집 계열 전부 ADMIN — 특히 recollect 는 investor 테이블 전량 삭제 후
                        // 재수집(파괴적)이라 정확매칭 "collect" 하나만 막던 구멍을 봉합.
                        .requestMatchers("/api/investor/collect", "/api/investor/collect/**",
                                "/api/investor/recollect", "/api/investor/surge/collect",
                                "/api/investor/export/**").hasRole("ADMIN")
                        // 공유 모의계좌 리셋(잔액 초기화) — 파괴적 쓰기라 ADMIN 한정
                        .requestMatchers("/api/paper-trading/account/initialize").hasRole("ADMIN")

                        // Telegram 메시지 발송 — 운영자 전용. 일반 인증만이면 임의 사용자가 봇 알림 폭주 가능.
                        .requestMatchers("/api/telegram/**").hasRole("ADMIN")

                        // 판정 관제실 — 운영자 전용 콘솔. 감사 플래그·불변식 같은 내부 상태를 노출하고
                        // 크루 세션은 유료 LLM 호출이라 일반 인증만으로는 부족하다.
                        // ⚠ 이 URL 규칙이 실제 게이트다 — ControlRoomController 의 @PreAuthorize 는
                        //    @EnableMethodSecurity 가 없어 현재 동작하지 않는다(코드베이스 전체 동일).
                        .requestMatchers("/api/control-room/**").hasRole("ADMIN")

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

