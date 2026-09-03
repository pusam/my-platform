package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 잔고 조회 집계({@link KisCallTally#BALANCE})는 <b>호출 단위</b>다 — 시도 1회 = getBalance() 1회, 실패 = 재시도 소진.
 *
 * <p>고치려는 결함(2026-09-03 prod 실측): {@code getBalance()} 는 rateLimiter 가 3회 재시도하는데 집계와 ERROR 로그가
 * 재시도 <i>안쪽</i>({@code getBalanceInternal})에 있어 시도마다 실패가 쌓였다. 14:00 까지 ERROR 7줄 = 실제 end-to-end
 * 실패 1건(10:10 삼연속, 백오프 3회 소진) + 재시도로 복구된 블립 4건. 그런데 관제실 규칙 ⑬은 실패 ≥5 로 울리므로
 * 실제 1건에 경보가 났고, 어제의 "하루 24건"도 같은 부풀림이었다. 복구된 블립은 잔고를 받았으니 실패가 아니다.
 *
 * <p>rateLimiter 는 <b>실물</b>을 쓴다 — 재시도·백오프가 진짜로 돌아야 "재시도 소진만 센다"를 증명한다
 * (소진 케이스는 500+1000+2000ms 백오프로 ~3.5초).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KoreaInvestmentBalanceTallyTest {

    /** KIS 가 초당 한도 초과 때 주는 500 바디 — rateLimiter 는 "초당 거래건수" 문자열로 재시도 대상을 판별한다. */
    private static final String EGW00215 =
            "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00215\",\"msg1\":\"원장에서 허용 가능한 초당 거래건수를 초과하였습니다.\"}";
    private static final String OK =
            "{\"rt_cd\":\"0\",\"output1\":[],\"output2\":[{\"dnca_tot_amt\":\"1000000\"}]}";

    @Mock private RestTemplate restTemplate;

    private KoreaInvestmentService service;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        KisCallTally.reset();
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        KisTokenManager tokenManager = new KisTokenManager(restTemplate, new ObjectMapper());
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

    private static HttpServerErrorException rateLimited() {
        return HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                null, EGW00215.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /** n번째(1-base)까지는 EGW00215, 그 뒤는 정상 응답. */
    private AtomicInteger stubRateLimitedTimes(int n) {
        AtomicInteger calls = new AtomicInteger();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(inv -> {
                    if (calls.incrementAndGet() <= n) throw rateLimited();
                    return ResponseEntity.ok(OK);
                });
        return calls;
    }

    private KisCallTally.Counts counts() {
        return KisCallTally.of(KisCallTally.BALANCE, today);
    }

    @Test
    @DisplayName("한 번 충돌 후 재시도로 받아오면 — 시도 1 / 실패 0 (블립은 실패가 아니다)")
    void recoveredOnRetryIsNotAFailure() {
        AtomicInteger calls = stubRateLimitedTimes(1);

        JsonNode result = service.getBalance();

        assertThat(result).as("재시도로 잔고를 받았다").isNotNull();
        assertThat(calls.get()).as("KIS 왕복은 2회(실패 1 + 성공 1)").isEqualTo(2);
        assertThat(counts()).as("집계는 호출 단위 — 수정 전엔 (2,1)").isEqualTo(new KisCallTally.Counts(1, 0));
    }

    @Test
    @DisplayName("재시도 3회 전부 충돌(소진) — 시도 1 / 실패 1 (수정 전엔 시도 4 / 실패 4)")
    void exhaustedRetriesCountAsOneFailure() {
        AtomicInteger calls = stubRateLimitedTimes(99);

        JsonNode result = service.getBalance();

        assertThat(result).isNull();
        assertThat(calls.get()).as("최초 1 + 재시도 3").isEqualTo(4);
        assertThat(counts()).isEqualTo(new KisCallTally.Counts(1, 1));
    }

    @Test
    @DisplayName("재시도 대상이 아닌 실패(rt_cd≠0 정상 200)는 그대로 시도 1 / 실패 1")
    void nonRetryableFailureStillCounts() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"rt_cd\":\"1\",\"msg1\":\"모의투자 장종료\"}"));

        assertThat(service.getBalance()).isNull();
        assertThat(counts()).isEqualTo(new KisCallTally.Counts(1, 1));
    }

    @Test
    @DisplayName("실계좌 미설정(가상 전용)은 실패가 아니라 부재 — 세지 않는다(§4c)")
    void notConfiguredIsNotCounted() {
        ReflectionTestUtils.setField(service, "accountPrefix", "");

        assertThat(service.getBalance()).isNull();
        assertThat(counts()).isEqualTo(KisCallTally.Counts.ZERO);
    }
}
