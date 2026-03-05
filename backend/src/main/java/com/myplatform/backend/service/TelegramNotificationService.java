package com.myplatform.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 텔레그램 알림 서비스
 * - 주식 매수 신호, 시장 상태 등 중요 알림을 텔레그램으로 발송
 * - 비동기 처리로 메인 로직에 영향 없음
 */
@Service
@Slf4j
public class TelegramNotificationService {

    private static final String TELEGRAM_API_URL = "https://api.telegram.org/bot{token}/sendMessage";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Value("${telegram.bot.token:}")
    private String botToken;

    @Value("${telegram.bot.chat-id:}")
    private String chatId;

    @Value("${telegram.bot.enabled:false}")
    private boolean enabled;

    private final RestTemplate restTemplate;

    public TelegramNotificationService() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    @PostConstruct
    public void init() {
        if (enabled && !botToken.isEmpty() && !chatId.isEmpty()) {
            log.info("텔레그램 알림 서비스 활성화됨 - chatId: {}", chatId);
        } else {
            log.info("텔레그램 알림 서비스 비활성화됨 (enabled: {}, token: {}, chatId: {})",
                    enabled, !botToken.isEmpty(), !chatId.isEmpty());
        }
    }

    /**
     * 텔레그램 알림 활성화 여부 확인
     */
    public boolean isEnabled() {
        return enabled && !botToken.isEmpty() && !chatId.isEmpty();
    }

    /**
     * 일반 텍스트 메시지 발송 (비동기)
     */
    @Async("notificationExecutor")
    public void sendMessage(String message) {
        if (!isEnabled()) {
            log.debug("텔레그램 비활성화 상태 - 메시지 발송 생략");
            return;
        }

        try {
            doSendMessage(message, "HTML");
            log.info("텔레그램 메시지 발송 완료");
        } catch (Exception e) {
            log.error("텔레그램 메시지 발송 실패: {}", e.getMessage());
        }
    }

    /**
     * 주식 매수 알림 발송 (비동기)
     * - 예쁜 포맷 + 이모지
     */
    @Async("notificationExecutor")
    public void sendStockAlert(String stockName, String stockCode, String reason, BigDecimal price) {
        if (!isEnabled()) {
            log.debug("텔레그램 비활성화 상태 - 주식 알림 발송 생략");
            return;
        }

        String formattedPrice = formatPrice(price);
        String currentTime = LocalDateTime.now().format(TIME_FORMATTER);

        String message = String.format(
            """
            <b>🚨 매수 신호 포착!</b>

            📊 <b>%s</b> (%s)
            💰 현재가: <b>%s원</b>

            📝 <b>추천 사유</b>
            %s

            ⏰ %s
            ━━━━━━━━━━━━━━━━
            🤖 MyPlatform 알림봇
            """,
            stockName, stockCode, formattedPrice, reason, currentTime
        );

        try {
            doSendMessage(message, "HTML");
            log.info("주식 알림 발송 완료 - {} ({})", stockName, stockCode);
        } catch (Exception e) {
            log.error("주식 알림 발송 실패 - {} ({}): {}", stockName, stockCode, e.getMessage());
        }
    }

    /**
     * 숏스퀴즈 후보 알림
     */
    @Async("notificationExecutor")
    public void sendShortSqueezeAlert(String stockName, String stockCode,
                                       BigDecimal price, int squeezeScore,
                                       BigDecimal loanBalanceChange, boolean isForeignBuying) {
        if (!isEnabled()) return;

        String foreignStatus = isForeignBuying ? "✅ 외국인 순매수 중" : "⏸️ 외국인 관망";
        String loanStatus = loanBalanceChange != null && loanBalanceChange.compareTo(BigDecimal.ZERO) < 0
                ? String.format("📉 대차잔고 %.1f%% 감소", loanBalanceChange.abs())
                : "📊 대차잔고 유지";

        String message = String.format(
            """
            <b>🔥 숏스퀴즈 후보 발견!</b>

            📊 <b>%s</b> (%s)
            💰 현재가: <b>%s원</b>
            🎯 스퀴즈 점수: <b>%d/100</b>

            📈 <b>신호 분석</b>
            %s
            %s

            ⏰ %s
            ━━━━━━━━━━━━━━━━
            🤖 MyPlatform 숏스퀴즈 알림
            """,
            stockName, stockCode, formatPrice(price), squeezeScore,
            loanStatus, foreignStatus,
            LocalDateTime.now().format(TIME_FORMATTER)
        );

        try {
            doSendMessage(message, "HTML");
            log.info("숏스퀴즈 알림 발송 완료 - {} ({}), 점수: {}", stockName, stockCode, squeezeScore);
        } catch (Exception e) {
            log.error("숏스퀴즈 알림 발송 실패: {}", e.getMessage());
        }
    }

    /**
     * 마법의 공식 상위 종목 알림
     */
    @Async("notificationExecutor")
    public void sendMagicFormulaAlert(String stockName, String stockCode,
                                       int rank, BigDecimal per, BigDecimal roe,
                                       BigDecimal operatingMargin, BigDecimal price) {
        if (!isEnabled()) return;

        String message = String.format(
            """
            <b>✨ 마법의 공식 유망주!</b>

            🏆 순위: <b>#%d</b>
            📊 <b>%s</b> (%s)
            💰 현재가: <b>%s원</b>

            📈 <b>핵심 지표</b>
            • PER: %.1f배
            • ROE: %.1f%%
            • 영업이익률: %.1f%%

            ⏰ %s
            ━━━━━━━━━━━━━━━━
            🤖 MyPlatform 퀀트 알림
            """,
            rank, stockName, stockCode, formatPrice(price),
            per, roe, operatingMargin,
            LocalDateTime.now().format(TIME_FORMATTER)
        );

        try {
            doSendMessage(message, "HTML");
            log.info("마법의 공식 알림 발송 완료 - {} ({}), 순위: #{}", stockName, stockCode, rank);
        } catch (Exception e) {
            log.error("마법의 공식 알림 발송 실패: {}", e.getMessage());
        }
    }

    /**
     * 턴어라운드 종목 알림
     */
    @Async("notificationExecutor")
    public void sendTurnaroundAlert(String stockName, String stockCode,
                                     String turnaroundType, BigDecimal changeRate,
                                     BigDecimal price) {
        if (!isEnabled()) return;

        String typeEmoji = "LOSS_TO_PROFIT".equals(turnaroundType) ? "🔄" : "📈";
        String typeText = "LOSS_TO_PROFIT".equals(turnaroundType)
                ? "적자 → 흑자 전환!"
                : String.format("순이익 %.0f%% 급증!", changeRate);

        String message = String.format(
            """
            <b>%s 턴어라운드 종목!</b>

            📊 <b>%s</b> (%s)
            💰 현재가: <b>%s원</b>

            💡 <b>실적 변화</b>
            %s

            ⏰ %s
            ━━━━━━━━━━━━━━━━
            🤖 MyPlatform 실적 알림
            """,
            typeEmoji, stockName, stockCode, formatPrice(price),
            typeText,
            LocalDateTime.now().format(TIME_FORMATTER)
        );

        try {
            doSendMessage(message, "HTML");
            log.info("턴어라운드 알림 발송 완료 - {} ({})", stockName, stockCode);
        } catch (Exception e) {
            log.error("턴어라운드 알림 발송 실패: {}", e.getMessage());
        }
    }

    /**
     * 시장 상태 알림
     */
    @Async("notificationExecutor")
    public void sendMarketStatusAlert(String condition, BigDecimal adr, String diagnosis) {
        if (!isEnabled()) return;

        String conditionEmoji;
        switch (condition) {
            case "OVERHEATED" -> conditionEmoji = "🔥 과열";
            case "OVERSOLD" -> conditionEmoji = "💧 침체 (매수 기회)";
            case "EXTREME_FEAR" -> conditionEmoji = "🥶 극심한 공포 (적극 매수!)";
            default -> conditionEmoji = "☁️ 보통";
        }

        String message = String.format(
            """
            <b>📊 시장 상태 알림</b>

            %s

            📈 ADR: <b>%.1f</b>

            💬 %s

            ⏰ %s
            ━━━━━━━━━━━━━━━━
            🤖 MyPlatform 시장 알림
            """,
            conditionEmoji, adr, diagnosis,
            LocalDateTime.now().format(TIME_FORMATTER)
        );

        try {
            doSendMessage(message, "HTML");
            log.info("시장 상태 알림 발송 완료 - {}, ADR: {}", condition, adr);
        } catch (Exception e) {
            log.error("시장 상태 알림 발송 실패: {}", e.getMessage());
        }
    }

    /**
     * 테스트 메시지 발송 (동기)
     * - 설정 확인용
     */
    public boolean sendTestMessage() {
        if (!isEnabled()) {
            log.warn("텔레그램 비활성화 상태 - 테스트 불가");
            return false;
        }

        String message = String.format(
            """
            <b>🔔 MyPlatform 알림 테스트</b>

            ✅ 텔레그램 연동 성공!

            앞으로 중요한 매수 신호가 포착되면
            이 채팅방으로 알림이 발송됩니다.

            ⏰ %s
            """,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );

        try {
            doSendMessage(message, "HTML");
            log.info("텔레그램 테스트 메시지 발송 성공");
            return true;
        } catch (Exception e) {
            log.error("텔레그램 테스트 메시지 발송 실패: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 실제 메시지 발송 (HTTP API 호출)
     */
    private void doSendMessage(String text, String parseMode) {
        String url = TELEGRAM_API_URL.replace("{token}", botToken);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("parse_mode", parseMode);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, request, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("텔레그램 API 응답 오류: " + response.getStatusCode());
        }
    }

    /**
     * 가격 포맷팅 (천 단위 콤마)
     */
    private String formatPrice(BigDecimal price) {
        if (price == null) return "N/A";
        return String.format("%,.0f", price);
    }
}
