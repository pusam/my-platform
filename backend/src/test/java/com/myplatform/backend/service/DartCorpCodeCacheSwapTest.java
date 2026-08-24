package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * corpCode 캐시 갱신 중 읽기가 '빈 맵'을 보지 않는지 ({@link DartService#applyCorpCodeCaches}).
 *
 * <p>배경: 갱신은 매일 06:00(batchScheduler), 읽기 쪽 DartDisclosureMonitorService.checkAfterHoursDawn
 * 은 cron "0 0 0-7 * * TUE-SAT"(cacheScheduler) 라 같은 초에 병렬로 뜬다. 갱신이 살아있는 맵을
 * clear() 후 putAll() 하면 그 사이 읽기는 corpCode 미해결(null) 이 되고, getCorpCodeByName 의
 * 2차 폴백은 하드코딩 20종목뿐이라 그 외 종목은 searchAllDisclosures(corp_cls=Y, KOSPI 한정)
 * 로 흘러 KOSDAQ 공시가 "공시 없음"으로 조용히 위장된다(CLAUDE.md 4c).
 */
class DartCorpCodeCacheSwapTest {

    private static final int ENTRIES = 3000;
    private static final int SWAPS = 300;
    private static final String PROBE = "005930";

    @Test
    @DisplayName("갱신이 도는 동안에도 읽기는 항상 완전한 맵을 본다 (빈 맵 노출 금지)")
    void readerNeverSeesEmptyCacheDuringReload() throws Exception {
        DartService svc = new DartService();
        Map<String, String> stockMap = new HashMap<>();
        Map<String, String> nameMap = new HashMap<>();
        for (int i = 0; i < ENTRIES; i++) {
            stockMap.put(String.format("%06d", i), "corp" + i);
            nameMap.put("종목" + i, "corp" + i);
        }
        // PROBE 는 갱신 전에도 후에도 항상 존재하는 키 — null 이 관측되면 그건 교체 도중의 빈 맵이다.
        stockMap.put(PROBE, "00126380");
        svc.applyCorpCodeCaches(stockMap, nameMap);

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong nullHits = new AtomicLong();
        AtomicLong reads = new AtomicLong();
        CountDownLatch ready = new CountDownLatch(2);

        Runnable reader = () -> {
            ready.countDown();
            while (!stop.get()) {
                if (svc.getCorpCodeByStockCode(PROBE) == null) nullHits.incrementAndGet();
                reads.incrementAndGet();
            }
        };
        Thread r1 = new Thread(reader, "corpcode-reader-1");
        Thread r2 = new Thread(reader, "corpcode-reader-2");
        r1.setDaemon(true);
        r2.setDaemon(true);
        r1.start();
        r2.start();
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

        for (int i = 0; i < SWAPS; i++) {
            svc.applyCorpCodeCaches(stockMap, nameMap);
        }
        stop.set(true);
        r1.join(5000);
        r2.join(5000);

        assertThat(reads.get()).as("읽기 스레드가 실제로 돌았는지").isPositive();
        assertThat(nullHits.get())
                .as("갱신 %d회 동안 관측된 빈 맵(null) 횟수 — 0 이어야 한다", SWAPS)
                .isZero();
    }
}
