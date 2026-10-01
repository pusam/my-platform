package com.myplatform.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 추천 성적의 "현재 산식 표본" 시작일 — 단일 출처(2026-10-01).
 *
 * <p>추천 입력 정의가 바뀌면(9/29~10/1 실적 가드·성장률·지배주주 PER·결산월, 10/1 스크리너 미래 행·AI 순위) 그 전 성적은
 * 지금 산식의 성적이 아니다. 그래서 관제실 신뢰 게이트·결론 카드·오늘 탭·종합판단 이력·종목 신호 이력은 전부 이 날짜
 * 이후 기록된 시그널만 "현재 산식 실측"으로 센다.
 *
 * <p><b>기본값은 '미정'이다.</b> 시작일은 배포 시각이 아니라 <b>수정이 재무 수집·추천 캐시·스냅샷까지 반영된 뒤의 첫 온전한
 * 거래일</b>이라, 배포 전에 날짜를 박아 둘 수 없다. 운영자가 반영을 확인한 뒤 날짜를 넣고(잠정), 그날 첫 추천에 수정된 입력이
 * 실제로 들어간 것까지 확인하면 확정한다. 미정이면 화면은 옛 값으로 채우지 않고 '검증 중'이다(§4c).
 *
 * <ul>
 *   <li>{@code recommendation.sample.since} — ISO 날짜. 비었거나 날짜가 아니면 미정(UNSET).</li>
 *   <li>{@code recommendation.sample.confirmed} — true 면 확정(CONFIRMED), 아니면 잠정(PROVISIONAL).</li>
 * </ul>
 */
@Component
@Slf4j
public class SignalSampleBoundary {

    public enum Status { UNSET, PROVISIONAL, CONFIRMED }

    /** 표본 경계 — {@code since == null} 이면 미정. */
    public record Boundary(LocalDate since, Status status) {
        public static final Boundary UNSET = new Boundary(null, Status.UNSET);

        public boolean isSet() {
            return since != null && status != Status.UNSET;
        }
    }

    private final String raw;
    private final Boundary boundary;

    public SignalSampleBoundary(@Value("${recommendation.sample.since:}") String since,
                                @Value("${recommendation.sample.confirmed:false}") boolean confirmed) {
        this.raw = since;
        this.boundary = resolve(since, confirmed);
    }

    public Boundary current() {
        return boundary;
    }

    /** 설정값 → 경계. 비었거나 날짜가 아니면 미정 — 잘못된 값이 엉뚱한 날짜로 바뀌지 않게. 순수 함수(테스트 대상). */
    static Boundary resolve(String since, boolean confirmed) {
        if (since == null || since.isBlank()) return Boundary.UNSET;
        try {
            LocalDate d = LocalDate.parse(since.trim());
            return new Boundary(d, confirmed ? Status.CONFIRMED : Status.PROVISIONAL);
        } catch (DateTimeParseException e) {
            return Boundary.UNSET;
        }
    }

    /** 미정·잠정은 기동마다 한 번 드러낸다 — 조용히 '검증 중'이 계속되면 확정을 잊는다. */
    @EventListener(ApplicationReadyEvent.class)
    public void reportOnStartup() {
        if (!boundary.isSet()) {
            boolean invalid = raw != null && !raw.isBlank();
            log.warn("[표본 경계] 현재 산식 표본 시작일 {} — 적중률 표시는 '검증 중'. 수정 반영 확인 후 "
                            + "RECOMMENDATION_SAMPLE_SINCE(YYYY-MM-DD) 를 넣을 것",
                    invalid ? "설정값이 날짜가 아님('" + raw.trim() + "')" : "미정");
        } else if (boundary.status() == Status.PROVISIONAL) {
            log.info("[표본 경계] 현재 산식 표본 시작일 {} (잠정) — 첫 추천 반영 확인 후 RECOMMENDATION_SAMPLE_CONFIRMED=true",
                    boundary.since());
        } else {
            log.info("[표본 경계] 현재 산식 표본 시작일 {} (확정)", boundary.since());
        }
    }
}
