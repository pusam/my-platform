package com.myplatform.backend.crosscheck;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 외부 대조 — 수동 실행(관리자)과 최근 결과 조회(관제실). 권한은 SecurityConfig URL 규칙이 건다
 * ({@code /api/admin/**}·{@code /api/control-room/**} 둘 다 ADMIN — {@code @PreAuthorize} 는 이 코드베이스에서 무효다).
 * 실행은 관제실 밖에 둔다 — 관제실은 읽기 전용 레이어다(CLAUDE.md §7).
 */
@RestController
@RequiredArgsConstructor
public class ExternalCrosscheckController {

    private final ExternalCrosscheckService service;

    /**
     * 백그라운드로 한 번 돌린다(약 1분). 꺼져 있거나, 대조 창(거래일 장 시작 10분 뒤 ~ 장 마감 10분 전) 밖이거나, 오늘 3회를 다 썼거나,
     * 이미 도는 중이면 시작하지 않고 이유를 돌려준다.
     */
    @PostMapping("/api/admin/crosscheck/run")
    public ResponseEntity<Map<String, Object>> run() {
        String refused = service.startManualRun();
        return ResponseEntity.ok(Map.of(
                "started", refused == null,
                "message", refused == null ? "외부 대조를 시작했다 — 약 1분 뒤 /api/control-room/crosscheck 에서 결과를 본다" : refused));
    }

    @GetMapping("/api/control-room/crosscheck")
    public ResponseEntity<Map<String, Object>> overview() {
        return ResponseEntity.ok(service.overview());
    }
}
