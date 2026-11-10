package com.myplatform.backend.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * V2 API 폴백 컨트롤러
 * - V2 API는 Python FastAPI 마이크로서비스가 처리한다. 백엔드가 도커 내부망으로 직접 부른다.
 * - nginx 는 2026-11-10 부터 밖에서 오는 /api/v2 를 403 으로 막는다(화면은 이 경로를 쓰지 않는다).
 * - nginx 를 거치지 않고 /api/v2 요청이 Java로 도달하면 503 JSON 응답 반환
 * - NoResourceFoundException 스택 트레이스 방지
 */
@RestController
@RequestMapping("/api/v2")
@Slf4j
public class V2FallbackController {

    @RequestMapping("/**")
    public ResponseEntity<Map<String, Object>> fallback() {
        log.debug("V2 API 요청이 Java 백엔드로 도달 - Python 백엔드 미실행 또는 nginx 미경유");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "success", false,
                        "message", "V2 API 서비스를 사용할 수 없습니다."
                ));
    }
}
