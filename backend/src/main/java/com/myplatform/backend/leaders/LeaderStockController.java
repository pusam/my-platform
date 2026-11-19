package com.myplatform.backend.leaders;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 주도주 목록 조회 — 저장된 결과만 읽는다(이 주소가 KIS 를 부르거나 계산을 일으키지 않는다). 로그인 필수({@code /api/**} 기본 규칙).
 */
@RestController
@RequestMapping("/api/leaders")
@RequiredArgsConstructor
@Slf4j
public class LeaderStockController {

    private final LeaderStockService leaderStockService;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "20") int limit) {
        // LinkedHashMap — 실패 응답엔 data 가 없다(Map.of 는 null 을 못 담는다)
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            LeaderStockService.LeaderList list = leaderStockService.getLeaders(limit);
            body.put("success", true);
            body.put("dataAvailable", true);
            body.put("data", list);
        } catch (Exception e) {
            // 조회 실패를 빈 목록('아직 계산 전')으로 보이지 않는다(§4c)
            log.warn("[주도주] 목록 조회 실패: {}", e.getMessage());
            body.put("success", false);
            body.put("dataAvailable", false);
        }
        return ResponseEntity.ok(body);
    }
}
