package com.myplatform.backend.controlroom;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
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

    /**
     * 모델 확인에 쓰는 클라이언트 타임아웃.
     *
     * <p>SDK 기본이 10분이라 그대로 두면 <b>재확인 엔드포인트가 요청 스레드를 10분간 붙잡을 수 있다.</b>
     * 이 호출은 모델 목록 조회 한 번이라 20초면 충분하고, 넘어가면 그 자체가 "확인 실패"로 처리되는 게 맞다.
     */
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(20);

    /**
     * 페이지네이션 소비 상한. 모델 목록은 수십 종이라 이걸 넘으면 목록이 긴 게 아니라 <b>커서가 안 도는 것</b>이다.
     *
     * <p><b>왜 상한이 필요한가(2026-09-15 실사고)</b>: 이전 코드는
     * {@code client.models().list().autoPager().forEach(m -> available.add(m.id()))} 였다.
     * 2026-09-11 에 구독 게이트웨이({@code control-room.crew.api-base})로 돌리자 그 게이트웨이의
     * {@code /v1/models} 가 커서를 진전시키지 않아 <b>autoPager 가 같은 페이지를 무한히 따라갔고</b>,
     * {@code available} 에 모델 ID 문자열이 끝없이 쌓였다 — 힙 덤프에서 {@code "claude-opus-5"}·
     * {@code "claude-sonnet-5"} 문자열 <b>3,800만 개(힙의 83%)</b>와 원소 4,672만 개짜리 배열이 나왔다.
     * ⚠ {@link #VERIFY_TIMEOUT} 은 <b>요청당</b> 타임아웃이라 각 페이지가 빨리 오면 전체 루프를 못 막는다.
     * ⚠ 게다가 {@code verifyOnStartup} 은 {@code @Async} 라 이 루프가 조용히 돈다.
     */
    static final int MAX_MODEL_ITEMS = 500;

    /** 모델 목록 스캔 결과. {@code truncated}=상한에 걸림(= 페이지네이션 이상 신호). */
    record ModelScan(List<String> ids, boolean truncated) {}

    /**
     * 페이지네이션을 <b>유한하게</b> 소비한다 — 순수 함수({@code CrewModelPaginationTest}).
     *
     * <p>중복은 합치되 <b>상한은 반복 횟수로</b> 건다: 게이트웨이가 같은 페이지를 반복하면 distinct 개수는
     * 안 늘어나므로 "수집된 개수"로 끊으면 영원히 안 끝난다.
     */
    static ModelScan scanBounded(java.util.Iterator<String> ids, int maxItems) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        int seen = 0;
        while (ids.hasNext()) {
            if (seen >= maxItems) {
                return new ModelScan(List.copyOf(out), true);
            }
            seen++;
            String id = ids.next();
            if (id != null && !id.isBlank()) {
                out.add(id);
            }
        }
        return new ModelScan(List.copyOf(out), false);
    }

    /**
     * 모델 목록 확인. 기동 시 1회 + 관리자가 키/모델 설정을 고친 뒤 재기동 없이 다시 부를 때 호출된다
     * ({@code POST /api/control-room/crew/verify}).
     *
     * <p>결과는 이 객체의 상태에만 반영된다 — 크루 가용 상태는 스냅샷이 매번 새로 읽으므로
     * 다음 폴링에서 화면에 그대로 나타난다.
     */
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
                    // 기본은 Anthropic. 구독 게이트웨이로 돌릴 때만 이 값이 바뀐다.
                    .baseUrl(properties.getApiBase())
                    .timeout(VERIFY_TIMEOUT)
                    .build();

            var pager = client.models().list().autoPager();
            ModelScan scan = scanBounded(
                    java.util.stream.StreamSupport.stream(pager.spliterator(), false)
                            .map(m -> m.id()).iterator(),
                    MAX_MODEL_ITEMS);
            List<String> available = scan.ids();
            if (scan.truncated()) {
                // 침묵 금지(§4c) — 여기서 조용히 끊으면 게이트웨이가 깨진 걸 아무도 모른다.
                log.warn("[관제실] /v1/models 가 {}건을 넘겨도 끝나지 않음 — 게이트웨이 페이지네이션 이상"
                        + "(커서 미진전) 의심. 상한에서 끊고 진행한다. api-base={}",
                        MAX_MODEL_ITEMS, properties.getApiBase());
            }

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
