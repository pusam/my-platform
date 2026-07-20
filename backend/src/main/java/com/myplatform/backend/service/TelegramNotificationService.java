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
            escapeHtml(stockName), stockCode, formatPrice(price), escapeHtml(reason),
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
            escapeHtml(stockName), stockCode, formatPrice(price), squeezeScore,
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
            rank, escapeHtml(stockName), stockCode, formatPrice(price),
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
            typeEmoji, escapeHtml(stockName), stockCode, formatPrice(price),
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
            conditionEmoji, adr, escapeHtml(diagnosis),
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
                .map(c -> "  • " + escapeHtml(c))
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
            escapeHtml(stockName), stockCode, formatPrice(price),
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

    /**
     * HTML 특수문자 이스케이프 — parse_mode=HTML 메시지에 외부 텍스트(종목명 "S&T모티브", 사유,
     * 진단 등)를 그대로 넣으면 Telegram 이 400(can't parse entities)을 반환해 알림이 유실된다.
     * 순수 함수(테스트 대상).
     */
    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * HTML 태그 제거 + 엔티티 복원 — 400 폴백 시 평문(parse_mode 없음) 재발송용. 순수 함수.
     */
    static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    /**
     * Telegram 429 응답의 retry_after 추출.
     * 응답 body 예: {"ok":false,"error_code":429,"description":"...","parameters":{"retry_after":33}}
     */
    private static long parseTelegramRetryAfter(org.springframework.web.client.HttpClientErrorException e) {
        try {
            String body = e.getResponseBodyAsString();
            if (body != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"retry_after\"\\s*:\\s*(\\d+)").matcher(body);
                if (m.find()) return Long.parseLong(m.group(1));
            }
        } catch (Exception ignore) { /* fallthrough */ }
        return 5L;  // 알 수 없으면 기본 5초 — 보수적으로 drop 결정
    }

    private void sendToChannel(String token, String targetChatId, String message, String channelName) {
        if (!isEnabled()) {
            log.debug("텔레그램 비활성화 - {} 메시지 발송 생략", channelName);
            return;
        }
        // 일시 장애(API 30초 다운, 네트워크 블립) 대비 재시도. 단 429 시 retry_after 헤더 존중 —
        // Telegram 이 30s+ 기다리라고 하는데 1s/3s 재시도하면 더 폭주만 시킴 → 즉시 drop.
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
            } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
                // HTML 파싱 실패(미이스케이프 <,>,& — 외부 호출자가 만든 메시지 포함) — 재시도해도
                // 같은 400 이므로, 알림 유실 대신 태그 제거 평문으로 1회 폴백 발송하고 종료.
                log.warn("텔레그램 {} 채널 400 — HTML 파싱 실패 가능, 평문 폴백 발송: {}",
                        channelName, e.getMessage());
                try {
                    doSendMessage(token, targetChatId, stripHtml(message), null);
                } catch (Exception e2) {
                    log.error("텔레그램 {} 평문 폴백도 실패: {}", channelName, e2.getMessage());
                }
                return;
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                last = e;
                long retryAfterSec = parseTelegramRetryAfter(e);
                if (retryAfterSec >= 5L) {
                    // 한도 회복까지 5초+ 면 재시도 의미 없음 (다른 thread/채널도 동시 폭주 가능) → drop.
                    log.warn("텔레그램 {} 채널 429 (retry_after {}s) — 즉시 drop (다음 사이클 대기)",
                            channelName, retryAfterSec);
                    return;
                }
                if (attempt < 3) {
                    long backoffMs = retryAfterSec * 1000L;
                    log.warn("텔레그램 {} 429 (시도 {}/3) — retry_after {}s 후 재시도",
                            channelName, attempt, retryAfterSec);
                    try { Thread.sleep(backoffMs); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
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
        if (parseMode != null) body.put("parse_mode", parseMode);   // null = 평문(폴백 발송용)

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
