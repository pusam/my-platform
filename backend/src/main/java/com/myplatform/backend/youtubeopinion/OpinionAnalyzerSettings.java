package com.myplatform.backend.youtubeopinion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 유튜브 의견 분석기 선택(2026-10-01) — GEMINI(기본, 서버가 직접 호출) 또는 CLAUDE(로컬 작업자 프로세스가 실행).
 *
 * <p><b>기본은 GEMINI 다</b> — 명시적으로 {@code youtube-opinion.analyzer=CLAUDE} 를 넣기 전까지 운영 동작은 그대로다.
 * 알 수 없는 값은 GEMINI 로 보고 부팅 때 경고한다(조용히 다른 분석기로 가지 않는다).
 *
 * <p>CLAUDE 일 때 서버는 Claude 를 부르지 않는다 — 실행 기록을 대기열(QUEUED)에 올리고, 구독 로그인이 된 로컬 PC 의 작업자가
 * 가져가 결과(모델 응답 원문)만 돌려준다. 응답 해석·검증·저장은 Gemini 와 같은 경로({@link OpinionValidator})다.
 */
@Component
@Slf4j
public class OpinionAnalyzerSettings {

    public enum Analyzer { GEMINI, CLAUDE }

    private final String raw;
    private final Analyzer analyzer;
    private final String claudeModel;
    private final int leaseMinutes;
    private final int maxAttempts;
    private final int maxConcurrent;
    private final int maxWaitHours;

    public OpinionAnalyzerSettings(@Value("${youtube-opinion.analyzer:GEMINI}") String analyzer,
                                   @Value("${youtube-opinion.claude.model:sonnet}") String claudeModel,
                                   @Value("${youtube-opinion.claude.lease-minutes:30}") int leaseMinutes,
                                   @Value("${youtube-opinion.claude.max-attempts:3}") int maxAttempts,
                                   @Value("${youtube-opinion.claude.max-concurrent:1}") int maxConcurrent,
                                   @Value("${youtube-opinion.claude.max-wait-hours:72}") int maxWaitHours) {
        this.raw = analyzer;
        this.analyzer = parse(analyzer);
        this.claudeModel = claudeModel == null || claudeModel.isBlank() ? "sonnet" : claudeModel.trim();
        this.leaseMinutes = Math.max(5, leaseMinutes);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.maxWaitHours = Math.max(1, maxWaitHours);
    }

    /** 테스트·기본값 — Gemini. */
    public static OpinionAnalyzerSettings gemini() {
        return new OpinionAnalyzerSettings("GEMINI", "sonnet", 30, 3, 1, 72);
    }

    /** 설정값 → 분석기. 모르는 값은 GEMINI(기본 동작 유지). 순수 함수. */
    static Analyzer parse(String value) {
        if (value == null) return Analyzer.GEMINI;
        return "CLAUDE".equals(value.trim().toUpperCase(Locale.ROOT)) ? Analyzer.CLAUDE : Analyzer.GEMINI;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportOnStartup() {
        boolean unknown = raw != null && !raw.isBlank()
                && !raw.trim().equalsIgnoreCase("GEMINI") && !raw.trim().equalsIgnoreCase("CLAUDE");
        if (unknown) {
            log.warn("[유튜브의견] 분석기 설정값 '{}' 을 알 수 없어 GEMINI 로 동작한다(GEMINI|CLAUDE)", raw.trim());
        } else if (analyzer == Analyzer.CLAUDE) {
            log.info("[유튜브의견] 분석기 CLAUDE — 서버는 대기열에만 올린다. 로컬 작업자(tools/youtube-claude-worker)가 실행해야 "
                    + "분석이 진행된다(요청 모델 {}, 임대 {}분, 시도 {}회)", claudeModel, leaseMinutes, maxAttempts);
        }
    }

    public Analyzer analyzer() { return analyzer; }
    public boolean isClaude() { return analyzer == Analyzer.CLAUDE; }
    public String claudeModel() { return claudeModel; }
    public int leaseMinutes() { return leaseMinutes; }
    public int maxAttempts() { return maxAttempts; }
    public int maxConcurrent() { return maxConcurrent; }
    public int maxWaitHours() { return maxWaitHours; }
}
