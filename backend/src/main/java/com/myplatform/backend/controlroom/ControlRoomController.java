package com.myplatform.backend.controlroom;

import com.myplatform.backend.entity.CrewSession;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 판정 관제실 API — <b>읽기 전용 레이어 + LLM 크루</b>.
 *
 * <p>여기서 나가는 것은 조회 결과와 크루 대화뿐이다. 봇 제어·주문·설정 변경 엔드포인트는 없고,
 * 크루의 결론도 "액션 제안" 텍스트일 뿐 실행 경로가 아니다.
 *
 * <p>운영 콘솔이라 전 엔드포인트 ADMIN 전용이다.
 *
 * <p>⚠ <b>실제 게이트는 {@code SecurityConfig} 의 URL 규칙</b>
 * ({@code /api/control-room/** → hasRole("ADMIN")})이다. 아래 {@code @PreAuthorize} 는 의도 표시이자
 * 장차 대비용이며, 이 코드베이스엔 {@code @EnableMethodSecurity} 가 없어 <b>현재 동작하지 않는다</b>
 * (기존 컨트롤러들의 {@code @PreAuthorize} 도 마찬가지 — URL 규칙이 실제 방어다).
 * 메서드 보안을 켜는 것은 앱 전역 동작을 바꾸므로 이 작업 범위 밖으로 두고 별도 티켓으로 제안한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/control-room")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "판정 관제실", description = "판정 캘린더 · KPI · FLAGGED · AI 크루 (읽기 전용)")
public class ControlRoomController {

    private final ControlRoomSnapshotService snapshotService;
    private final CrewOrchestrationService crewService;

    public ControlRoomController(ControlRoomSnapshotService snapshotService,
                                 CrewOrchestrationService crewService) {
        this.snapshotService = snapshotService;
        this.crewService = crewService;
    }

    /** 크루 지시 요청. */
    public record CrewOrderRequest(
            @NotBlank(message = "지시 내용은 필수입니다.")
            @Size(max = 2000, message = "지시는 2000자 이내여야 합니다.")
            String instruction
    ) {}

    @GetMapping("/snapshot")
    @Operation(summary = "관제실 스냅샷",
            description = "KPI 5종 + 판정 캘린더 + FLAGGED + 불변식. 계산은 전부 백엔드에서 끝낸다. "
                    + "각 블록의 dataAvailable=false 는 '0건'이 아니라 '못 읽음'을 뜻한다(§4c).")
    public ResponseEntity<Map<String, Object>> snapshot(
            @RequestParam(required = false) String month) {
        return ResponseEntity.ok(ok(snapshotService.snapshot(month)));
    }

    @PostMapping("/crew/sessions")
    @Operation(summary = "크루 세션 시작",
            description = "지시 1건 → 5턴(에렌 분배 → SCOUT 초안 → FIREWALL 검토 → SCOUT 반영 → 에렌 결론) "
                    + "백그라운드 실행. 즉시 세션 id 를 돌려주므로 화면은 GET 으로 폴링한다. "
                    + "동시 1건·일일 상한 초과는 조용히 넘어가지 않고 409/429 로 거부한다.")
    public ResponseEntity<Map<String, Object>> startCrew(@RequestBody CrewOrderRequest request,
                                                         Authentication authentication) {
        String operator = authentication != null ? authentication.getName() : "admin";
        try {
            CrewSession session = crewService.start(request.instruction(), operator);
            return ResponseEntity.ok(ok(CrewSessionView.of(session, crewService.messages(session.getId()))));
        } catch (CrewOrchestrationService.CrewUnavailableException e) {
            HttpStatus status = e.isLimitReached() ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.CONFLICT;
            log.info("[관제실] 크루 세션 거부 ({}): {}", status.value(), e.getMessage());
            return ResponseEntity.status(status).body(fail(e.getMessage(), e.isLimitReached()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(fail(e.getMessage(), false));
        }
    }

    @GetMapping("/crew/sessions/{id}")
    @Operation(summary = "크루 세션 조회(폴링)",
            description = "status(RUNNING/COMPLETED/FAILED) 와 지금까지 저장된 턴을 돌려준다. "
                    + "FAILED 면 failureReason 을 화면에 그대로 노출할 것 — 자동 재시도는 하지 않는다.")
    public ResponseEntity<Map<String, Object>> session(@PathVariable Long id) {
        CrewSession session = crewService.session(id);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(fail("세션을 찾을 수 없다", false));
        }
        return ResponseEntity.ok(ok(CrewSessionView.of(session, crewService.messages(id))));
    }

    @GetMapping("/crew/sessions")
    @Operation(summary = "최근 크루 세션 목록", description = "최근 20건. 스레드 복원·비용 확인용.")
    public ResponseEntity<Map<String, Object>> sessions() {
        List<CrewSessionView> views = crewService.recentSessions().stream()
                .map(s -> CrewSessionView.of(s, crewService.messages(s.getId())))
                .toList();
        return ResponseEntity.ok(ok(views));
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    private static Map<String, Object> fail(String message, boolean limitReached) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        body.put("limitReached", limitReached);
        return body;
    }
}
