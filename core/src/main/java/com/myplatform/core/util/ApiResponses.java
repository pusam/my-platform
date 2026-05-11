package com.myplatform.core.util;

import com.myplatform.core.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Controller catch 블록 공용 응답 생성기.
 *
 * 기존 패턴: {@code return ResponseEntity.ok(ApiResponse.fail("xxx 실패: " + e.getMessage()))}
 * — 모든 실패가 HTTP 200으로 응답되어 클라이언트가 status로 실패 감지 불가.
 *
 * 신 패턴: {@code return ApiResponses.error(e, "xxx 실패: ")}
 * — RuntimeException 메시지 기반으로 4xx/5xx 자동 매핑.
 *
 * 서비스 레이어가 RuntimeException 메시지로 도메인 에러를 알려주는 기존 패턴 보존.
 * (예: "사용자를 찾을 수 없습니다." → 404)
 */
public final class ApiResponses {

    private ApiResponses() {}

    /**
     * 예외 메시지 키워드로 HTTP status 추론.
     */
    public static HttpStatus statusFor(Exception e) {
        String msg = e == null ? null : e.getMessage();
        if (msg == null) return HttpStatus.INTERNAL_SERVER_ERROR;
        if (msg.contains("찾을 수 없") || msg.contains("존재하지 않")) return HttpStatus.NOT_FOUND;
        if (msg.contains("이미") || msg.contains("유효하지") || msg.contains("일치하지")
                || msg.contains("올바른") || msg.contains("잘못된") || msg.contains("입력")) {
            return HttpStatus.BAD_REQUEST;
        }
        if (msg.contains("권한") || msg.contains("forbidden")) return HttpStatus.FORBIDDEN;
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /**
     * catch 블록 공용 — 메시지 prefix 유지 + 적절한 HTTP status 반환.
     */
    public static <T> ResponseEntity<ApiResponse<T>> error(Exception e, String prefix) {
        String msg = e.getMessage();
        return ResponseEntity.status(statusFor(e))
                .body(ApiResponse.fail((prefix != null ? prefix : "") + (msg != null ? msg : "알 수 없는 오류")));
    }

    /**
     * 명시적 status 지정 — validation 실패 / 명백한 4xx 케이스용.
     */
    public static <T> ResponseEntity<ApiResponse<T>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.fail(message));
    }
}
