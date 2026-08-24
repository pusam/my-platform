package com.myplatform.backend.controlroom;

import com.myplatform.backend.entity.CrewMessage;
import com.myplatform.backend.entity.CrewSession;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 크루 세션 화면 표현 — 폴링 응답.
 *
 * <p>진행 상황은 {@code messages} 길이로 드러난다(5턴 중 몇 번째까지 왔는지). 별도 progress 필드를
 * 두지 않는 이유는 그것이 실제 저장된 턴 수와 어긋날 수 있기 때문이다.
 */
public record CrewSessionView(
        Long id,
        String status,
        String instruction,
        String operator,
        String model,
        Integer contextBytes,
        Integer omittedFlags,
        String failureReason,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        int totalTurns,
        List<Message> messages,
        List<String> actions,
        Usage usage
) {

    /** 5턴 고정 — 화면 진행 표시의 분모. */
    public static final int TOTAL_TURNS = 5;

    /**
     * @param truncated  stop_reason=max_tokens — 본문이 잘렸다. 화면이 "응답 잘림" 배지를 띄운다
     * @param addressedTo 이 발언이 향하는 상대("SCOUT · FIREWALL" 등). null 이면 표기 없음
     */
    public record Message(
            int turnNo,
            String agent,
            String displayName,
            String phase,
            String addressedTo,
            String content,
            boolean truncated,
            String stopReason,
            String model,
            String effort,
            Integer maxTokens,
            Integer inputTokens,
            Integer outputTokens
    ) {}

    /**
     * 세션 토큰 합계 — 비용 추적.
     *
     * @param complete false = 일부 턴의 usage 가 없어 합계가 과소 집계다(§4c — 0 으로 메우지 않는다)
     */
    public record Usage(int inputTokens, int outputTokens, boolean complete) {}

    public static CrewSessionView of(CrewSession session, List<CrewMessage> messages) {
        List<Message> views = new ArrayList<>();
        List<String> actions = List.of();

        int inputSum = 0;
        int outputSum = 0;
        boolean complete = true;

        for (CrewMessage m : messages) {
            boolean isClosing = m.getPhase() == CrewMessage.Phase.CLOSING;
            String content = m.getContent();
            if (isClosing) {
                actions = CrewActionParser.extract(content);
                content = CrewActionParser.stripActionLine(content);
            }

            if (m.getAgent() != CrewMessage.Agent.OPERATOR) {
                if (m.getInputTokens() == null || m.getOutputTokens() == null) {
                    complete = false;
                } else {
                    inputSum += m.getInputTokens();
                    outputSum += m.getOutputTokens();
                }
            }

            views.add(new Message(
                    m.getTurnNo(),
                    m.getAgent().name(),
                    CrewPrompts.displayName(m.getAgent()),
                    m.getPhase().name(),
                    addressedTo(m.getPhase()),
                    content,
                    "max_tokens".equals(m.getStopReason()),
                    m.getStopReason(),
                    m.getModel(),
                    m.getEffort(),
                    m.getMaxTokens(),
                    m.getInputTokens(),
                    m.getOutputTokens()));
        }

        return new CrewSessionView(
                session.getId(),
                session.getStatus().name(),
                session.getInstruction(),
                session.getOperator(),
                session.getModel(),
                session.getContextBytes(),
                session.getOmittedFlags(),
                session.getFailureReason(),
                session.getStartedAt(),
                session.getFinishedAt(),
                TOTAL_TURNS,
                views,
                actions,
                new Usage(inputSum, outputSum, complete));
    }

    private static String addressedTo(CrewMessage.Phase phase) {
        for (CrewPrompts.Step step : CrewPrompts.Step.values()) {
            if (step.phase() == phase) return step.addressedTo();
        }
        return null;
    }
}
