package com.myplatform.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE(Server-Sent Events) 관리 서비스
 * - 클라이언트 연결 관리
 * - 진행률/로그 이벤트 브로드캐스트
 * - 작업별 진행 상태 관리
 */
@Service
@Slf4j
public class SseEmitterService {

    // 클라이언트별 SSE 연결 (clientId -> SseEmitter)
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    // 작업 유형별 구독자 목록 (taskType -> clientIds)
    private final Map<String, CopyOnWriteArrayList<String>> taskSubscribers = new ConcurrentHashMap<>();

    // SSE 타임아웃 (30분) - 장시간 크롤링 작업 고려
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    /**
     * 새로운 SSE 연결 생성
     *
     * @param clientId 클라이언트 ID (UUID 권장)
     * @param taskType 구독할 작업 유형 (예: "crawl-operating-margin", "collect-finance")
     * @return SseEmitter
     */
    public SseEmitter subscribe(String clientId, String taskType) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        // 이전 연결이 있으면 정리
        if (emitters.containsKey(clientId)) {
            SseEmitter oldEmitter = emitters.get(clientId);
            try {
                oldEmitter.complete();
            } catch (Exception ignored) {}
        }

        emitters.put(clientId, emitter);

        // 작업 유형별 구독자 등록
        taskSubscribers
                .computeIfAbsent(taskType, k -> new CopyOnWriteArrayList<>())
                .add(clientId);

        // 연결 종료 시 정리
        emitter.onCompletion(() -> removeClient(clientId, taskType));
        emitter.onTimeout(() -> removeClient(clientId, taskType));
        emitter.onError(e -> removeClient(clientId, taskType));

        // 연결 확인 이벤트 전송
        try {
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .data("{\"type\":\"CONNECTED\",\"message\":\"SSE 연결 성공\",\"taskType\":\"" + taskType + "\"}"));
        } catch (IOException e) {
            log.error("SSE 연결 확인 이벤트 전송 실패: {}", e.getMessage());
        }

        log.info("SSE 구독 시작 - clientId: {}, taskType: {}, 현재 연결 수: {}",
                clientId, taskType, emitters.size());

        return emitter;
    }

    /**
     * 특정 작업 유형의 모든 구독자에게 진행률 이벤트 전송
     *
     * @param taskType 작업 유형
     * @param percent 진행률 (0-100)
     * @param message 상태 메시지
     */
    public void sendProgress(String taskType, int percent, String message) {
        String eventData = String.format(
                "{\"type\":\"PROGRESS\",\"percent\":%d,\"message\":\"%s\"}",
                percent, escapeJson(message));

        broadcastToTask(taskType, "PROGRESS", eventData);
    }

    /**
     * 진행률 이벤트 전송 (상세 정보 포함)
     */
    public void sendProgress(String taskType, int current, int total, int success, int fail, String stockName) {
        int percent = total > 0 ? (int) ((current * 100.0) / total) : 0;
        String message = String.format("%s 수집 완료 (%d/%d)", stockName, current, total);

        String eventData = String.format(
                "{\"type\":\"PROGRESS\",\"percent\":%d,\"current\":%d,\"total\":%d,\"success\":%d,\"fail\":%d,\"message\":\"%s\"}",
                percent, current, total, success, fail, escapeJson(message));

        broadcastToTask(taskType, "PROGRESS", eventData);
    }

    /**
     * 로그 이벤트 전송
     */
    public void sendLog(String taskType, String level, String message) {
        String eventData = String.format(
                "{\"type\":\"LOG\",\"level\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}",
                level, escapeJson(message), java.time.LocalDateTime.now().toString());

        broadcastToTask(taskType, "LOG", eventData);
    }

    /**
     * 작업 시작 이벤트 전송
     */
    public void sendStart(String taskType, int totalCount, String description) {
        String eventData = String.format(
                "{\"type\":\"START\",\"totalCount\":%d,\"message\":\"%s\"}",
                totalCount, escapeJson(description));

        broadcastToTask(taskType, "START", eventData);
    }

    /**
     * 작업 완료 이벤트 전송
     */
    public void sendComplete(String taskType, Map<String, Object> result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"COMPLETE\"");
        for (Map.Entry<String, Object> entry : result.entrySet()) {
            sb.append(",\"").append(entry.getKey()).append("\":");
            if (entry.getValue() instanceof String) {
                sb.append("\"").append(escapeJson(entry.getValue().toString())).append("\"");
            } else {
                sb.append(entry.getValue());
            }
        }
        sb.append("}");

        broadcastToTask(taskType, "COMPLETE", sb.toString());
    }

    /**
     * 에러 이벤트 전송
     */
    public void sendError(String taskType, String errorMessage) {
        String eventData = String.format(
                "{\"type\":\"ERROR\",\"message\":\"%s\"}",
                escapeJson(errorMessage));

        broadcastToTask(taskType, "ERROR", eventData);
    }

    /**
     * 특정 작업 유형의 모든 구독자에게 이벤트 브로드캐스트
     */
    private void broadcastToTask(String taskType, String eventName, String data) {
        CopyOnWriteArrayList<String> subscribers = taskSubscribers.get(taskType);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }

        for (String clientId : subscribers) {
            SseEmitter emitter = emitters.get(clientId);
            if (emitter != null) {
                try {
                    emitter.send(SseEmitter.event()
                            .name(eventName)
                            .data(data));
                } catch (IOException e) {
                    log.debug("SSE 전송 실패 (clientId: {}): {}", clientId, e.getMessage());
                    removeClient(clientId, taskType);
                }
            }
        }
    }

    /**
     * 클라이언트 연결 정리
     */
    private void removeClient(String clientId, String taskType) {
        emitters.remove(clientId);
        CopyOnWriteArrayList<String> subscribers = taskSubscribers.get(taskType);
        if (subscribers != null) {
            subscribers.remove(clientId);
        }
        log.debug("SSE 클라이언트 제거 - clientId: {}, taskType: {}", clientId, taskType);
    }

    /**
     * 연결된 클라이언트 수 조회
     */
    public int getConnectedClientCount() {
        return emitters.size();
    }

    /**
     * 특정 작업 유형의 구독자 수 조회
     */
    public int getSubscriberCount(String taskType) {
        CopyOnWriteArrayList<String> subscribers = taskSubscribers.get(taskType);
        return subscribers != null ? subscribers.size() : 0;
    }

    /**
     * JSON 문자열 이스케이프
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
