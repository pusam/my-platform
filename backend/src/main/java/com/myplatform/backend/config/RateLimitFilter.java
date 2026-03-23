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
 * - /api/auth/** 엔드포인트: 1분당 20회 제한 (로그인, 회원가입, 인증번호 발송)
 * - /api/password/** 엔드포인트: 1분당 10회 제한
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RateLimitFilter extends OncePerRequestFilter {

    // IP별 요청 카운트 (1분 TTL)
    private final Cache<String, AtomicInteger> authRateCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(10000)
            .build();

    private final Cache<String, AtomicInteger> passwordRateCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(10000)
            .build();

    private static final int AUTH_LIMIT = 20;       // 인증 API: 분당 20회
    private static final int PASSWORD_LIMIT = 10;   // 비밀번호 API: 분당 10회

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String clientIp = getClientIp(request);

        if (path.startsWith("/api/auth/")) {
            if (isRateLimited(authRateCache, clientIp, AUTH_LIMIT)) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해주세요.\"}");
                return;
            }
        } else if (path.startsWith("/api/password/")) {
            if (isRateLimited(passwordRateCache, clientIp, PASSWORD_LIMIT)) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해주세요.\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isRateLimited(Cache<String, AtomicInteger> cache, String ip, int limit) {
        AtomicInteger count = cache.get(ip, k -> new AtomicInteger(0));
        return count.incrementAndGet() > limit;
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
