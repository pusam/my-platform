package com.myplatform.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "테스트", description = "테스트 API")
@RestController
@RequestMapping("/api/test")
public class TestController {

    @Operation(summary = "공개 테스트", description = "인증 없이 접근 가능한 테스트 API")
    @GetMapping("/public")
    public ResponseEntity<Map<String, Object>> publicTest() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Public API - 인증 없이 접근 가능");
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "인증 필요 테스트",
        description = "JWT 토큰이 필요한 테스트 API",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/secured")
    public ResponseEntity<Map<String, Object>> securedTest() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Secured API - 인증된 사용자만 접근 가능");
        response.put("username", authentication.getName());
        response.put("authorities", authentication.getAuthorities());
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "관리자 전용 테스트",
        description = "ADMIN 권한이 필요한 테스트 API",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/admin")
    public ResponseEntity<Map<String, Object>> adminTest() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Admin API - 관리자만 접근 가능");
        response.put("username", authentication.getName());
        response.put("authorities", authentication.getAuthorities());
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "현재 사용자 정보", description = "현재 로그인한 사용자 정보 조회")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("username", authentication.getName());
        response.put("authorities", authentication.getAuthorities());
        response.put("authenticated", authentication.isAuthenticated());
        return ResponseEntity.ok(response);
    }
}

