package com.myplatform.backend.youtubeopinion;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import static com.myplatform.backend.youtubeopinion.YoutubeOpinionDtos.*;

/**
 * Claude 로컬 작업자 API(2026-10-01) — 임대·연장·결과·실패 보고. 작업자({@code tools/youtube-claude-worker})만 부른다.
 *
 * <p><b>권한은 SecurityConfig 의 URL 규칙({@code /api/admin/**} → ADMIN)이 담당한다</b> — 작업자는 관리자 계정으로 로그인한다.
 * 새 인증 수단(공유 비밀 헤더 등)을 만들지 않는다. 서버는 이 API 안에서도 Claude 를 부르지 않는다 — 응답 원문을 받아
 * Gemini 와 같은 경로로 해석·검증할 뿐이다.
 */
@RestController
@RequestMapping("/api/admin/youtube-opinions/worker")
@RequiredArgsConstructor
public class YoutubeOpinionWorkerController {

    private final YoutubeOpinionWorkerService worker;

    /** 다음 작업 임대 — 없으면 data=null(대기열 비었음·동시 실행 상한·대기 시각 전). */
    @PostMapping("/claim")
    public ResponseEntity<Map<String, Object>> claim(@RequestBody(required = false) WorkerClaimRequest req) {
        return ok(worker.claim(req == null ? null : req.workerId()).orElse(null));
    }

    @PostMapping("/runs/{runId}/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(@PathVariable long runId, @RequestBody WorkerLeaseRequest req) {
        return ok(Map.of("leaseUntil", worker.heartbeat(runId, req == null ? null : req.leaseToken())));
    }

    @PostMapping("/runs/{runId}/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable long runId, @RequestBody WorkerCompleteRequest req) {
        return ok(worker.complete(runId, req));
    }

    @PostMapping("/runs/{runId}/fail")
    public ResponseEntity<Map<String, Object>> fail(@PathVariable long runId, @RequestBody WorkerFailRequest req) {
        return ok(worker.fail(runId, req));
    }

    // ------------------------------------------------------------------ 오류 → 상태코드(관리자 API 와 같은 규약)

    @ExceptionHandler(FeatureDisabledException.class)
    public ResponseEntity<Map<String, Object>> disabled(FeatureDisabledException e) {
        return failBody(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return failBody(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 임대가 유효하지 않음(만료 뒤 다른 작업자가 집음·이미 끝남) — 작업자는 결과를 버리고 다음 작업으로 간다. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return failBody(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException e) {
        return failBody(HttpStatus.NOT_FOUND, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> failBody(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
