package com.myplatform.backend.controller;

import com.myplatform.backend.service.MacroTiltService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 매크로 tilt 보조 뷰 (P3-7, 미검증 · 표시 전용).
 *
 * <p>전용 슬림 컨트롤러 — 간밤 tilt 가 GlobalFuturesController 에 있는 건 futures 시세를
 * 재사용해서다(여긴 KIS 지수+ECOS 라 응집 근거 없음). 기본 authenticated(/api/** 규칙).
 */
@RestController
@RequestMapping("/api/macro-tilt")
@RequiredArgsConstructor
public class MacroTiltController {

    private final MacroTiltService macroTiltService;

    /** 매크로 tilt — {tilt, drivers, dataAvailable, asOf, unverified, note}. 30분 캐시 뷰. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getMacroTilt() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", macroTiltService.getMacroTiltView());
        return ResponseEntity.ok(body);
    }
}
