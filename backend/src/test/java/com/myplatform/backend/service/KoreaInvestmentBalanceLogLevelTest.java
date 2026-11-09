package com.myplatform.backend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 잔고 조회의 초당 건수 초과(EGW00215)는 재시도된다 — 시도마다 ERROR 를 남기지 않는다(2026-11-09).
 *
 * <p>재현(운영 11/9): 잔고 조회 ERROR 5줄(10:10×2·11:52·13:34·15:40)이 전부 같은 초에 다른 KIS 호출과 부딪힌
 * EGW00215 였고 전부 재시도로 받아왔다 — 실패는 0건인데 매일 보는 ERROR 건수에 섞였다(집계 {@link KisCallTally} 는
 * 2026-09-03 부터 호출 단위라 이미 0). 재시도가 바닥나면 rateLimiter 가 '최대 재시도 초과' ERROR 를 남기고 호출 단위로
 * 실패를 센다 — 그 줄은 그대로다. 초당 건수 초과가 아닌 실패(예: 만료 토큰 EGW00123)는 종전대로 ERROR.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KoreaInvestmentBalanceLogLevelTest {

    private static final String EGW00215 =
            "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00215\",\"msg1\":\"원장에서 허용 가능한 초당 거래건수를 초과하였습니다.\"}";
    private static final String EGW00123 =
            "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\",\"msg1\":\"기간이 만료된 token 입니다.\"}";
    private static final String OK =
            "{\"rt_cd\":\"0\",\"output1\":[],\"output2\":[{\"dnca_tot_amt\":\"1000000\"}]}";

    @Mock private RestTemplate restTemplate;

    private KoreaInvestmentService service;
    private final Logger kisLogger = (Logger) LoggerFactory.getLogger(KoreaInvestmentService.class);
    private final Logger limiterLogger = (Logger) LoggerFactory.getLogger(KisApiRateLimiter.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        KisCallTally.reset();
        logs.start();
        kisLogger.addAppender(logs);
        limiterLogger.addAppender(logs);

        KisTokenManager tokenManager = new KisTokenManager(restTemplate, new ObjectMapper(), null);
        ReflectionTestUtils.setField(tokenManager, "appKey", "k");
        ReflectionTestUtils.setField(tokenManager, "appSecret", "s");
        ReflectionTestUtils.setField(tokenManager, "baseUrl", "https://mock.kis");
        ReflectionTestUtils.setField(tokenManager, "accessToken", "cached-token");
        ReflectionTestUtils.setField(tokenManager, "tokenExpireTime", LocalDateTime.now().plusHours(12));

        service = new KoreaInvestmentService(restTemplate, new ObjectMapper(), new KisApiRateLimiter(), tokenManager);
        ReflectionTestUtils.setField(service, "appKey", "k");
        ReflectionTestUtils.setField(service, "appSecret", "s");
        ReflectionTestUtils.setField(service, "accountPrefix", "12345678");
        ReflectionTestUtils.setField(service, "accountSuffix", "01");
        ReflectionTestUtils.setField(service, "baseUrl", "https://mock.kis");
    }

    @AfterEach
    void tearDown() {
        kisLogger.detachAppender(logs);
        limiterLogger.detachAppender(logs);
    }

    private static HttpServerErrorException serverError(String body) {
        return HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                null, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /** n번째(1-base)까지는 주어진 500 바디, 그 뒤는 정상 응답. */
    private void stubFailuresThenOk(int n, String body) {
        AtomicInteger calls = new AtomicInteger();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(inv -> {
                    if (calls.incrementAndGet() <= n) throw serverError(body);
                    return ResponseEntity.ok(OK);
                });
    }

    private List<ILoggingEvent> errorsFrom(Logger logger) {
        return logs.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR && e.getLoggerName().equals(logger.getName()))
                .toList();
    }

    @Test
    @DisplayName("재현(11/9): 초당 건수 초과 한 번 뒤 재시도로 받아오면 ERROR 0줄 — 시도는 WARN")
    void recoveredRateLimitLeavesNoError() {
        stubFailuresThenOk(1, EGW00215);

        assertThat(service.getBalance()).isNotNull();

        assertThat(errorsFrom(kisLogger)).as("시도마다 ERROR 였다(수정 전 1줄)").isEmpty();
        assertThat(errorsFrom(limiterLogger)).isEmpty();
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("EGW00215");
        });
    }

    @Test
    @DisplayName("재시도가 바닥나면 rateLimiter 의 '최대 재시도 초과' ERROR 1줄은 그대로 — 시도별 ERROR 4줄은 없다")
    void exhaustedRateLimitKeepsOneError() {
        stubFailuresThenOk(99, EGW00215);

        assertThat(service.getBalance()).isNull();

        assertThat(errorsFrom(kisLogger)).isEmpty();
        assertThat(errorsFrom(limiterLogger)).hasSize(1)
                .first().satisfies(e -> assertThat(e.getFormattedMessage()).contains("최대 재시도 초과"));
    }

    @Test
    @DisplayName("초당 건수 초과가 아닌 500(만료 토큰 EGW00123)은 종전대로 ERROR")
    void otherServerErrorStaysError() {
        stubFailuresThenOk(99, EGW00123);

        // 초당 건수 초과가 아니면 rateLimiter 는 재시도하지 않고 예외를 그대로 올린다(종전 동작)
        org.assertj.core.api.Assertions.assertThatThrownBy(service::getBalance).isInstanceOf(RuntimeException.class);

        assertThat(errorsFrom(kisLogger)).isNotEmpty()
                .first().satisfies(e -> assertThat(e.getFormattedMessage()).contains("EGW00123"));
    }
}
