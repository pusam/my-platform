package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.service.KoreaInvestmentService.BalanceInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * KIS 잔고 조회 단일 비행 — {@code RealTradeService.getBalanceInfo(false)}.
 *
 * <p>고치려는 결함(2026-09-02 prod 실측): 공시 모니터(5분)·급락 감시(2분)·워머가 같은 초(스케줄러 tick)에
 * 각자 KIS 잔고를 호출해 서로를 EGW00215(초당 거래건수 초과)로 실패시켰다 — 하루 24건, 전부 tick 초.
 * 30초 캐시는 tick 마다 이미 만료돼 있어 막지 못했다. 동시 호출자는 한 번만 KIS 에 가고 나머지는
 * 방금 갱신된 캐시를 써야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RealTradeBalanceSingleFlightTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private KoreaInvestmentService kisService;
    @InjectMocks private RealTradeService service;

    private static BalanceInfo emptyBalance() {
        return BalanceInfo.builder()
                .availableBalance(BigDecimal.ONE)
                .depositBalance(BigDecimal.ONE)
                .totalEvaluation(BigDecimal.ZERO)
                .totalProfitLoss(BigDecimal.ZERO)
                .holdings(new ArrayList<>())
                .build();
    }

    @Test
    @DisplayName("같은 순간 3호출 → KIS 잔고 API 는 1회만 (나머지는 갱신된 캐시)")
    void concurrentCallersShareOneKisCall() throws Exception {
        AtomicInteger kisCalls = new AtomicInteger();
        when(kisService.getBalance()).thenAnswer(inv -> {
            kisCalls.incrementAndGet();
            Thread.sleep(300);   // 실제 KIS 왕복 동안 다른 호출자가 겹치도록
            return MAPPER.readTree("{\"output1\":[],\"output2\":[]}");
        });
        when(kisService.parseBalance(any())).thenReturn(emptyBalance());

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<?>>> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return service.getPortfolio();
            }));
        }
        go.countDown();
        for (Future<List<?>> f : results) {
            assertThat(f.get(5, TimeUnit.SECONDS)).isNotNull();   // 셋 다 정상 결과(빈 보유)
        }
        pool.shutdown();

        assertThat(kisCalls.get()).as("동시 호출자가 각자 KIS 를 때리면 안 된다").isEqualTo(1);
    }

    @Test
    @DisplayName("캐시가 신선하면 KIS 를 부르지 않는다 — 두 번째 순차 호출은 0회 추가")
    void freshCacheSkipsKis() throws Exception {
        AtomicInteger kisCalls = new AtomicInteger();
        when(kisService.getBalance()).thenAnswer(inv -> {
            kisCalls.incrementAndGet();
            return MAPPER.readTree("{}");
        });
        when(kisService.parseBalance(any())).thenReturn(emptyBalance());

        service.getPortfolio();
        service.getPortfolio();

        assertThat(kisCalls.get()).isEqualTo(1);
    }
}
