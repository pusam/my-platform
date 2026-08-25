package com.myplatform.backend.controlroom;

import com.myplatform.backend.entity.CrewMessage;
import com.myplatform.backend.entity.CrewSession;
import com.myplatform.backend.repository.CrewMessageRepository;
import com.myplatform.backend.repository.CrewSessionRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 크루 5턴 파이프라인 실행 — 에렌 분배 → SCOUT 초안 → FIREWALL 검토 → SCOUT 반영 → 에렌 결론.
 *
 * <p><b>설계 원칙</b>
 * <ul>
 *   <li><b>5턴 고정</b> — 자동 루프도 자동 재시도도 없다. 어느 턴이든 실패하면 세션은 FAILED 로
 *       끝나고 사유를 남긴다. 재시도는 사람이 새 세션을 만든다(조용한 재시도 = 비용 누수).</li>
 *   <li><b>동시 1건</b> — 단일 스레드 실행기 + RUNNING 존재 시 거부. 실행기 자체가 2차 방어다.</li>
 *   <li><b>일일 상한</b> — 초과 시 조용히 넘어가지 않고 거부한다. 화면이 "일일 상한 도달"을 표시한다.</li>
 *   <li><b>읽기 전용</b> — 크루에게 툴을 주지 않는다. 여기서 하는 쓰기는 대화 기록 저장뿐이다.</li>
 * </ul>
 *
 * <p>재기동 시 남아 있는 RUNNING 행은 <b>고아</b>다(프로세스가 죽어 이어갈 수 없다). 그대로 두면
 * 동시 1건 가드가 영구히 막히므로 기동 시 FAILED 로 정리한다.
 */
@Slf4j
@Service
public class CrewOrchestrationService {

    /** 오퍼레이터 지시 기록의 턴 번호 — 크루 턴은 1~5. */
    private static final int ORDER_TURN = 0;

    private final CrewSessionRepository sessionRepository;
    private final CrewMessageRepository messageRepository;
    private final ControlRoomSnapshotService snapshotService;
    private final CrewLlmClient llmClient;
    private final CrewProperties properties;
    private final CrewModelAvailability modelAvailability;
    private final Clock clock;

    /** 단일 스레드 — 동시 1건 보장의 실행 레벨 방어. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "control-room-crew");
        t.setDaemon(true);
        return t;
    });

    public CrewOrchestrationService(CrewSessionRepository sessionRepository,
                                    CrewMessageRepository messageRepository,
                                    ControlRoomSnapshotService snapshotService,
                                    CrewLlmClient llmClient,
                                    CrewProperties properties,
                                    CrewModelAvailability modelAvailability,
                                    Clock clock) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.snapshotService = snapshotService;
        this.llmClient = llmClient;
        this.properties = properties;
        this.modelAvailability = modelAvailability;
        this.clock = clock;
    }

    /** 가드에 걸렸을 때 던진다 — 컨트롤러가 409/429 로 옮긴다. */
    public static class CrewUnavailableException extends RuntimeException {
        private final boolean limitReached;

        public CrewUnavailableException(String message, boolean limitReached) {
            super(message);
            this.limitReached = limitReached;
        }

        public boolean isLimitReached() { return limitReached; }
    }

    // ==================== 기동 정리 ====================

    /**
     * 재기동으로 끊긴 RUNNING 세션 정리. 이걸 안 하면 고아 행 하나가 동시 1건 가드를 영구히 막는다.
     * §4c: 조용히 지우지 않고 FAILED + 사유로 남긴다(무슨 일이 있었는지 이력에 보이게).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void failStrandedSessionsOnStartup() {
        try {
            List<CrewSession> stranded = sessionRepository.findTop20ByOrderByStartedAtDesc().stream()
                    .filter(s -> s.getStatus() == CrewSession.Status.RUNNING)
                    .toList();
            for (CrewSession s : stranded) {
                s.setStatus(CrewSession.Status.FAILED);
                s.setFailureReason("애플리케이션 재기동으로 중단됨 (이어서 실행하지 않는다 — 새 세션을 만들 것)");
                s.setFinishedAt(LocalDateTime.now(clock));
                sessionRepository.save(s);
                log.warn("[관제실 크루] 고아 세션 정리 — id={}", s.getId());
            }
        } catch (Exception e) {
            log.warn("[관제실 크루] 고아 세션 정리 실패: {}", e.getMessage());
        }
    }

    // ==================== 세션 시작 ====================

    /**
     * 지시 1건 → 세션 생성 후 백그라운드로 5턴 실행. 즉시 세션 id 를 돌려주고 화면은 폴링한다.
     *
     * @throws CrewUnavailableException 크루 비활성 / 동시 실행 중 / 일일 상한 도달
     */
    public CrewSession start(String instruction, String operator) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("지시 내용이 비어 있다");
        }

        modelAvailability.disabledReason().ifPresent(reason -> {
            throw new CrewUnavailableException("크루 비활성: " + reason, false);
        });

        // 동시 1건 가드 — 단, RUNNING 이 죽은 세션(파이프라인 스레드가 Error 로 죽는 등 프로세스는
        // 살아 있는데 상태만 남은 경우)이면 여기서 FAILED 로 밀어내고 진행한다. 기동 시 고아 정리는
        // 재시작해야만 돌기 때문에, 이 밀어내기가 재시작 없는 유일한 탈출구다.
        List<CrewSession> running = sessionRepository.findTop20ByOrderByStartedAtDesc().stream()
                .filter(s -> s.getStatus() == CrewSession.Status.RUNNING)
                .toList();
        if (!running.isEmpty()) {
            LocalDateTime now = LocalDateTime.now(clock);
            int staleMinutes = effectiveStaleMinutes(
                    properties.getTurnTimeoutSeconds(), properties.getStaleSessionMinutes());
            boolean allStale = running.stream().allMatch(s -> isStale(s, now, staleMinutes));
            if (!allStale) {
                throw new CrewUnavailableException("이미 실행 중인 크루 세션이 있다 (동시 1건)", false);
            }
            for (CrewSession stale : running) {
                stale.setStatus(CrewSession.Status.FAILED);
                stale.setFailureReason("응답 지연 " + staleMinutes + "분 초과로 중단 처리 — 새 세션이 밀어냄"
                        + " (자동 재시도 아님, 이 지시의 결과는 없다)");
                stale.setFinishedAt(now);
                sessionRepository.save(stale);
                log.warn("[관제실 크루] stale RUNNING 세션 밀어냄 — id={} (시작 {})",
                        stale.getId(), stale.getStartedAt());
            }
        }

        LocalDate today = LocalDate.now(clock);
        long usedToday = sessionRepository.countByStartedAtBetween(
                today.atStartOfDay(), today.atStartOfDay().plusDays(1));
        if (properties.dailyLimitReached(usedToday)) {
            throw new CrewUnavailableException(
                    "일일 상한 도달 (" + usedToday + "/" + properties.getDailyLimit() + ")", true);
        }

        ControlRoomSnapshotDto snapshot = snapshotService.currentForCrew();
        CrewContextBuilder.Context context =
                CrewContextBuilder.build(snapshot, properties.getContextLimitBytes());

        CrewSession session = sessionRepository.save(CrewSession.builder()
                .operator(operator)
                .instruction(instruction.trim())
                .status(CrewSession.Status.RUNNING)
                .model(properties.getModel())
                .contextBytes(context.bytes())
                .omittedFlags(context.omittedFlags())
                .startedAt(LocalDateTime.now(clock))
                .build());

        messageRepository.save(CrewMessage.builder()
                .sessionId(session.getId())
                .turnNo(ORDER_TURN)
                .agent(CrewMessage.Agent.OPERATOR)
                .phase(CrewMessage.Phase.ORDER)
                .content(instruction.trim())
                .build());

        Long sessionId = session.getId();
        executor.submit(() -> runPipeline(sessionId, instruction.trim(), context.text()));
        return session;
    }

    // ==================== 파이프라인 ====================

    private void runPipeline(Long sessionId, String instruction, String context) {
        List<String> transcript = new ArrayList<>();
        transcript.add("오퍼레이터 지시: " + instruction);

        int turnNo = 0;
        try {
            for (CrewPrompts.Step step : CrewPrompts.Step.values()) {
                turnNo++;

                int maxTokens = step.isReview()
                        ? properties.getReviewMaxTokens() : properties.getMaxTokens();
                String effort = step.isReview()
                        ? properties.getReviewEffort() : properties.getEffort();

                CrewLlmClient.Turn turn = llmClient.call(
                        CrewPrompts.system(step, context),
                        String.join("\n\n", transcript),
                        maxTokens,
                        effort);

                messageRepository.save(CrewMessage.builder()
                        .sessionId(sessionId)
                        .turnNo(turnNo)
                        .agent(step.agent())
                        .phase(step.phase())
                        .content(turn.text())
                        .model(turn.model())
                        .effort(turn.effort())
                        .maxTokens(turn.maxTokens())
                        .stopReason(turn.stopReason())
                        .inputTokens(turn.inputTokens())
                        .outputTokens(turn.outputTokens())
                        .build());

                transcript.add(CrewPrompts.displayName(step.agent()) + ": " + turn.text());
            }
            finish(sessionId, CrewSession.Status.COMPLETED, null);
            log.info("[관제실 크루] 세션 완료 — id={}", sessionId);
        } catch (Throwable e) {
            // 자동 재시도 없음 — 사유를 남기고 끝낸다. 재시도는 사람의 판단.
            // Throwable 까지 잡는 이유: Error(NoClassDef 등)로 스레드가 죽으면 세션이 RUNNING 으로
            // 박제돼 동시 1건 가드를 stale 임계까지 잠근다. 기록이 먼저다.
            String reason = "턴 " + turnNo + " 실패 — " + e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("[관제실 크루] 세션 실패 — id={} {}", sessionId, reason);
            finish(sessionId, CrewSession.Status.FAILED, reason);
        }
    }

    private void finish(Long sessionId, CrewSession.Status status, String failureReason) {
        try {
            sessionRepository.findById(sessionId).ifPresent(s -> {
                // stale 밀어내기가 이미 FAILED 로 닫은 세션을 뒤늦게 깨어난 스레드가
                // COMPLETED 로 덮어쓰지 못하게 — 종료 상태는 RUNNING 에서만 전이한다.
                if (s.getStatus() != CrewSession.Status.RUNNING) {
                    log.warn("[관제실 크루] 종료 상태 전이 무시 — id={} 이미 {}", sessionId, s.getStatus());
                    return;
                }
                s.setStatus(status);
                s.setFailureReason(failureReason);
                s.setFinishedAt(LocalDateTime.now(clock));
                sessionRepository.save(s);
            });
        } catch (Exception e) {
            log.error("[관제실 크루] 세션 종료 상태 저장 실패 — id={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * RUNNING 세션이 죽었다고 볼 임계(분) — <b>항상 5턴 최악 소요(5×턴 타임아웃)보다 크게</b> 보정한다.
     * 설정값이 최악 소요보다 작으면 정상 진행 중인 세션을 밀어내 이중 실행이 되기 때문이다.
     */
    static int effectiveStaleMinutes(int turnTimeoutSeconds, int configuredMinutes) {
        int worstCaseFloor = (int) Math.ceil(turnTimeoutSeconds * 5 / 60.0) + 5;   // +5분 여유
        return Math.max(configuredMinutes, worstCaseFloor);
    }

    /** startedAt 이 없으면(비정상 행) 즉시 stale 취급 — 판정 불가로 영구 잠금되는 것보다 낫다. */
    static boolean isStale(CrewSession session, LocalDateTime now, int staleMinutes) {
        if (session.getStartedAt() == null) return true;
        return session.getStartedAt().plusMinutes(Math.max(1, staleMinutes)).isBefore(now);
    }

    // ==================== 조회 ====================

    public List<CrewMessage> messages(Long sessionId) {
        return messageRepository.findBySessionIdOrderByTurnNoAsc(sessionId);
    }

    public List<CrewSession> recentSessions() {
        return sessionRepository.findTop20ByOrderByStartedAtDesc();
    }

    public CrewSession session(Long sessionId) {
        return sessionRepository.findById(sessionId).orElse(null);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
