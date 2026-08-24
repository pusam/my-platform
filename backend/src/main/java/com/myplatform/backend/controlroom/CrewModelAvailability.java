package com.myplatform.backend.controlroom;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 기동 시 모델 실재 확인 — {@code GET /v1/models} 를 한 번 호출해 설정된 모델 ID 가 목록에 있는지 본다.
 *
 * <p><b>실패해도 앱 전체는 정상 기동한다.</b> 크루 기능만 DISABLED 로 두고 화면이 그 사유를 명시한다
 * (§4c — 버튼만 죽어 있고 이유를 모르는 상태를 만들지 않는다). 키가 없을 때도 같은 처리다.
 *
 * <p>확인은 비동기다. 확인이 끝나기 전 상태는 "확인 중"이며 이때도 크루는 비활성이다 —
 * 모델이 실재하는지 모르는 채로 과금 호출을 내보내지 않는다.
 */
@Slf4j
@Component
public class CrewModelAvailability {

    private final CrewProperties properties;
    private final AtomicReference<String> disabledReason =
            new AtomicReference<>("모델 확인 중 — 기동 직후 GET /v1/models 응답 대기");

    public CrewModelAvailability(CrewProperties properties) {
        this.properties = properties;
    }

    /** 크루를 못 쓰는 사유. 비어 있으면 정상. */
    public Optional<String> disabledReason() {
        String reason = disabledReason.get();
        return (reason == null || reason.isBlank()) ? Optional.empty() : Optional.of(reason);
    }

    public boolean enabled() {
        return disabledReason().isEmpty();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void verifyOnStartup() {
        verify();
    }

    /** 모델 목록 확인. 관리자가 설정을 고친 뒤 재기동 없이 다시 확인하고 싶을 때도 호출 가능. */
    public void verify() {
        if (!properties.hasApiKey()) {
            disable("ANTHROPIC_API_KEY 미설정 — 크루 비활성 (앱 나머지는 정상)");
            return;
        }

        String configured = properties.getModel();
        if (configured == null || configured.isBlank()) {
            disable("control-room.crew.model 미설정 — 크루 비활성");
            return;
        }

        try {
            AnthropicClient client = AnthropicOkHttpClient.builder()
                    .apiKey(properties.getApiKey())
                    .build();

            List<String> available = new ArrayList<>();
            client.models().list().autoPager().forEach(model -> available.add(model.id()));

            if (available.isEmpty()) {
                disable("GET /v1/models 응답이 비어 있음 — 모델 실재를 확인하지 못해 크루 비활성");
                return;
            }
            if (!available.contains(configured)) {
                // 임의 대체 금지 — 모르는 모델로 조용히 바꿔 과금하지 않는다. 사람이 고른다.
                disable("설정된 모델 '" + configured + "' 이 /v1/models 목록에 없음. 사용 가능: "
                        + String.join(", ", available.subList(0, Math.min(available.size(), 8))));
                return;
            }

            disabledReason.set(null);
            log.info("[관제실] 크루 모델 확인 완료 — {} (사용 가능 모델 {}종)", configured, available.size());
        } catch (Exception e) {
            disable("모델 목록 조회 실패 (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
    }

    private void disable(String reason) {
        disabledReason.set(reason);
        log.warn("[관제실] 크루 비활성 — {}", reason);
    }
}
