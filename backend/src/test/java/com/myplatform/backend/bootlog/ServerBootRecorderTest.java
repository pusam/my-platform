package com.myplatform.backend.bootlog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 부팅 기록(V74) — 저장 내용과 '기록 실패해도 부팅은 계속된다'를 고정한다. */
class ServerBootRecorderTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-11-10 16:26:05 KST 에 부팅 완료. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T07:26:05Z"), KST);
    /** JVM 시작 2026-11-10 16:25:10.734 KST. */
    private static final Instant JVM_START = Instant.parse("2026-11-10T07:25:10.734Z");

    @Test
    @DisplayName("JVM 시작 시각(초 단위, KST)·완료 시각·컨테이너·커밋을 남긴다")
    void recordsBoot() {
        ServerBootLogRepository repo = mock(ServerBootLogRepository.class);
        ServerBootRecorder recorder = new ServerBootRecorder(repo, CLOCK, "1e3fb164abcdef", () -> JVM_START, () -> "a1b2c3d4e5f6");

        assertThat(recorder.record()).isTrue();

        ArgumentCaptor<ServerBootLog> saved = ArgumentCaptor.forClass(ServerBootLog.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getStartedAt()).isEqualTo(LocalDateTime.of(2026, 11, 10, 16, 25, 10));
        assertThat(saved.getValue().getReadyAt()).isEqualTo(LocalDateTime.of(2026, 11, 10, 16, 26, 5));
        assertThat(saved.getValue().getHost()).isEqualTo("a1b2c3d4e5f6");
        assertThat(saved.getValue().getRevision()).isEqualTo("1e3fb164abcdef");
    }

    @Test
    @DisplayName("커밋을 모르면 빈 문자열이 아니라 null")
    void blankRevisionIsNull() {
        ServerBootLogRepository repo = mock(ServerBootLogRepository.class);
        new ServerBootRecorder(repo, CLOCK, "  ", () -> JVM_START, () -> null).record();
        ArgumentCaptor<ServerBootLog> saved = ArgumentCaptor.forClass(ServerBootLog.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getRevision()).isNull();
        assertThat(saved.getValue().getHost()).isNull();
    }

    @Test
    @DisplayName("저장이 실패해도 예외를 던지지 않는다 — 재시작 기록 때문에 서버가 안 뜨면 안 된다")
    void saveFailureDoesNotBreakBoot() {
        ServerBootLogRepository repo = mock(ServerBootLogRepository.class);
        when(repo.save(any())).thenThrow(new RuntimeException("Table 'server_boot_log' doesn't exist"));
        ServerBootRecorder recorder = new ServerBootRecorder(repo, CLOCK, null, () -> JVM_START, () -> "h");

        assertThatCode(recorder::onApplicationReady).doesNotThrowAnyException();
        assertThat(recorder.record()).isFalse();
    }

    @Test
    @DisplayName("JVM 시작 시각을 못 읽어도 예외를 던지지 않는다")
    void jvmStartFailureDoesNotBreakBoot() {
        ServerBootLogRepository repo = mock(ServerBootLogRepository.class);
        ServerBootRecorder recorder = new ServerBootRecorder(repo, CLOCK, null,
                () -> { throw new IllegalStateException("no mxbean"); }, () -> "h");
        assertThatCode(recorder::onApplicationReady).doesNotThrowAnyException();
    }
}
