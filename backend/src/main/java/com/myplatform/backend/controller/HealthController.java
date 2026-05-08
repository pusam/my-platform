package com.myplatform.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Docker healthcheck 전용 경량 endpoint.
 * Spring Boot 4.0 의 actuator/health 가 새 access 모델 도입으로 매핑 누락 → Docker 헬스체크 unhealthy.
 * actuator 디버깅과 별개로 컨테이너 정상 마킹 위해 자체 ping 추가.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public Map<String, String> ping() {
        return Map.of("status", "UP");
    }
}
