package com.myplatform.backend.youtubeopinion;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 유튜브 참고 의견 조회(로그인 사용자) — SecurityConfig 의 {@code /api/**} 인증 규칙이 적용된다.
 * 저장된 결과만 읽는다. 추천 점수·순위와 무관한 <b>외부 참고</b>다.
 */
@RestController
@RequestMapping("/api/youtube-opinions")
@RequiredArgsConstructor
public class YoutubeOpinionController {

    private final YoutubeOpinionQueryService query;

    @GetMapping("/stocks/{stockCode}")
    public ResponseEntity<Map<String, Object>> stock(@PathVariable String stockCode) {
        return ok(query.stockView(stockCode));
    }

    /** 여러 종목 요약 — '오늘' 매수 후보처럼 목록 화면용. {@code codes=005930,000660} (최대 20). */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam("codes") String codes) {
        List<String> list = Arrays.stream(codes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return ok(query.summary(list));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
