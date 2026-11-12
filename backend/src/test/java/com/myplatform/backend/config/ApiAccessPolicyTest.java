package com.myplatform.backend.config;

import com.myplatform.jwtredis.entrypoint.JwtAuthenticationEntryPoint;
import com.myplatform.jwtredis.filter.JwtAuthenticationFilter;
import com.myplatform.jwtredis.provider.JwtTokenProvider;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * 누가 어느 주소를 부를 수 있는지를 <b>실제 SecurityConfig 필터 체인</b>으로 고정한다 (2026-11-10).
 *
 * <p>앱의 모든 컨트롤러 주소를 리플렉션으로 모아 필터 체인에 그대로 흘려 보낸다. 컨트롤러 대신 모든 경로에 200 을 돌려주는
 * 가짜 하나만 등록하므로, 응답이 200 이면 '권한 규칙이 통과시켰다', 401·403 이면 '막았다'는 뜻이다.
 * <ul>
 *   <li>밖(nginx 를 거친 요청 — {@code X-Real-IP} 가 붙는다)에서 로그인 없이 닿는 주소는 {@link #PUBLIC} 와 정확히 같아야 한다.
 *       새 공개 주소가 생기면 이 테스트가 실패한다 — 정말 공개해야 하면 이유와 함께 목록에 넣을 것.</li>
 *   <li>서버 안(루프백·사설 주소, {@code X-Real-IP} 없음)에서 로그인 없이 닿는 주소는 {@link #PUBLIC} + {@link #INTERNAL} 이다 —
 *       운영자 {@code curl localhost:8080/api/diagnostics/...} 점검과 python 의 지수 일봉 호출이 여기 든다.</li>
 *   <li>수집·배치를 일으키는 주소는 관리자만({@link #ADMIN_ONLY}).</li>
 * </ul>
 * 실행하면 전체 실효 권한 표를 {@code build/reports/api-access-table.md} 에 남긴다.
 */
class ApiAccessPolicyTest {

    static final JwtTokenProvider TOKENS =
            new JwtTokenProvider("test-only-secret-for-api-access-policy-0123456789abcdef", 60_000, 60_000, 120_000);

    /** 밖에서 로그인 없이 닿는 주소 — 로그인 전 화면이 쓰는 것과 도커·배포 헬스체크뿐. */
    static final Set<String> PUBLIC = Set.of(
            // 로그인·회원가입·토큰 갱신·로그아웃 — 로그인 전(또는 토큰 만료 뒤) 화면이 부른다
            "POST /api/auth/login", "POST /api/auth/logout", "POST /api/auth/refresh",
            "POST /api/auth/send-verification", "POST /api/auth/signup", "POST /api/auth/verify-email",
            // 비밀번호 재설정 — 로그인 못 하는 사람이 쓴다
            "POST /api/password/reset/send", "POST /api/password/reset/verify", "POST /api/password/reset/confirm",
            // 패스키 로그인 — 로그인 전 화면
            "POST /api/webauthn/login/options", "POST /api/webauthn/login/verify",
            "POST /api/webauthn/passkey/options", "POST /api/webauthn/passkey/verify",
            // 배포 헬스체크(deploy.yml 'localhost:8080/api/health')
            "GET /api/health");

    /** 서버 안에서만 로그인 없이 닿는 주소 — 밖에서는 로그인(진단은 관리자)이 필요하다. */
    static final Set<String> INTERNAL = Set.of(
            // 운영자 점검(서버 안 curl). 밖에서는 관리자만.
            "GET /api/diagnostics/data", "GET /api/diagnostics/earnings-source", "GET /api/diagnostics/price-scaling",
            "GET /api/diagnostics/python-health", "GET /api/diagnostics/stale-feeds",
            // python 시장 국면·섹터 강도가 토큰 없이 부른다(app/utils/index_source.py)
            "GET /api/market/index/kospi-daily",
            // 운영자 curl 검증용으로 열어 두었던 측정 메타데이터 — 화면은 로그인한 채로 부른다
            "GET /api/signal-outcomes/accuracy", "GET /api/signal-outcomes/compare", "GET /api/signal-outcomes/timeseries",
            "GET /api/recommendation/strong-value-frequency", "GET /api/recommendation/sector-strength",
            "GET /api/recommendation/trend-pullback-top10");

    /** 수집·배치를 일으키는 주소 — 일반 사용자 403, 관리자 통과. */
    static final List<String> ADMIN_ONLY = List.of(
            "POST /api/screener/collect-all-in-one", "POST /api/screener/collect-all",
            "POST /api/screener/collect/005930", "POST /api/screener/calculate-growth",
            "POST /api/market/collect",
            "POST /api/ai-strategy/collect", "POST /api/ai-strategy/collect/SWING",
            "POST /api/investor/collect", "POST /api/investor/surge/collect",
            "POST /api/short-selling/collect",
            "POST /api/sector/trading/refresh",
            "POST /api/paper-trading/portfolio/refresh",
            // 공유 모의계좌 수동 매매(수집은 아니지만 공유 상태를 바꾼다 — 2026-11-10 사용자 결정)
            "POST /api/paper-trading/trades",
            "POST /api/quant-ta/collect-history", "POST /api/quant-ta/backfill-names",
            // 외부 공개 수치 대조 수동 실행 — 외부 호출을 일으킨다(관제실 밖 /api/admin, 관제실은 읽기 전용)
            "POST /api/admin/crosscheck/run",
            "GET /api/diagnostics/data", "GET /api/diagnostics/price-scaling");

    @RestController
    static class AnyPath {
        @RequestMapping("/**")
        ResponseEntity<String> any() { return ResponseEntity.ok("ok"); }
    }

    /** {@code @TestConfiguration} — 앱 스캔 범위 패키지라 일반 {@code @Configuration} 이면 스모크 테스트에 끼어든다. */
    @TestConfiguration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class Web {
        @Bean JwtTokenProvider jwtTokenProvider() { return TOKENS; }

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

        @Bean JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint() { return new JwtAuthenticationEntryPoint(); }

        @Bean AnyPath anyPath() { return new AnyPath(); }
    }

    static AnnotationConfigWebApplicationContext ctx;
    static MockMvc mvc;
    static final String ADMIN = "Bearer " + TOKENS.generateAccessToken("test-admin");
    static final String USER = "Bearer " + TOKENS.generateAccessToken("test-user");

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
    static void stop() { ctx.close(); }

    // ---------------------------------------------------------------- 요청 모양

    enum Caller {
        /** nginx 를 거친 밖의 요청 — 백엔드가 보는 주소는 nginx 컨테이너(사설)이고 nginx 가 X-Real-IP 를 붙인다. */
        EXTERNAL_VIA_NGINX("172.18.0.9", "203.0.113.7"),
        /** 같은 요청을 Tomcat RemoteIpValve 가 클라이언트 주소로 바꾼 모양(운영 forward-headers-strategy: native). */
        EXTERNAL_REWRITTEN("203.0.113.7", "203.0.113.7"),
        /** 서버 안 curl localhost:8080 (호스트에서 오면 도커 게이트웨이 주소로 들어온다). */
        HOST_CURL("172.18.0.1", null),
        /** 컨테이너 안 curl / 헬스체크. */
        LOOPBACK("127.0.0.1", null),
        /** python-backend 컨테이너(도커 내부망). */
        PYTHON("172.18.0.5", null);

        final String remoteAddr;
        final String realIp;

        Caller(String remoteAddr, String realIp) { this.remoteAddr = remoteAddr; this.realIp = realIp; }
    }

    static int status(String endpoint, Caller caller, String bearer) throws Exception {
        int sp = endpoint.indexOf(' ');
        MockHttpServletRequestBuilder req = request(HttpMethod.valueOf(endpoint.substring(0, sp)), endpoint.substring(sp + 1));
        String remote = caller.remoteAddr;
        req.with(r -> { r.setRemoteAddr(remote); return r; });
        if (caller.realIp != null) req.header("X-Real-IP", caller.realIp);
        if (bearer != null) req.header("Authorization", bearer);
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    static boolean allowed(int status) { return status >= 200 && status < 300; }

    // ---------------------------------------------------------------- 컨트롤러 주소 모으기

    /** 앱의 모든 컨트롤러 주소("METHOD /path", 경로 변수는 예시 값으로). */
    static List<String> controllerEndpoints() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        Set<String> out = new TreeSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.myplatform")) {
            Class<?> cls = Class.forName(bd.getBeanClassName());
            if (cls.getEnclosingClass() != null || isTestClass(cls)) continue;
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(cls, RequestMapping.class);
            String[] prefixes = base == null || base.path().length == 0 ? new String[]{""} : base.path();
            for (Method m : cls.getDeclaredMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) continue;
                String[] paths = rm.path().length == 0 ? new String[]{""} : rm.path();
                RequestMethod[] methods = rm.method().length == 0 ? new RequestMethod[]{RequestMethod.GET} : rm.method();
                for (String prefix : prefixes) for (String p : paths) for (RequestMethod verb : methods) {
                    out.add(verb.name() + " " + sample(prefix + p));
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** 테스트 소스의 가짜 컨트롤러는 뺀다(이름에 Test 가 든 진짜 컨트롤러 {@code TestController} 는 남긴다). */
    static boolean isTestClass(Class<?> cls) {
        var src = cls.getProtectionDomain().getCodeSource();
        String loc = src == null ? "" : String.valueOf(src.getLocation()).replace('\\', '/');
        return loc.contains("/classes/java/test") || loc.contains("/test-classes");
    }

    /** {@code {code:\d{6}}} 처럼 중괄호가 겹친 경로 변수도 예시 값 하나로 바꾼다. */
    static String sample(String path) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char c : path.toCharArray()) {
            if (c == '{') { if (depth++ == 0) sb.append("005930"); continue; }
            if (c == '}') { depth--; continue; }
            if (depth == 0) sb.append(c);
        }
        String s = sb.toString().replace("//", "/");
        return s.isEmpty() ? "/" : s;
    }

    /** API 주소만 — 화면 경로(SpaController 의 /{path} 포워드)는 따로 본다. */
    static List<String> apiEndpoints() throws ClassNotFoundException {
        return controllerEndpoints().stream().filter(e -> e.contains(" /api/")).toList();
    }

    // ---------------------------------------------------------------- 테스트

    @Test
    @DisplayName("컨트롤러 주소를 실제로 모았다 (리플렉션이 비면 아래 검사가 헛통과한다)")
    void scannedEndpointsAreReal() throws Exception {
        List<String> all = controllerEndpoints();
        assertThat(all.size()).isGreaterThan(250);
        assertThat(all).contains("POST /api/auth/login", "GET /api/diagnostics/price-scaling", "POST /api/screener/collect-all-in-one");
        assertThat(all).containsAll(PUBLIC).containsAll(INTERNAL);
    }

    @Nested
    @DisplayName("로그인 없이 닿는 주소 목록")
    class OpenList {

        @Test
        @DisplayName("밖(nginx 경유)에서 로그인 없이 닿는 컨트롤러 주소 = PUBLIC 과 정확히 같다")
        void externalAnonymousIsExactlyPublic() throws Exception {
            Set<String> open = new TreeSet<>();
            for (String e : apiEndpoints()) if (allowed(status(e, Caller.EXTERNAL_VIA_NGINX, null))) open.add(e);
            assertThat(open).containsExactlyInAnyOrderElementsOf(PUBLIC);
        }

        @Test
        @DisplayName("서버 안(로컬 curl)에서 로그인 없이 닿는 컨트롤러 주소 = PUBLIC + INTERNAL")
        void internalAnonymousIsPublicPlusInternal() throws Exception {
            Set<String> expected = new TreeSet<>(PUBLIC);
            expected.addAll(INTERNAL);
            Set<String> open = new TreeSet<>();
            for (String e : apiEndpoints()) if (allowed(status(e, Caller.HOST_CURL, null))) open.add(e);
            assertThat(open).containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        @DisplayName("서버 안 점검·python 내부 호출은 계속 된다 — 루프백·게이트웨이·python 컨테이너")
        void internalCallersKeepWorking() throws Exception {
            for (Caller c : List.of(Caller.LOOPBACK, Caller.HOST_CURL, Caller.PYTHON)) {
                assertThat(status("GET /api/diagnostics/price-scaling", c, null)).as(c.name()).isEqualTo(200);
                assertThat(status("GET /api/diagnostics/data", c, null)).as(c.name()).isEqualTo(200);
                assertThat(status("GET /api/market/index/kospi-daily", c, null)).as(c.name()).isEqualTo(200);
                assertThat(status("GET /actuator/health", c, null)).as(c.name()).isEqualTo(200);
                assertThat(status("GET /actuator/prometheus", c, null)).as(c.name()).isEqualTo(200);
            }
        }

        @Test
        @DisplayName("밖에서는 진단·지수 일봉·액추에이터 내부 지표가 로그인 없이 닫힌다 — nginx 를 거쳤든 주소가 바뀌었든")
        void externalAnonymousClosed() throws Exception {
            for (Caller c : List.of(Caller.EXTERNAL_VIA_NGINX, Caller.EXTERNAL_REWRITTEN)) {
                assertThat(status("GET /api/diagnostics/price-scaling", c, null)).as(c.name()).isEqualTo(401);
                assertThat(status("GET /api/diagnostics/data", c, null)).as(c.name()).isEqualTo(401);
                assertThat(status("GET /api/market/index/kospi-daily", c, null)).as(c.name()).isEqualTo(401);
                assertThat(status("GET /api/signal-outcomes/accuracy", c, null)).as(c.name()).isEqualTo(401);
                assertThat(status("GET /api/gold/price", c, null)).as(c.name()).isEqualTo(401);
                assertThat(status("GET /actuator/prometheus", c, null)).as(c.name()).isEqualTo(401);
                assertThat(status("GET /v3/api-docs", c, null)).as(c.name()).isEqualTo(401);
                // 공개로 남긴 것
                assertThat(status("GET /actuator/health", c, null)).as(c.name()).isEqualTo(200);
                assertThat(status("GET /api/health", c, null)).as(c.name()).isEqualTo(200);
                assertThat(status("GET /", c, null)).as("화면(SPA) " + c.name()).isEqualTo(200);
                assertThat(status("GET /stock-dashboard", c, null)).as("화면 경로 포워드 " + c.name()).isEqualTo(200);
            }
        }

        @Test
        @DisplayName("밖에서 로그인한 일반 사용자는 화면이 쓰는 측정 주소는 읽고, 진단은 못 읽는다(관리자만)")
        void externalUserAndAdmin() throws Exception {
            Caller c = Caller.EXTERNAL_REWRITTEN;
            assertThat(status("GET /api/signal-outcomes/accuracy", c, USER)).isEqualTo(200);
            assertThat(status("GET /api/recommendation/sector-strength", c, USER)).isEqualTo(200);
            assertThat(status("GET /api/gold/price", c, USER)).isEqualTo(200);
            assertThat(status("GET /api/diagnostics/price-scaling", c, USER)).isEqualTo(403);
            assertThat(status("GET /api/diagnostics/price-scaling", c, ADMIN)).isEqualTo(200);
            assertThat(status("GET /actuator/prometheus", c, ADMIN)).as("액추에이터 내부 지표는 서버 안에서만").isEqualTo(403);
        }
    }

    @Nested
    @DisplayName("내 사이트 바로가기(/api/site-links) — 어떤 사이트가 떠 있는지는 로그인한 사람에게만")
    class SiteLinks {

        static final List<String> ENDPOINTS = List.of("GET /api/site-links", "POST /api/site-links",
                "PUT /api/site-links/005930", "DELETE /api/site-links/005930");

        @Test
        @DisplayName("컨트롤러 주소가 실제로 있다 (이름이 바뀌면 아래 검사가 헛통과한다)")
        void endpointsExist() throws Exception {
            assertThat(controllerEndpoints()).containsAll(ENDPOINTS);
        }

        @Test
        @DisplayName("로그인 없이는 밖에서도 서버 안에서도 401")
        void anonymousClosedEverywhere() throws Exception {
            for (String e : ENDPOINTS) {
                for (Caller c : Caller.values()) {
                    assertThat(status(e, c, null)).as("anon " + c.name() + " " + e).isEqualTo(401);
                }
            }
        }

        @Test
        @DisplayName("로그인한 사용자는 닿는다 — 본인 행만 주는 것은 SiteLinkServiceTest 가 본다")
        void loggedInAllowed() throws Exception {
            for (String e : ENDPOINTS) {
                assertThat(status(e, Caller.EXTERNAL_REWRITTEN, USER)).as("user " + e).isEqualTo(200);
                assertThat(status(e, Caller.EXTERNAL_REWRITTEN, ADMIN)).as("admin " + e).isEqualTo(200);
            }
        }
    }

    @Nested
    @DisplayName("수집·배치를 일으키는 주소 — 관리자만")
    class Collectors {

        @Test
        @DisplayName("로그인 없음 401 · 일반 사용자 403 · 관리자 통과")
        void adminOnly() throws Exception {
            for (String e : ADMIN_ONLY) {
                assertThat(status(e, Caller.EXTERNAL_REWRITTEN, null)).as("anon " + e).isEqualTo(401);
                assertThat(status(e, Caller.EXTERNAL_REWRITTEN, USER)).as("user " + e).isEqualTo(403);
                assertThat(status(e, Caller.EXTERNAL_REWRITTEN, ADMIN)).as("admin " + e).isEqualTo(200);
            }
        }

        @Test
        @DisplayName("서버 안이라도 수집 주소는 로그인 없이 못 부른다 — 내부 허용은 읽기 주소에만")
        void internalDoesNotOpenCollectors() throws Exception {
            for (String e : ADMIN_ONLY) {
                if (e.startsWith("GET /api/diagnostics")) continue;
                assertThat(status(e, Caller.HOST_CURL, null)).as("internal anon " + e).isEqualTo(401);
            }
        }

        @Test
        @DisplayName("같은 컨트롤러의 읽기 주소는 그대로 로그인 사용자에게 열려 있다")
        void readsStayOpenForUsers() throws Exception {
            Caller c = Caller.EXTERNAL_REWRITTEN;
            assertThat(status("GET /api/quant-ta/collect-history/progress", c, USER)).isEqualTo(200);
            assertThat(status("GET /api/screener/collect-status", c, USER)).isEqualTo(200);
            assertThat(status("GET /api/ai-strategy/latest", c, USER)).isEqualTo(200);
            assertThat(status("GET /api/paper-trading/portfolio", c, USER)).isEqualTo(200);
            assertThat(status("GET /api/paper-trading/trades", c, USER)).as("거래 내역 조회").isEqualTo(200);
        }
    }

    @Test
    @DisplayName("실효 권한 표를 build/reports/api-access-table.md 에 남긴다")
    void writeAccessTable() throws Exception {
        TreeMap<String, String> rows = new TreeMap<>();
        for (String e : controllerEndpoints()) {
            if (e.startsWith("GET ")) continue; // 표는 쓰기 주소만
            rows.put(e, label(status(e, Caller.EXTERNAL_REWRITTEN, null)) + " | " + label(status(e, Caller.HOST_CURL, null))
                    + " | " + label(status(e, Caller.EXTERNAL_REWRITTEN, USER)) + " | " + label(status(e, Caller.EXTERNAL_REWRITTEN, ADMIN)));
        }
        StringBuilder sb = new StringBuilder("| 주소 | 밖·비로그인 | 서버 안·비로그인 | 일반 사용자 | 관리자 |\n|---|---|---|---|---|\n");
        rows.forEach((k, v) -> sb.append("| `").append(k).append("` | ").append(v).append(" |\n"));
        Path out = Path.of("build", "reports", "api-access-table.md");
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 표는 참고용 — 쓰기 실패가 권한 검사를 깨지 않게
        }
        assertThat(rows).isNotEmpty();
    }

    static String label(int status) {
        return allowed(status) ? "허용" : String.valueOf(status);
    }
}
