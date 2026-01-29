package com.myplatform.backend.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 전역 예외 처리 핸들러
 * - 모든 컨트롤러에서 발생하는 예외를 일관된 JSON 포맷으로 응답
 * - 클라이언트에게 적절한 HTTP 상태 코드와 에러 메시지 전달
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 400 Bad Request - 잘못된 인수
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("잘못된 인수: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "잘못된 요청입니다.", ex.getMessage());
    }

    /**
     * 400 Bad Request - 유효성 검증 실패 (@Valid)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        error -> error.getDefaultMessage() != null ? error.getDefaultMessage() : "유효하지 않은 값",
                        (first, second) -> first
                ));

        log.warn("유효성 검증 실패: {}", fieldErrors);
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "입력값 검증에 실패했습니다.", fieldErrors);
    }

    /**
     * 400 Bad Request - 필수 파라미터 누락
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        String message = String.format("필수 파라미터 '%s'이(가) 누락되었습니다.", ex.getParameterName());
        log.warn(message);
        return buildErrorResponse(HttpStatus.BAD_REQUEST, message, null);
    }

    /**
     * 400 Bad Request - 파라미터 타입 불일치
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = String.format("파라미터 '%s'의 타입이 올바르지 않습니다.", ex.getName());
        log.warn(message);
        return buildErrorResponse(HttpStatus.BAD_REQUEST, message, null);
    }

    /**
     * 400 Bad Request - JSON 파싱 오류
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("요청 본문 파싱 실패: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다. JSON 형식을 확인하세요.", null);
    }

    /**
     * 401 Unauthorized - 인증 실패
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        log.warn("인증 실패: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, "인증에 실패했습니다.", null);
    }

    /**
     * 403 Forbidden - 접근 권한 없음
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        log.warn("접근 거부: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.", null);
    }

    /**
     * 404 Not Found - 핸들러 없음
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoHandlerFound(NoHandlerFoundException ex) {
        String message = String.format("요청하신 경로 '%s'를 찾을 수 없습니다.", ex.getRequestURL());
        log.warn(message);
        return buildErrorResponse(HttpStatus.NOT_FOUND, message, null);
    }

    /**
     * 405 Method Not Allowed - 지원하지 않는 HTTP 메서드
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String message = String.format("HTTP 메서드 '%s'은(는) 지원하지 않습니다.", ex.getMethod());
        log.warn(message);
        return buildErrorResponse(HttpStatus.METHOD_NOT_ALLOWED, message, null);
    }

    /**
     * 415 Unsupported Media Type - 지원하지 않는 Content-Type
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.warn("지원하지 않는 미디어 타입: {}", ex.getContentType());
        return buildErrorResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다.", null);
    }

    /**
     * 500 Internal Server Error - 데이터베이스 오류
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> handleDataAccessException(DataAccessException ex) {
        log.error("데이터베이스 오류: {}", ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "데이터베이스 처리 중 오류가 발생했습니다.", null);
    }

    /**
     * 503 Service Unavailable - 외부 API 타임아웃/연결 실패
     */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceAccessException(ResourceAccessException ex) {
        log.error("외부 서비스 연결 실패: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, "외부 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.", null);
    }

    /**
     * 502 Bad Gateway - 외부 API 호출 오류
     */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ApiErrorResponse> handleRestClientException(RestClientException ex) {
        log.error("외부 API 호출 실패: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_GATEWAY, "외부 API 호출에 실패했습니다.", null);
    }

    /**
     * 500 Internal Server Error - RuntimeException
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiErrorResponse> handleRuntimeException(RuntimeException ex) {
        log.error("런타임 오류: {}", ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.", ex.getMessage());
    }

    /**
     * 500 Internal Server Error - 기타 모든 예외
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGlobalException(Exception ex) {
        log.error("예상치 못한 오류: {}", ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "서버에서 오류가 발생했습니다.", null);
    }

    /**
     * 에러 응답 생성 헬퍼 메서드
     */
    private ResponseEntity<ApiErrorResponse> buildErrorResponse(HttpStatus status, String message, Object details) {
        ApiErrorResponse response = ApiErrorResponse.builder()
                .success(false)
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .details(details)
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(status).body(response);
    }

    /**
     * API 에러 응답 DTO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ApiErrorResponse {
        private boolean success;
        private int status;
        private String error;
        private String message;
        private Object details;
        private LocalDateTime timestamp;
    }
}
