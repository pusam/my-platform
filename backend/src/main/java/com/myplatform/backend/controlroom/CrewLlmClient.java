package com.myplatform.backend.controlroom;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 크루 LLM 호출 — Anthropic Messages API 한 턴.
 *
 * <p><b>툴을 절대 주지 않는다.</b> 크루는 시스템 프롬프트와 대화 기록만 받는다. DB·파일·봇에 접근할
 * 경로를 애초에 만들지 않는 것이 관제실 읽기 전용 원칙의 실제 구현이다.
 *
 * <p><b>자동 재시도 없음.</b> 실패는 예외로 올려 세션을 FAILED 로 만든다 — 조용한 재시도는 비용이
 * 새는 가장 흔한 경로다. 재시도 여부는 사람이 정한다.
 *
 * <p>{@code stop_reason=max_tokens} 는 예외가 아니라 정상 응답이다. 다만 <b>본문이 잘렸다</b>는
 * 뜻이므로 그대로 올려보내 화면이 "응답 잘림" 배지를 띄우게 한다(§4c — 잘린 걸 완결로 위장 금지).
 */
@Slf4j
@Component
public class CrewLlmClient {

    private final CrewProperties properties;
    private volatile AnthropicClient client;

    public CrewLlmClient(CrewProperties properties) {
        this.properties = properties;
    }

    /**
     * 한 턴의 응답.
     *
     * @param text         본문(text 블록만 이어붙임)
     * @param stopReason   end_turn / max_tokens / refusal 등. max_tokens = 잘림
     * @param inputTokens  usage 없으면 null (§4c — 0 으로 위장하지 않음, 비용 집계에서 제외)
     */
    public record Turn(String text, String stopReason, Integer inputTokens, Integer outputTokens,
                       String model, String effort, int maxTokens) {

        public boolean truncated() {
            return "max_tokens".equals(stopReason);
        }
    }

    /**
     * 한 턴 호출.
     *
     * @param system     시스템 프롬프트(역할 + 컨텍스트)
     * @param transcript 지금까지의 대화 기록 — 단일 user 메시지로 넘긴다
     */
    public Turn call(String system, String transcript, int maxTokens, String effort) {
        String model = properties.getModel();

        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(system)
                .addUserMessage(transcript)
                .outputConfig(OutputConfig.builder().effort(toEffort(effort)).build())
                .build();

        Message response = client().messages().create(params);

        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"))
                .trim();

        String stopReason = response.stopReason().map(Object::toString).orElse(null);
        Integer inputTokens = toInt(response.usage() == null ? null : response.usage().inputTokens());
        Integer outputTokens = toInt(response.usage() == null ? null : response.usage().outputTokens());

        if ("max_tokens".equals(stopReason)) {
            log.warn("[관제실 크루] 응답 잘림 — max_tokens={} 도달 (재시도하지 않음)", maxTokens);
        }
        return new Turn(text, stopReason, inputTokens, outputTokens, model, effort, maxTokens);
    }

    /** 클라이언트는 첫 호출 때 만든다 — 키가 없어도 앱 기동을 막지 않기 위해. */
    private AnthropicClient client() {
        AnthropicClient local = client;
        if (local != null) return local;
        synchronized (this) {
            if (client == null) {
                if (!properties.hasApiKey()) {
                    throw new IllegalStateException("ANTHROPIC_API_KEY 미설정 — 크루 호출 불가");
                }
                client = AnthropicOkHttpClient.builder()
                        .apiKey(properties.getApiKey())
                        // SDK 기본 타임아웃은 10분 — 한 턴이 매달리면 단일 스레드 실행기가
                        // 최대 50분 잠기고 그동안 동시 1건 가드가 새 세션까지 막는다.
                        // 초과는 예외로 올라가 세션이 FAILED 로 끝난다(재시도는 사람이).
                        .timeout(Duration.ofSeconds(Math.max(30, properties.getTurnTimeoutSeconds())))
                        // 전송 계층 재시도도 끈다. SDK 기본(2회)은 타임아웃·429 를 자동 재전송하는데,
                        // 타임아웃 재전송은 이미 과금됐을 수 있는 생성을 한 번 더 사는 것이고
                        // 턴 벽시계도 3배가 된다 — "자동 재시도 금지" 비용 가드를 전송 계층까지 일관 적용.
                        .maxRetries(0)
                        .build();
            }
            return client;
        }
    }

    private static OutputConfig.Effort toEffort(String raw) {
        String v = raw == null ? "low" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "medium" -> OutputConfig.Effort.MEDIUM;
            case "high" -> OutputConfig.Effort.HIGH;
            default -> OutputConfig.Effort.LOW;
        };
    }

    private static Integer toInt(Long value) {
        return value == null ? null : Math.toIntExact(value);
    }
}
