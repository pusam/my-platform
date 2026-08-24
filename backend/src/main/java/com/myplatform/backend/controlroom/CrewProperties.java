package com.myplatform.backend.controlroom;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 관제실 크루 설정 — <b>모델 ID 하드코딩 금지</b>. 전부 설정/환경변수로 뺀다.
 *
 * <p>비용 가드가 여기 모여 있다. 크루는 오퍼레이터 1명이 쓰는 콘솔이지만, LLM 호출은 조용히 새기
 * 쉬운 비용이라 상한을 코드가 아니라 설정으로 들고 있어야 조정이 쉽다.
 *
 * <ul>
 *   <li><b>5턴 고정</b> — 자동 루프·재시도로 늘어나지 않는다(코드 상수, 설정 아님)</li>
 *   <li><b>일일 세션 상한</b> — 초과 시 조용히 스킵하지 않고 429 로 거부, 화면에 "일일 상한 도달" 명시</li>
 *   <li><b>동시 1건</b> — 연타로 세션이 겹치지 않게</li>
 *   <li><b>컨텍스트 상한</b> — 초과 시 FLAGGED 뒤쪽부터 잘라내고 "N건 생략" 표기</li>
 * </ul>
 */
@Getter
@Component
public class CrewProperties {

    /** 모델 ID. 기동 시 {@code GET /v1/models} 로 실재 여부를 확인한다({@link CrewModelAvailability}). */
    @Value("${control-room.crew.model:claude-opus-5}")
    private String model;

    /** 하루 세션 상한. 0 이하면 무제한. */
    @Value("${control-room.crew.daily-limit:30}")
    private int dailyLimit;

    /** 스냅샷 컨텍스트 바이트 상한(UTF-8). 초과분은 FLAGGED 뒤쪽부터 잘라낸다. */
    @Value("${control-room.crew.context-limit-bytes:8192}")
    private int contextLimitBytes;

    /** 일반 턴(에렌 분배·SCOUT 초안·SCOUT 반영·에렌 결론) max_tokens. */
    @Value("${control-room.crew.max-tokens:1000}")
    private int maxTokens;

    /** FIREWALL 검토 턴 max_tokens — 불변식 대조라 여유를 더 준다. */
    @Value("${control-room.crew.review-max-tokens:2000}")
    private int reviewMaxTokens;

    /**
     * 일반 턴 effort. Claude Opus 5 는 adaptive thinking 이 기본 ON 이고 사고 토큰이 max_tokens 예산을
     * 함께 쓴다 — effort 를 낮추지 않으면 1000 토큰이 사고에 먹혀 본문이 잘린다.
     */
    @Value("${control-room.crew.effort:low}")
    private String effort;

    /** FIREWALL 검토 턴 effort — 판단 품질이 필요한 유일한 턴이라 한 단계 위. */
    @Value("${control-room.crew.review-effort:medium}")
    private String reviewEffort;

    /** API 키. 비어 있으면 크루 기능만 DISABLED(앱 전체는 정상 기동). */
    @Value("${control-room.crew.api-key:${ANTHROPIC_API_KEY:}}")
    private String apiKey;

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean dailyLimitReached(long usedToday) {
        return dailyLimit > 0 && usedToday >= dailyLimit;
    }
}
