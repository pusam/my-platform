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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 텔레그램 알림 서비스 — 3채널 분리
 *
 * 브리핑 채널: 모닝브리핑, 마감알림, 헬스체크, 시장상태, 서버시작
 * 시그널 채널: 수급급증, 선점레이더, 복합조건, 봇매매, 매수신호, 숏스퀴즈, 마법공식, 턴어라운드
 * 리스크 채널: 리스크알리미, 킬스위치, 공매도경보
 *
 * 시그널/리스크 채널 미설정 시 기본 브리핑 채널로 폴백
 */
@Service
@Slf4j
public class TelegramNotificationService {

    private static final String TELEGRAM_API_URL = "https://api.telegram.org/bot{token}/sendMessage";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    // 브리핑 채널 (기본)
    @Value("${telegram.bot.token:}")
    private String botToken;

    @Value("${telegram.bot.chat-id:}")
    private String chatId;

    @Value("${telegram.bot.enabled:false}")
    private boolean enabled;

    // 시그널 채널
    @Value("${telegram.bot.token-signal:}")
    private String signalToken;

    @Value("${telegram.bot.chat-id-signal:}")
    private String signalChatId;

    // 리스크 채널
    @Value("${telegram.bot.token-risk:}")
    private String riskToken;

    @Value("${telegram.bot.chat-id-risk:}")
    private String riskChatId;

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
            log.info("텔레그램 알림 서비스 활성화됨 - 브리핑: {}", chatId);
            if (isSignalChannelConfigured()) {
                log.info("텔레그램 시그널 채널 활성화됨 - chatId: {}", signalChatId);
            }
            if (isRiskChannelConfigured()) {
                log.info("텔레그램 리스크 채널 활성화됨 - chatId: {}", riskChatId);
            }
        } else {
            log.info("텔레그램 알림 서비스 비활성화됨 (enabled: {}, token: {}, chatId: {})",
                    enabled, !botToken.isEmpty(), !chatId.isEmpty());
        }
    }

    public boolean isEnabled() {
        return enabled && !botToken.isEmpty() && !chatId.isEmpty();
    }

    private boolean isSignalChannelConfigured() {
        return !signalToken.isEmpty() && !signalChatId.isEmpty();
    }

    private boolean isRiskChannelConfigured() {
        return !riskToken.isEmpty() && !riskChatId.isEmpty();
    }

    // ==================== 채널별 발송 메서드 ====================

    /** 브리핑 채널로 발송 (모닝브리핑, 마감알림, 헬스체크, 시장상태) */
    @Async("notificationExecutor")
    public void sendBriefing(String message) {
        sendToChannel(botToken, chatId, message, "브리핑");
    }

    /** 시그널 채널로 발송 (수급급증, 선점레이더, 복합조건, 봇매매) */
    @Async("notificationExecutor")
    public void sendSignal(String message) {
        if (isSignalChannelConfigured()) {
            sendToChannel(signalToken, signalChatId, message, "시그널");
        } else {
            sendToChannel(botToken, chatId, message, "시그널(→브리핑폴백)");
        }
    }

    /** 리스크 채널로 발송 (리스크알리미, 킬스위치, 공매도경보) */
    @Async("notificationExecutor")
    public void sendRisk(String message) {
        if (isRiskChannelConfigured()) {
            sendToChannel(riskToken, riskChatId, message, "리스크");
        } else {
            sendToChannel(botToken, chatId, message, "리스크(→브리핑폴백)");
        }
    }

    // ==================== 기존 API 호환 (브리핑 채널 기본) ====================

    /** 기존 sendMessage — 브리핑 채널로 발송 (하위 호환) */
    @Async("notificationExecutor")
    public void sendMessage(String message) {
        if (!isEnabled()) {
            log.debug("텔레그램 비활성화 상태 - 메시지 발송 생략");
            return;
        }
        try {
            doSendMessage(botToken, chatId, message, "HTML");
            log.info("텔레그램 메시지 발송 완료");
        } catch (Exception e) {
            log.error("텔레그램 메시지 발송 실패: {}", e.getMessage());
        }
    }

    /** 주식 매수 알림 → 시그널 채널 */
    @Async("notificationExecutor")
    public void sendStockAlert(String stockName, String stockCode, String reason, BigDecimal price) {
        if (!isEnabled()) return;

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
            stockName, stockCode, formatPrice(price), reason,
            LocalDateTime.now().format(TIME_FORMATTER)
        );

        sendSignal(message);
    }

    /** 숏스퀴즈 후보 알림 → 시그널 채널 */
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

        sendSignal(message);
    }

    /** 마법의 공식 알림 → 시그널 채널 */
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

        sendSignal(message);
    }

    /** 턴어라운드 종목 알림 → 시그널 채널 */
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

        sendSignal(message);
    }

    /** 시장 상태 알림 → 브리핑 채널 */
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

        sendBriefing(message);
    }

    /** 복합 조건 알림 → 시그널 채널 */
    @Async("notificationExecutor")
    public void sendCompositeAlert(String stockName, String stockCode,
                                    BigDecimal price, List<String> matchedConditions) {
        if (!isEnabled()) return;

        String conditionList = matchedConditions.stream()
                .map(c -> "  • " + c)
                .collect(Collectors.joining("\n"));

        String message = String.format(
            """
            <b>🎯 복합 신호 포착!</b>

            📊 <b>%s</b> (%s)
            💰 현재가: <b>%s원</b>

            🔍 <b>충족 조건 (%d개)</b>
            %s

            ⏰ %s
            ━━━━━━━━━━━━━━━━
            🤖 MyPlatform 복합 알림
            """,
            stockName, stockCode, formatPrice(price),
            matchedConditions.size(), conditionList,
            LocalDateTime.now().format(TIME_FORMATTER)
        );

        sendSignal(message);
    }

    /** 테스트 메시지 (동기, 브리핑 채널) */
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
            doSendMessage(botToken, chatId, message, "HTML");
            log.info("텔레그램 테스트 메시지 발송 성공");
            return true;
        } catch (Exception e) {
            log.error("텔레그램 테스트 메시지 발송 실패: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 내부 메서드 ====================

    private void sendToChannel(String token, String targetChatId, String message, String channelName) {
        if (!isEnabled()) {
            log.debug("텔레그램 비활성화 - {} 메시지 발송 생략", channelName);
            return;
        }
        // 트레이딩 신호는 놓치면 안 됨 — 일시 장애(Telegram API 30초 다운, 네트워크 블립)에 대비해
        // 지수 백오프 재시도 (1s, 3s, 9s — 총 13초 내 3회 시도). HTTP 429(Too Many Requests) 도 같은 정책.
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                doSendMessage(token, targetChatId, message, "HTML");
                if (attempt > 1) {
                    log.info("텔레그램 {} 채널 발송 성공 (재시도 {}/3)", channelName, attempt);
                } else {
                    log.info("텔레그램 {} 채널 발송 완료", channelName);
                }
                return;
            } catch (Exception e) {
                last = e;
                if (attempt < 3) {
                    long backoffMs = (long) Math.pow(3, attempt - 1) * 1000L; // 1s, 3s
                    log.warn("텔레그램 {} 발송 실패 (시도 {}/3): {} — {}ms 후 재시도",
                            channelName, attempt, e.getMessage(), backoffMs);
                    try { Thread.sleep(backoffMs); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("텔레그램 {} 재시도 중단됨", channelName);
                        return;
                    }
                }
            }
        }
        log.error("텔레그램 {} 채널 발송 최종 실패 (3회 시도): {}",
                channelName, last == null ? "unknown" : last.getMessage(), last);
    }

    private void doSendMessage(String token, String targetChatId, String text, String parseMode) {
        String url = TELEGRAM_API_URL.replace("{token}", token);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", targetChatId);
        body.put("text", text);
        body.put("parse_mode", parseMode);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, request, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("텔레그램 API 응답 오류: " + response.getStatusCode());
        }
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) return "N/A";
        return String.format("%,.0f", price);
    }
}
