package com.myplatform.backend.listener;

import com.myplatform.backend.service.TelegramNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 서버 시작 알림 리스너
 * - 애플리케이션 재시작 시 텔레그램 알림 발송
 * - 운영 중 서버 재시작을 빠르게 감지하기 위함
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ServerStartupListener {

    private final TelegramNotificationService telegramService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("애플리케이션 시작 완료 - 텔레그램 알림 발송");

        if (!telegramService.isEnabled()) {
            log.info("텔레그램 알림이 비활성화 상태입니다.");
            return;
        }

        String startupTime = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        String message = String.format(
                """
                <b>🔄 서버 재시작 알림</b>

                ✅ MyPlatform 서버가 시작되었습니다.

                ⏰ 시작 시간: %s

                ━━━━━━━━━━━━━━━━
                ⚠️ 자동매매 봇이 중지 상태입니다.
                필요 시 수동으로 봇을 시작해 주세요.
                """,
                startupTime
        );

        try {
            telegramService.sendMessage(message);
            log.info("서버 시작 알림 발송 완료");
        } catch (Exception e) {
            log.error("서버 시작 알림 발송 실패: {}", e.getMessage());
        }
    }
}
