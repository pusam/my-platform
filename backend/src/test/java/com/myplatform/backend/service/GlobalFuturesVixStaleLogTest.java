package com.myplatform.backend.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 'VIX ≥25 인데 지난 값이라 위기 판정을 건너뛴다' 로그 — 정말 그럴 때만, 같은 상태면 하루 한 번(2026-10-07).
 *
 * <p>재현: 한국 낮에는 VIX 가 늘 지난 값(미국장 마감)인데, 그때마다 VIX 수준과 상관없이
 * "[코스피 전망] VIX 15.0 (≥25) but stale (311분 전) — CRISIS 오버라이드 스킵" 이 INFO 로 남았다 — VIX 15 를 25 이상이라 하고,
 * 글로벌 화면이 30초마다 부를 때마다 같은 줄이 쌓였다(10/7 오전 운영 19줄). 판정·점수는 그대로다.
 */
class GlobalFuturesVixStaleLogTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 10, 26);

    private GlobalFuturesService service;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        service = new GlobalFuturesService(mock(RestTemplate.class), new ObjectMapper(), mock(KisApiService.class));
        logger = (Logger) LoggerFactory.getLogger(GlobalFuturesService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private static GlobalFuturesService.FuturesQuote staleVix(String level) {
        return GlobalFuturesService.FuturesQuote.builder()
                .symbol("VIX").success(true).stale(true).dataAgeMinutes(311)
                .changeRate(new BigDecimal("-3.29")).currentPrice(new BigDecimal(level))
                .build();
    }

    private long skipLines() {
        return appender.list.stream().filter(e -> e.getFormattedMessage().contains("CRISIS 오버라이드 스킵")).count();
    }

    @Test
    @DisplayName("재현: VIX 15 가 지난 값이면 'VIX 15.0 (≥25)' 로그를 남기지 않는다 — 건너뛸 위기 판정이 없다")
    void calmStaleVixLogsNothing() {
        service.analyzeImpact(List.of(staleVix("15.0")), NOW);
        service.analyzeImpact(List.of(staleVix("15.0")), NOW.plusSeconds(30));

        assertThat(skipLines()).isZero();
    }

    @Test
    @DisplayName("VIX 27 이 지난 값이면 한 번 남기고, 같은 상태로 다시 불려도 되풀이하지 않는다")
    void highStaleVixLogsOnce() {
        service.analyzeImpact(List.of(staleVix("27.0")), NOW);
        service.analyzeImpact(List.of(staleVix("27.0")), NOW.plusSeconds(30));
        service.analyzeImpact(List.of(staleVix("27.0")), NOW.plusMinutes(5));

        assertThat(skipLines()).isEqualTo(1);
        assertThat(appender.list.stream().filter(e -> e.getFormattedMessage().contains("CRISIS 오버라이드 스킵"))
                .findFirst().orElseThrow().getFormattedMessage()).contains("VIX 27.0");
    }

    @Test
    @DisplayName("다음 날 같은 상태면 하루 한 번은 다시 남긴다")
    void nextDayLogsAgain() {
        service.analyzeImpact(List.of(staleVix("27.0")), NOW);
        service.analyzeImpact(List.of(staleVix("27.0")), NOW.plusDays(1));

        assertThat(skipLines()).isEqualTo(2);
    }
}
