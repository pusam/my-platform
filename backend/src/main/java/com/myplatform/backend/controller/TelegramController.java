package com.myplatform.backend.controller;

import com.myplatform.backend.service.TelegramNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 텔레그램 알림 관리 API 컨트롤러
 */
@RestController
@RequestMapping("/api/telegram")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "텔레그램 알림", description = "텔레그램 알림 설정 및 테스트 API")
public class TelegramController {

    private final TelegramNotificationService telegramNotificationService;

    /**
     * 텔레그램 알림 상태 확인
     */
    @GetMapping("/status")
    @Operation(summary = "텔레그램 상태 확인", description = "텔레그램 알림 서비스 활성화 여부를 확인합니다.")
    public ResponseEntity<Map<String, Object>> getTelegramStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("enabled", telegramNotificationService.isEnabled());
        response.put("message", telegramNotificationService.isEnabled()
                ? "텔레그램 알림 서비스가 활성화되어 있습니다."
                : "텔레그램 알림 서비스가 비활성화되어 있습니다. 환경 변수를 확인해주세요.");

        return ResponseEntity.ok(response);
    }

    /**
     * 텔레그램 테스트 메시지 발송
     */
    @PostMapping("/test")
    @Operation(summary = "테스트 메시지 발송", description = "텔레그램으로 테스트 메시지를 발송합니다.")
    public ResponseEntity<Map<String, Object>> sendTestMessage() {
        log.info("텔레그램 테스트 메시지 발송 요청");

        Map<String, Object> response = new HashMap<>();

        if (!telegramNotificationService.isEnabled()) {
            response.put("success", false);
            response.put("message", "텔레그램 알림이 비활성화 상태입니다. 환경 변수를 설정해주세요.");
            return ResponseEntity.badRequest().body(response);
        }

        boolean result = telegramNotificationService.sendTestMessage();
        response.put("success", result);
        response.put("message", result
                ? "테스트 메시지가 발송되었습니다. 텔레그램을 확인해주세요."
                : "테스트 메시지 발송에 실패했습니다. 설정을 확인해주세요.");

        return ResponseEntity.ok(response);
    }

    /**
     * 커스텀 메시지 발송
     */
    @PostMapping("/send")
    @Operation(summary = "커스텀 메시지 발송", description = "텔레그램으로 사용자 정의 메시지를 발송합니다.")
    public ResponseEntity<Map<String, Object>> sendCustomMessage(
            @RequestParam String message) {

        log.info("텔레그램 커스텀 메시지 발송 요청");

        Map<String, Object> response = new HashMap<>();

        if (!telegramNotificationService.isEnabled()) {
            response.put("success", false);
            response.put("message", "텔레그램 알림이 비활성화 상태입니다.");
            return ResponseEntity.badRequest().body(response);
        }

        telegramNotificationService.sendMessage(message);
        response.put("success", true);
        response.put("message", "메시지가 발송 대기열에 추가되었습니다.");

        return ResponseEntity.ok(response);
    }

    /**
     * 주식 알림 테스트
     */
    @PostMapping("/test-stock-alert")
    @Operation(summary = "주식 알림 테스트", description = "주식 매수 알림 테스트 메시지를 발송합니다.")
    public ResponseEntity<Map<String, Object>> testStockAlert() {
        log.info("주식 알림 테스트 발송 요청");

        Map<String, Object> response = new HashMap<>();

        if (!telegramNotificationService.isEnabled()) {
            response.put("success", false);
            response.put("message", "텔레그램 알림이 비활성화 상태입니다.");
            return ResponseEntity.badRequest().body(response);
        }

        // 테스트용 알림 발송
        telegramNotificationService.sendStockAlert(
                "삼성전자",
                "005930",
                "• 외국인 3일 연속 순매수\n• 골든크로스 발생\n• RSI 과매도 구간 탈출",
                new BigDecimal("72500")
        );

        response.put("success", true);
        response.put("message", "주식 알림 테스트 메시지가 발송되었습니다.");

        return ResponseEntity.ok(response);
    }

    /**
     * 숏스퀴즈 알림 테스트
     */
    @PostMapping("/test-squeeze-alert")
    @Operation(summary = "숏스퀴즈 알림 테스트", description = "숏스퀴즈 후보 알림 테스트 메시지를 발송합니다.")
    public ResponseEntity<Map<String, Object>> testSqueezeAlert() {
        log.info("숏스퀴즈 알림 테스트 발송 요청");

        Map<String, Object> response = new HashMap<>();

        if (!telegramNotificationService.isEnabled()) {
            response.put("success", false);
            response.put("message", "텔레그램 알림이 비활성화 상태입니다.");
            return ResponseEntity.badRequest().body(response);
        }

        telegramNotificationService.sendShortSqueezeAlert(
                "에코프로",
                "086520",
                new BigDecimal("98500"),
                85,
                new BigDecimal("-8.5"),
                true
        );

        response.put("success", true);
        response.put("message", "숏스퀴즈 알림 테스트 메시지가 발송되었습니다.");

        return ResponseEntity.ok(response);
    }
}
