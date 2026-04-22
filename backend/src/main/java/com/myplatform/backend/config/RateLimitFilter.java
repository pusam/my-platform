package com.myplatform.backend.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IP 기반 Rate Limiting 필터
 *
 * 우선순위 (위에서부터 매칭, 더 엄격한 정책 우선):
 *  - /api/auth/login         : 분당 5회   (브루트포스 방지)
 *  - /api/auth/signup        : 분당 3회   (가입 폭격 방지)
 *  - /api/auth/send-*        : 분당 5회   (이메일/SMS 인증 폭격 방지)
 *  - /api/password/**        : 분당 10회
 *  - /api/auth/**            : 분당 20회
 *  - /api/**                 : 분당 300회 (전체 API 글로벌 제한)
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int CACHE_MAX = 50_000;

    private final Cache<String, AtomicInteger> loginCache    = newCache();
    private final Cache<String, AtomicInteger> signupCache   = newCache();
    private final Cache<String, AtomicInteger> sendCache     = newCache();
    private final Cache<String, AtomicInteger> passwordCache = newCache();
    private final Cache<String, AtomicInteger> authCache     = newCache();
    private final Cache<String, AtomicInteger> apiCache      = newCache();

    private static final int LOGIN_LIMIT    = 5;
    private static final int SIGNUP_LIMIT   = 3;
    private static final int SEND_LIMIT     = 5;
    private static final int PASSWORD_LIMIT = 10;
    private static final int AUTH_LIMIT     = 20;
    private static final int API_LIMIT      = 300;

    private static Cache<String, AtomicInteger> newCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(WINDOW)
                .maximumSize(CACHE_MAX)
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String ip = getClientIp(request);

        // 더 엄격한 정책부터 매칭
        if (path.startsWith("/api/auth/login")) {
            if (limited(loginCache, ip, LOGIN_LIMIT)) { reject(response); return; }
        } else if (path.startsWith("/api/auth/signup")) {
            if (limited(signupCache, ip, SIGNUP_LIMIT)) { reject(response); return; }
        } else if (path.startsWith("/api/auth/send-") || path.startsWith("/api/auth/verify")) {
            if (limited(sendCache, ip, SEND_LIMIT)) { reject(response); return; }
        } else if (path.startsWith("/api/password/")) {
            if (limited(passwordCache, ip, PASSWORD_LIMIT)) { reject(response); return; }
        } else if (path.startsWith("/api/auth/")) {
            if (limited(authCache, ip, AUTH_LIMIT)) { reject(response); return; }
        }

        // 모든 /api/** 요청에 대한 글로벌 상한 (헬스체크 제외)
        if (path.startsWith("/api/") && !path.startsWith("/api/sse/")) {
            if (limited(apiCache, ip, API_LIMIT)) { reject(response); return; }
        }

        filterChain.doFilter(request, response);
    }

    private boolean limited(Cache<String, AtomicInteger> cache, String ip, int limit) {
        AtomicInteger count = cache.get(ip, k -> new AtomicInteger(0));
        return count.incrementAndGet() > limit;
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", "60");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해주세요.\"}");
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }
}
