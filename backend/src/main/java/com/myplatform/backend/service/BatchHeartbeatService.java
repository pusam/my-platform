package com.myplatform.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 핵심 배치 크론 dead-man switch — "알림이 없다 ≠ 배치가 성공했다"
 * (ops/backup {@code check_backup_age.sh} 패턴의 앱 내부판).
 *
 * <p>배치가 실패하면 에러 로그/알림이 남지만, <b>크론 자체가 죽으면</b>(스케줄러 스레드 고갈·
 * 컨테이너 반기동·설정 실수) 아무 신호도 없다 — 각 배치가 <b>성공 시각을 Redis 에 기록</b>하고
 * (TTL 없음 — 심박이 스스로 사라지면 감시가 무의미. DB 아님 — 경량), 매일 09:05 체크 크론이
 * 마지막 성공 경과가 임계를 넘으면 텔레그램 리스크 채널로 1회 경고한다(잡별 일 1회 스로틀).
 *
 * <p><b>§4c(결측 근거 오탐 금지)</b>:
 * <ul>
 *   <li>심박 키 자체가 없으면(콜드스타트/최초 도입) 경고가 아니라 <b>INFO</b> — 다음 성공이 기록을 만든다.
 *   <li>Redis 조회 실패는 UNKNOWN — 감시 시스템 자신의 장애를 배치 사망으로 위장하지 않는다(경고 안 보냄).
 *   <li>{@link #recordSuccess} 는 best-effort — 심박 기록 실패가 배치 본체를 깨지 않는다.
 * </ul>
 *
 * <p><b>임계(주말 감안)</b>: 평가 4일(금 19:30→화 체크까지 정상 갭 3.6일 허용, 평일 2연속 실패에 경고) ·
 * 발굴 2일(전일 실패를 다음날 감지) · 주간 9일(일요일 1회 누락을 화요일 감지). 체크 크론이 MON-FRI 인
 * 이유: 주말엔 발굴(금 08:00)이 2일 임계를 정상적으로 넘겨 오탐이 나기 때문(월요일 09:05 체크는
 * 당일 08:00 성공 이후라 정상 주말 갭이 걸리지 않는다).
 */
@Service
@Slf4j
public class BatchHeartbeatService {

    static final String KEY_PREFIX = "java:batch-heartbeat:";
    private static final String CHECK_LOCK = "batch-heartbeat-check";

    public static final String JOB_SIGNAL_EVALUATION = "signal-evaluation";
    public static final String JOB_DISCOVERY_RESET = "discovery-reset";
    public static final String JOB_WEEKLY_REPORT = "weekly-report";

    /** 감시 대상 명세 — maxAge 는 주말/정상 갭을 감안한 완충 임계(클래스 주석 참조). */
    record JobSpec(String key, String label, Duration maxAge) {}

    static final List<JobSpec> JOBS = List.of(
            new JobSpec(JOB_SIGNAL_EVALUATION, "시그널 평가(평일 19:30)", Duration.ofDays(4)),
            new JobSpec(JOB_DISCOVERY_RESET, "발굴 일일 리셋(평일 08:00)", Duration.ofDays(2)),
            new JobSpec(JOB_WEEKLY_REPORT, "시그널 주간 리포트(일 18:00)", Duration.ofDays(9)));

    private final StringRedisTemplate redis;   // null 허용 — Redis 비활성(dev) 시 심박 비활성
    private final ObjectProvider<TelegramNotificationService> telegramProvider;
    private final SchedulerLockService schedulerLockService;
    private final Clock clock;

    /** 잡별 일 1회 경고 스로틀 (in-memory — 재기동 시 리셋되어 최대 1회 중복 경고, 무해). */
    private final Map<String, LocalDate> alertedOn = new ConcurrentHashMap<>();

    public BatchHeartbeatService(
            @Autowired(required = false) @Qualifier("cacheRedisTemplate") StringRedisTemplate redis,
            ObjectProvider<TelegramNotificationService> telegramProvider,
            SchedulerLockService schedulerLockService,
            Clock clock) {
        this.redis = redis;
        this.telegramProvider = telegramProvider;
        this.schedulerLockService = schedulerLockService;
        this.clock = clock;
        log.info("[배치심박] dead-man switch {} (감시 {}개 잡)", redis != null ? "활성" : "비활성(Redis 없음)", JOBS.size());
    }

    /**
     * 배치 성공 심박 기록 — 각 배치의 성공 경로 끝에서 호출. best-effort(실패해도 배치 본체 무영향).
     * TTL 없음: 심박이 스스로 만료되면 "마지막 성공이 언제였나"를 알 수 없어 dead-man switch 가 무의미.
     */
    public void recordSuccess(String jobKey) {
        if (redis == null) return;
        try {
            redis.opsForValue().set(KEY_PREFIX + jobKey, Instant.now(clock).toString());
        } catch (Exception e) {
            log.warn("[배치심박] 심박 기록 실패 ({}): {} — 배치 본체는 정상(best-effort)", jobKey, e.getMessage());
        }
    }

    /** 매일(평일) 09:05 — 각 잡의 마지막 성공 경과 점검. 08:00 발굴 리셋 이후 시각이라 당일 성공을 반영. */
    @Scheduled(scheduler = "batchScheduler", cron = "0 5 9 * * MON-FRI", zone = "Asia/Seoul")
    public void checkHeartbeats() {
        if (redis == null) return;
        if (!schedulerLockService.tryLock(CHECK_LOCK, Duration.ofMinutes(10))) {
            log.debug("[배치심박] 다른 인스턴스에서 체크 중 — 스킵");
            return;
        }
        Instant now = Instant.now(clock);
        for (JobSpec job : JOBS) {
            String raw;
            try {
                raw = redis.opsForValue().get(KEY_PREFIX + job.key());
            } catch (Exception e) {
                // 감시 시스템 자신의 장애(Redis 다운) — 배치 사망으로 위장 금지(§4c), 경고 안 보냄.
                log.warn("[배치심박] {} 심박 조회 실패(UNKNOWN): {}", job.key(), e.getMessage());
                continue;
            }
            Instant last = parseInstant(job.key(), raw);
            switch (judge(last, now, job.maxAge())) {
                case MISSING -> log.info("[배치심박] {} 심박 없음 — 콜드스타트/최초 도입으로 간주(경고 아님, §4c). "
                        + "다음 성공 실행이 심박을 만든다.", job.label());
                case STALE -> alertStale(job, last, now);
                case OK -> log.debug("[배치심박] {} 정상", job.key());
            }
        }
    }

    /**
     * 잡별 심박 현황 — <b>읽기 전용</b>. 관제실이 화면에 띄우려고 쓴다(2026-08-28).
     *
     * <p><b>왜 필요한가</b>: dead-man switch 결과가 <b>텔레그램으로만</b> 가고 화면엔 없었다.
     * 2026-08-28 주간 리포트 경보가 4일 연속 왔는데, 원인(8/23 일요일 크론 미실행)을 알아내려면
     * 코드·로그·DB 를 뒤져야 했다. 관제실에 있었으면 한눈에 끝났을 일이다.
     *
     * <p>Redis 조회 실패는 <b>UNKNOWN 으로 남긴다</b> — 감시 시스템 자신의 장애를
     * "배치 사망"으로 위장하지 않는다(§4c). 경보 로직과 같은 원칙.
     */
    public List<HeartbeatView> currentHeartbeats() {
        List<HeartbeatView> out = new ArrayList<>();
        Instant now = Instant.now(clock);
        for (JobSpec job : JOBS) {
            if (redis == null) {
                out.add(new HeartbeatView(job.key(), job.label(), null, "UNKNOWN", job.maxAge().toDays()));
                continue;
            }
            try {
                Instant last = parseInstant(job.key(), redis.opsForValue().get(KEY_PREFIX + job.key()));
                out.add(new HeartbeatView(job.key(), job.label(), last,
                        judge(last, now, job.maxAge()).name(), job.maxAge().toDays()));
            } catch (Exception e) {
                out.add(new HeartbeatView(job.key(), job.label(), null, "UNKNOWN", job.maxAge().toDays()));
            }
        }
        return out;
    }

    /**
     * @param verdict {@code OK} | {@code STALE} | {@code MISSING}(콜드스타트) | {@code UNKNOWN}(조회 실패)
     */
    public record HeartbeatView(String key, String label, Instant lastSuccess,
                                String verdict, long maxAgeDays) {}

    enum Verdict { OK, MISSING, STALE }

    /**
     * 심박 판정 — 순수 함수(테스트 대상).
     * 마지막 성공 없음 = MISSING(콜드스타트 — 호출부가 INFO 처리, 경고 금지 §4c).
     * 경과 == 임계는 OK(초과만 STALE).
     */
    static Verdict judge(Instant lastSuccess, Instant now, Duration maxAge) {
        if (lastSuccess == null) return Verdict.MISSING;
        return Duration.between(lastSuccess, now).compareTo(maxAge) > 0 ? Verdict.STALE : Verdict.OK;
    }

    /** ISO Instant 파싱 — 없거나(콜드스타트) 손상이면 null(호출부가 MISSING 처리). */
    static Instant parseInstant(String jobKey, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            log.warn("[배치심박] {} 심박 값 손상('{}') — MISSING 으로 처리", jobKey, raw);
            return null;
        }
    }

    private void alertStale(JobSpec job, Instant last, Instant now) {
        LocalDate today = LocalDate.now(clock);
        if (today.equals(alertedOn.get(job.key()))) return;   // 잡별 일 1회
        alertedOn.put(job.key(), today);

        double elapsedDays = Duration.between(last, now).toHours() / 24.0;
        String msg = String.format(
                "🚨 <b>[배치 사망감지] %s</b>\n\n"
                        + "• 마지막 성공: %s (%.1f일 경과, 임계 %d일)\n"
                        + "• 크론 사망/연속 실패 의심 — 스케줄러 스레드·컨테이너 로그 확인 필요\n"
                        + "• \"에러 알림이 없었다\"는 성공의 근거가 아님 (dead-man switch)",
                job.label(), last, elapsedDays, job.maxAge().toDays());
        log.error("[배치심박] {} 마지막 성공 {} — 임계 {}일 초과", job.label(), last, job.maxAge().toDays());
        TelegramNotificationService telegram = telegramProvider.getIfAvailable();
        if (telegram != null) {
            try {
                telegram.sendRisk(msg);
            } catch (Exception e) {
                log.warn("[배치심박] 텔레그램 발송 실패: {}", e.getMessage());
            }
        }
    }
}
