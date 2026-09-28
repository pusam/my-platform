package com.myplatform.backend.youtubeopinion;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import static com.myplatform.backend.youtubeopinion.YoutubeOpinionDtos.*;

/**
 * 유튜브 의견 관리자 API.
 *
 * <p><b>권한은 SecurityConfig 의 URL 규칙({@code /api/admin/**} → hasRole ADMIN)이 담당한다</b> — 이 코드베이스엔
 * {@code @EnableMethodSecurity} 가 없어 {@code @PreAuthorize} 는 무효다(CLAUDE.md §7). 회귀는
 * {@code YoutubeOpinionSecurityTest} 가 실제 필터 체인으로 고정한다.
 */
@RestController
@RequestMapping("/api/admin/youtube-opinions")
@RequiredArgsConstructor
public class YoutubeOpinionAdminController {

    /** 자막 요청 본문 상한 — 파서 상한(512KB)에 JSON 여유분. 본문을 다 읽기 전에 끊는다. */
    static final int MAX_TRANSCRIPT_BODY_BYTES = TranscriptParser.MAX_BYTES + 64 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final YoutubeOpinionAdminService admin;
    private final YoutubeOpinionAnalysisService analysis;

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        return ok(admin.config());
    }

    @GetMapping("/videos")
    public ResponseEntity<Map<String, Object>> videos() {
        return ok(admin.videos());
    }

    @PostMapping("/videos")
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest req, Authentication auth) {
        return ok(admin.register(req, name(auth)));
    }

    /** 본문을 상한까지만 읽는다 — 큰 요청을 메모리에 다 올린 뒤 거절하지 않게. */
    @PostMapping(path = "/videos/{videoId}/transcript", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> transcript(@PathVariable String videoId, HttpServletRequest request,
                                                          Authentication auth) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_TRANSCRIPT_BODY_BYTES) return fail(HttpStatus.PAYLOAD_TOO_LARGE, "자막이 너무 큽니다(최대 512KB).");
        byte[] body = readLimited(request.getInputStream(), MAX_TRANSCRIPT_BODY_BYTES);
        if (body == null) return fail(HttpStatus.PAYLOAD_TOO_LARGE, "자막이 너무 큽니다(최대 512KB).");
        TranscriptRequest req;
        try {
            req = MAPPER.readValue(body, TranscriptRequest.class);
        } catch (IOException e) {
            return fail(HttpStatus.BAD_REQUEST, "요청 본문이 JSON({format, content})이 아닙니다.");
        }
        return ok(admin.uploadTranscript(videoId, req, name(auth)));
    }

    @PostMapping("/videos/{videoId}/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@PathVariable String videoId, Authentication auth) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body(analysis.start(videoId, name(auth))));
    }

    @GetMapping("/videos/{videoId}/runs")
    public ResponseEntity<Map<String, Object>> runs(@PathVariable String videoId) {
        return ok(admin.runs(videoId));
    }

    @GetMapping("/review")
    public ResponseEntity<Map<String, Object>> review() {
        return ok(admin.reviewQueue());
    }

    @PostMapping("/opinions/{id}/review")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable long id, @RequestBody ReviewRequest req, Authentication auth) {
        return ok(admin.review(id, req, name(auth)));
    }

    // ------------------------------------------------------------------ 오류 → 상태코드

    @ExceptionHandler(FeatureDisabledException.class)
    public ResponseEntity<Map<String, Object>> disabled(FeatureDisabledException e) {
        return fail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Map<String, Object>> tooMany(TooManyRequestsException e) {
        return fail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return fail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return fail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException e) {
        return fail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    static byte[] readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0, n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String name(Authentication auth) {
        return auth == null ? null : auth.getName();
    }

    private static Map<String, Object> body(Object data) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(body(data));
    }

    private static ResponseEntity<Map<String, Object>> fail(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
