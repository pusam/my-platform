package com.myplatform.jwtredis.entrypoint;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationEntryPoint.class);

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                        AuthenticationException authException) throws IOException, ServletException {

        // 로그인 없는 요청의 401 은 클라이언트 상태지 서버 오류가 아니다(2026-11-09) — ERROR 였을 땐 로그인 전 화면 하나가
        // ERROR 6줄을 만들어 매일 보는 오류 건수를 가렸고, 어느 주소였는지는 남지 않았다. 경로만 남긴다(쿼리 문자열엔 값이 실릴 수 있다).
        logger.warn("Responding with unauthorized error. {} {} — {}",
                request.getMethod(), request.getRequestURI(), authException.getMessage());

        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // 고정 문구 — 예외 메시지를 이스케이프 없이 접합하면 따옴표/개행 포함 시 malformed JSON 이 되고,
        // 내부 인증 예외 문자열이 응답에 노출된다. 상세는 위 로그로만 남긴다.
        response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"인증이 필요합니다.\"}");
    }
}

