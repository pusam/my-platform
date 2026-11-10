package com.myplatform.backend.bootlog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;

/**
 * 부팅할 때마다 {@code server_boot_log} 에 한 행을 남긴다(V74, 2026-11-10).
 *
 * <p>왜: 표본 시작일은 08:30~15:50 에 재시작이 없던 거래일이어야 하는데(CLAUDE.md §7), 재시작 시각은 {@code docker inspect}
 * 의 마지막 한 번뿐이었다. 이 기록을 관제실 '장중 재시작' 카드가 읽는다.
 *
 * <p><b>기록이 실패해도 부팅은 계속된다</b> — 예외는 WARN 한 줄로 삼킨다. 재시작 기록 때문에 서버가 안 뜨면 주객전도다.
 * 대신 관제실은 지금 프로세스의 시작 시각(메모리)을 기록과 대조해 '이번 부팅 기록 누락'을 따로 말한다(§4c).
 */
@Slf4j
@Component
public class ServerBootRecorder {

    private final ServerBootLogRepository repository;
    private final Clock clock;
    private final String revision;
    private final Supplier<Instant> jvmStart;
    private final Supplier<String> host;

    @Autowired
    public ServerBootRecorder(ServerBootLogRepository repository,
                              Clock clock,
                              @Value("${APP_REVISION:}") String revision) {
        this(repository, clock, revision, ServerBootRecorder::jvmStartInstant, ServerBootRecorder::hostName);
    }

    ServerBootRecorder(ServerBootLogRepository repository, Clock clock, String revision,
                       Supplier<Instant> jvmStart, Supplier<String> host) {
        this.repository = repository;
        this.clock = clock;
        this.revision = (revision == null || revision.isBlank()) ? null : revision.trim();
        this.jvmStart = jvmStart;
        this.host = host;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        record();
    }

    /** @return 저장했으면 true. 실패는 예외 없이 false(부팅을 막지 않는다). */
    boolean record() {
        try {
            LocalDateTime startedAt = jvmStartedAt(jvmStart.get(), clock.getZone());
            LocalDateTime readyAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
            repository.save(ServerBootLog.builder()
                    .startedAt(startedAt)
                    .readyAt(readyAt)
                    .host(truncate(host.get(), 100))
                    .revision(truncate(revision, 64))
                    .build());
            log.info("[부팅 기록] 시작 {} · 완료 {} · 커밋 {}", startedAt, readyAt, revision == null ? "모름" : revision);
            return true;
        } catch (Exception e) {
            log.warn("[부팅 기록] 저장 실패 — 부팅은 계속한다(관제실 '장중 재시작' 카드가 이번 부팅을 기록 누락으로 표시한다): {}",
                    e.toString());
            return false;
        }
    }

    /** 지금 프로세스가 뜬 시각(초 단위, 주어진 시간대). 관제실이 기록과 대조할 때도 같은 값을 쓴다. */
    public static LocalDateTime currentProcessStartedAt(ZoneId zone) {
        return jvmStartedAt(jvmStartInstant(), zone);
    }

    static LocalDateTime jvmStartedAt(Instant start, ZoneId zone) {
        return LocalDateTime.ofInstant(start, zone).truncatedTo(ChronoUnit.SECONDS);
    }

    private static Instant jvmStartInstant() {
        return Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    private static String hostName() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) return env.trim();
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
