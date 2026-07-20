package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myplatform.backend.dto.KisRealtimePriceDto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * KIS WebSocket 실시간 시세/호가 채널.
 *
 * 빈 등록: kis.websocket.enabled=true 일 때만 (@ConditionalOnProperty). 기본 비활성.
 *
 * 폴링(stockPriceService 1분 캐시) 만으로는 매도 시점 지연이 발생 → 보유 종목 / 감시 종목은
 * push 로 시세를 받아 매매 봇이 즉시 사용할 수 있게 한다.
 *
 * 기능:
 *  - approval_key 발급 (REST POST /oauth2/Approval) — 단발성, 만료 시 재발급
 *  - WebSocket 연결 + 자동 재연결 (지수 백오프, 최대 60s)
 *  - 종목별 구독/해제 (TR_ID: H0STCNT0=체결, H0STASP0=호가)
 *  - 메시지 핸들러 등록 — RealtimePriceBus 갱신 + 매매 봇 콜백
 *
 * 한도: 실전/모의 모두 동시 구독 41건. 자동매매에서는 보유 + 감시 종목만.
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "kis.websocket", name = "enabled", havingValue = "true")
public class KisWebSocketService {

    @Value("${kis.websocket.url:ws://ops.koreainvestment.com:21000}")
    private String wsUrl;

    @Value("${kis.api.app-key:}")
    private String appKey;

    @Value("${kis.api.app-secret:}")
    private String appSecret;

    @Value("${kis.api.base-url:https://openapi.koreainvestment.com:9443}")
    private String restBaseUrl;

    @Value("${kis.websocket.customer-type:P}")
    private String customerType;

    @Value("${kis.websocket.reconnect-max-attempts:999}")
    private int maxReconnectAttempts;

    @Value("${kis.websocket.reconnect-max-backoff-sec:60}")
    private int maxBackoffSec;

    private static final String TR_PRICE = "H0STCNT0";       // 실시간 체결가
    private static final String TR_ORDERBOOK = "H0STASP0";   // 실시간 호가
    private static final int MAX_SUBSCRIPTIONS = 40;          // KIS 41 한도 — 1건 여유

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    // Gap-filling 용 — phase 30. 재연결 직후 REST 폴백으로 보유 종목 시세 동기화.
    // ObjectProvider 로 받아 순환 의존 / 미구성 환경 방어.
    private final org.springframework.beans.factory.ObjectProvider<KoreaInvestmentService> kisRestProvider;

    // 상태
    private volatile WebSocket webSocket;
    private volatile String approvalKey;
    private volatile LocalDateTime approvalKeyIssuedAt;
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private ScheduledFuture<?> reconnectTask;

    // 구독 — stockCode → trIds 집합. 재연결 시 자동 재구독 위해 보존.
    private final Map<String, Set<String>> activeSubscriptions = new ConcurrentHashMap<>();

    // 메시지 수신 시 호출되는 콜백.
    private final Map<String, Consumer<KisRealtimePriceDto>> priceListeners = new ConcurrentHashMap<>();

    // 수신 버퍼 — KIS 가 메시지를 onText fragment 로 쪼개 보낼 때 합치는 용도.
    private final StringBuilder receiveBuffer = new StringBuilder();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kis-ws-scheduler");
                t.setDaemon(true);
                return t;
            });

    public KisWebSocketService(ObjectMapper objectMapper, WebClient.Builder webClientBuilder,
                               org.springframework.beans.factory.ObjectProvider<KoreaInvestmentService> kisRestProvider) {
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.build();
        this.kisRestProvider = kisRestProvider;
    }

    @PostConstruct
    public void init() {
        if (appKey == null || appKey.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            log.warn("[KIS-WS] app-key/secret 미설정 — 연결 스킵");
            return;
        }
        connectAsync();
    }

    @PreDestroy
    public void shutdown() {
        shutdownRequested.set(true);
        if (reconnectTask != null) reconnectTask.cancel(false);
        WebSocket ws = this.webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (Exception e) {
                log.debug("[KIS-WS] close 중 오류 (무시): {}", e.getMessage());
            }
        }
        scheduler.shutdownNow();
    }

    // ============================== 외부 API ==============================

    public void registerPriceListener(String listenerId, Consumer<KisRealtimePriceDto> listener) {
        priceListeners.put(listenerId, listener);
    }

    public void unregisterPriceListener(String listenerId) {
        priceListeners.remove(listenerId);
    }

    public boolean subscribePrice(String stockCode) {
        return subscribe(stockCode, TR_PRICE);
    }

    public boolean subscribeOrderbook(String stockCode) {
        return subscribe(stockCode, TR_ORDERBOOK);
    }

    public void unsubscribe(String stockCode) {
        Set<String> trIds = activeSubscriptions.remove(stockCode);
        if (trIds == null) return;
        WebSocket ws = this.webSocket;
        if (ws == null) return;
        for (String trId : trIds) {
            sendSubscribeFrame(ws, trId, stockCode, false);
        }
    }

    public boolean isConnected() {
        WebSocket ws = this.webSocket;
        return ws != null && !ws.isInputClosed() && !ws.isOutputClosed();
    }

    public int getSubscriptionCount() {
        return activeSubscriptions.size();
    }

    public Set<String> getSubscribedCodes() {
        return activeSubscriptions.keySet();
    }

    // ============================== 내부 ==============================

    private boolean subscribe(String stockCode, String trId) {
        Set<String> trIds = activeSubscriptions.computeIfAbsent(stockCode,
                k -> ConcurrentHashMap.newKeySet());
        if (trIds.contains(trId)) {
            return true;   // 이미 구독 중 — 한도 검사 불필요
        }
        // KIS 한도(41)는 종목 수가 아니라 '등록 프레임 수(종목×TR_ID)' 기준 — 체결+호가 양채널
        // 구독이면 종목당 2건이라, 종목 수만 세면 ~21종목 양채널에서 실제 42건으로 초과된다.
        if (totalFrameCount() >= MAX_SUBSCRIPTIONS) {
            if (trIds.isEmpty()) {
                activeSubscriptions.remove(stockCode);
            }
            log.warn("[KIS-WS] 구독 한도({}프레임) 도달 — {} tr={} 거절", MAX_SUBSCRIPTIONS, stockCode, trId);
            return false;
        }
        if (!trIds.add(trId)) {
            return true;
        }
        WebSocket ws = this.webSocket;
        if (ws != null && !ws.isOutputClosed()) {
            sendSubscribeFrame(ws, trId, stockCode, true);
        }
        return true;
    }

    /** 현재 KIS 에 등록된(될) 프레임 수 = Σ(종목별 trId 수). 한도 판정 기준. */
    private int totalFrameCount() {
        int n = 0;
        for (Set<String> s : activeSubscriptions.values()) n += s.size();
        return n;
    }

    private void sendSubscribeFrame(WebSocket ws, String trId, String stockCode, boolean subscribe) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode header = root.putObject("header");
            header.put("approval_key", approvalKey == null ? "" : approvalKey);
            header.put("custtype", customerType);
            header.put("tr_type", subscribe ? "1" : "2");
            header.put("content-type", "utf-8");
            ObjectNode body = root.putObject("body");
            ObjectNode input = body.putObject("input");
            input.put("tr_id", trId);
            input.put("tr_key", stockCode);
            String payload = objectMapper.writeValueAsString(root);
            ws.sendText(payload, true);
            log.debug("[KIS-WS] {} {} tr={}", subscribe ? "SUBSCRIBE" : "UNSUBSCRIBE", stockCode, trId);
        } catch (Exception e) {
            log.warn("[KIS-WS] frame 전송 실패 ({} {}): {}", trId, stockCode, e.getMessage());
        }
    }

    private void connectAsync() {
        if (shutdownRequested.get()) return;
        if (!connecting.compareAndSet(false, true)) return;

        scheduler.execute(() -> {
            try {
                connect();
            } catch (Exception e) {
                log.warn("[KIS-WS] 연결 실패: {}", e.getMessage());
                scheduleReconnect();
            } finally {
                connecting.set(false);
            }
        });
    }

    private void connect() throws Exception {
        // approval_key — 만료 (12시간) 시 재발급.
        if (approvalKey == null || approvalKeyIssuedAt == null
                || Duration.between(approvalKeyIssuedAt, LocalDateTime.now()).toHours() >= 11) {
            approvalKey = issueApprovalKey();
            approvalKeyIssuedAt = LocalDateTime.now();
            log.info("[KIS-WS] approval_key 발급 완료");
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        CompletableFuture<WebSocket> future = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(wsUrl), new Listener());

        this.webSocket = future.get(15, TimeUnit.SECONDS);
        log.info("[KIS-WS] 연결 완료 → {}", wsUrl);
        boolean wasReconnect = reconnectAttempts.get() > 0;
        reconnectAttempts.set(0);

        // 재연결 시 기존 구독 자동 복원.
        for (Map.Entry<String, Set<String>> entry : activeSubscriptions.entrySet()) {
            for (String trId : entry.getValue()) {
                sendSubscribeFrame(this.webSocket, trId, entry.getKey(), true);
            }
        }

        // 재연결인 경우 Gap-filling 실행 (phase 30) — 끊긴 동안 누락된 시세를 REST 로 보완.
        // 첫 연결은 활성 구독 0건이므로 자동 skip.
        if (wasReconnect && !activeSubscriptions.isEmpty()) {
            scheduler.execute(this::fillGapAfterReconnect);
        }
    }

    /**
     * 재연결 직후 보유/감시 종목 현재가를 KIS REST 로 1회 조회하여 priceListeners 에 dispatch.
     * WebSocket 끊긴 동안 누락된 시세 갭을 메꿈 (phase 30, Gemini 제안).
     *
     * 부하: 보유 종목 수 × 1 회 호출. 보통 5~10건이라 KIS rate limit (5/s) 영향 적음.
     * 실패 시 조용히 skip — 다음 push 가 들어오면 자연 복원.
     */
    private void fillGapAfterReconnect() {
        KoreaInvestmentService kis = kisRestProvider.getIfAvailable();
        if (kis == null || !kis.isConfigured()) {
            log.debug("[KIS-WS] Gap-fill skip — KIS REST 미구성");
            return;
        }
        Set<String> codes = new java.util.HashSet<>(activeSubscriptions.keySet());
        if (codes.isEmpty()) return;
        log.info("[KIS-WS] Gap-fill 시작 — {}종목 REST 폴백", codes.size());
        int filled = 0;
        for (String code : codes) {
            try {
                JsonNode resp = kis.getStockPriceWithPriority(code, KisApiRateLimiter.Priority.HIGH);
                if (resp == null || !resp.has("output")) continue;
                JsonNode out = resp.get("output");
                BigDecimal price = parseDecimalSafe(out.has("stck_prpr") ? out.get("stck_prpr").asText() : null);
                if (price == null || price.signum() <= 0) continue;
                KisRealtimePriceDto dto = KisRealtimePriceDto.builder()
                        .stockCode(code)
                        .currentPrice(price)
                        .changeRate(parseDecimalSafe(out.has("prdy_ctrt") ? out.get("prdy_ctrt").asText() : null))
                        .openPrice(parseDecimalSafe(out.has("stck_oprc") ? out.get("stck_oprc").asText() : null))
                        .highPrice(parseDecimalSafe(out.has("stck_hgpr") ? out.get("stck_hgpr").asText() : null))
                        .lowPrice(parseDecimalSafe(out.has("stck_lwpr") ? out.get("stck_lwpr").asText() : null))
                        .receivedAt(LocalDateTime.now())
                        .build();
                for (Consumer<KisRealtimePriceDto> listener : priceListeners.values()) {
                    try { listener.accept(dto); } catch (Exception ignore) {}
                }
                filled++;
            } catch (Exception e) {
                log.debug("[KIS-WS] Gap-fill {} 실패: {}", code, e.getMessage());
            }
        }
        log.info("[KIS-WS] Gap-fill 완료 {}/{}", filled, codes.size());
    }

    private String issueApprovalKey() throws Exception {
        URI uri = URI.create(restBaseUrl + "/oauth2/Approval");
        Map<String, String> body = new HashMap<>();
        body.put("grant_type", "client_credentials");
        body.put("appkey", appKey);
        body.put("secretkey", appSecret);
        String response = webClient.post()
                .uri(uri)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .block();
        JsonNode node = objectMapper.readTree(response);
        if (!node.has("approval_key")) {
            throw new RuntimeException("approval_key 발급 실패: " + response);
        }
        return node.get("approval_key").asText();
    }

    private synchronized void scheduleReconnect() {
        if (shutdownRequested.get()) return;
        // onClose/onError·connect 실패 등 복수 경로에서 호출될 수 있다 — 이미 재연결이 예약돼
        // 있으면 스킵(중복 예약 시 attempt 만 부풀고 이전 task 참조가 덮여 취소 불가).
        if (reconnectTask != null && !reconnectTask.isDone()) return;
        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > maxReconnectAttempts) {
            log.error("[KIS-WS] 재연결 최대 시도 초과 — 중단");
            return;
        }
        long backoff = Math.min(maxBackoffSec, (long) Math.pow(2, Math.min(attempt, 6)));
        log.warn("[KIS-WS] {}초 후 재연결 시도 (attempt={})", backoff, attempt);
        reconnectTask = scheduler.schedule(this::connectAsync, backoff, TimeUnit.SECONDS);
    }

    // ============================== 메시지 처리 ==============================

    private void handleMessage(String message) {
        if (message == null || message.isEmpty()) return;

        // 시스템 메시지 (PINGPONG / SUBSCRIBE 응답) — JSON 형태.
        if (message.startsWith("{")) {
            try {
                JsonNode node = objectMapper.readTree(message);
                JsonNode header = node.get("header");
                if (header != null && "PINGPONG".equals(asText(header, "tr_id"))) {
                    WebSocket ws = this.webSocket;
                    if (ws != null) ws.sendText(message, true);
                    return;
                }
                if (log.isDebugEnabled()) log.debug("[KIS-WS] sys: {}", message);
            } catch (Exception e) {
                log.debug("[KIS-WS] system msg 파싱 실패: {}", e.getMessage());
            }
            return;
        }

        // 실시간 데이터 — "encryption|trId|count|payload"
        String[] parts = message.split("\\|", 4);
        if (parts.length < 4) return;
        String trId = parts[1];
        String payload = parts[3];

        if (TR_PRICE.equals(trId)) {
            dispatchPriceTick(payload);
        }
    }

    private void dispatchPriceTick(String payload) {
        String[] f = payload.split("\\^");
        if (f.length < 15) return;
        try {
            KisRealtimePriceDto dto = KisRealtimePriceDto.builder()
                    .stockCode(f[0])
                    .tradeTime(f[1])
                    .currentPrice(parseDecimalSafe(f[2]))
                    .changeAmount(parseDecimalSafe(f[4]))
                    .changeRate(parseDecimalSafe(f[5]))
                    .openPrice(parseDecimalSafe(f[7]))
                    .highPrice(parseDecimalSafe(f[8]))
                    .lowPrice(parseDecimalSafe(f[9]))
                    .askPrice1(parseDecimalSafe(f[10]))
                    .bidPrice1(parseDecimalSafe(f[11]))
                    .tradeVolume(parseLongSafe(f[12]))
                    .accVolume(parseLongSafe(f[13]))
                    .accTradeValue(parseDecimalSafe(f[14]))
                    .receivedAt(LocalDateTime.now())
                    .build();
            for (Consumer<KisRealtimePriceDto> listener : priceListeners.values()) {
                try {
                    listener.accept(dto);
                } catch (Exception e) {
                    log.warn("[KIS-WS] listener 처리 오류: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("[KIS-WS] price tick 파싱 실패: {}", e.getMessage());
        }
    }

    private static BigDecimal parseDecimalSafe(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return new BigDecimal(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static long parseLongSafe(String s) {
        if (s == null || s.isEmpty()) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String asText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null ? null : v.asText();
    }

    // ============================== WebSocket Listener ==============================

    private class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            receiveBuffer.setLength(0);   // 이전 연결의 미완성 fragment 잔존 시 첫 메시지 파싱 오염 방지
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            receiveBuffer.append(data);
            if (last) {
                String full = receiveBuffer.toString();
                receiveBuffer.setLength(0);
                try {
                    handleMessage(full);
                } catch (Exception e) {
                    log.warn("[KIS-WS] message 처리 예외: {}", e.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.sendPong(message);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("[KIS-WS] 연결 종료: code={} reason={}", statusCode, reason);
            KisWebSocketService.this.webSocket = null;
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("[KIS-WS] 소켓 에러: {}", error.getMessage());
            KisWebSocketService.this.webSocket = null;
            scheduleReconnect();
        }
    }
}
