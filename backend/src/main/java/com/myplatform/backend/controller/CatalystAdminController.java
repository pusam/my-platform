package com.myplatform.backend.controller;

import com.myplatform.backend.service.CatalystWarmingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 재료 워밍 수동 트리거 (P2-CAT3) — ADMIN 전용(SecurityConfig `/api/admin/**` → hasRole ADMIN).
 * 08:00 크론과 동일 로직을 즉시 실행(락 없이). Gemini quota 소모하므로 인증 필수.
 */
@RestController
@RequestMapping("/api/admin/catalyst")
@RequiredArgsConstructor
@Slf4j
public class CatalystAdminController {

    private final CatalystWarmingService catalystWarmingService;

    /** union 상위 재료 일괄 워밍 즉시 실행(테스트/ops). @return 신규 분류 저장 수. */
    @PostMapping("/warm-union")
    public ResponseEntity<Map<String, Object>> warmUnion() {
        log.info("[Admin] union 재료 워밍 수동 트리거");
        int warmed = catalystWarmingService.warmUnionCatalysts();
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("warmed", warmed);
        return ResponseEntity.ok(body);
    }
}
