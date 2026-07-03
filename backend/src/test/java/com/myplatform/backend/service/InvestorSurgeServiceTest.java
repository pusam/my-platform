package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myplatform.backend.repository.AlertHistoryRepository;
import com.myplatform.backend.repository.InvestorIntradaySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 수급 스냅샷 수집 → Redis L2(all_0) 즉시 갱신 회귀 테스트.
 *
 * 배경(2026-07-03): 수집 cron(:x2)은 DB 만 갱신하고 Redis all_0 은 워머
 * warmInvestorSurge(fixedDelay 10분)가 따로 채웠다. fixedDelay 는 서버 시작
 * 시각에 따라 위상이 표류하므로, 워머가 수집 직전(:x1)에 돌던 구간에는
 * 봇/프론트가 한 사이클 전 스냅샷을 최대 ~10분 더 보게 됨 → 봇 신선도
 * 가드(15분)가 16분 stale 로 매수 보류 연발. 수집 성공 직후 서비스가 직접
 * refreshAllSurgeStocksCache() 를 호출해 위상 문제를 원천 제거한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvestorSurgeServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private InvestorIntradaySnapshotRepository snapshotRepository;
    @Mock private AlertHistoryRepository alertHistoryRepository;
    @Mock private KoreaInvestmentService koreaInvestmentService;
    @Mock private TelegramNotificationService telegramService;
    @Mock private StockPriceService stockPriceService;
    @Mock private RedisCacheService redisCacheService;
    @Mock private SchedulerLockService schedulerLockService;
    @Mock private ObjectProvider<SignalOutcomeService> signalOutcomeProvider;

    private InvestorSurgeService service;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 2026-07-03(금) 10:22 KST — 수집 cron 발화 시각(:x2), 기관 데이터 제공 시간(10:00 이후)
        Clock fixedClock = Clock.fixed(
                LocalDateTime.of(2026, 7, 3, 10, 22).atZone(KST).toInstant(), KST);
        service = new InvestorSurgeService(
                snapshotRepository, alertHistoryRepository, koreaInvestmentService,
                telegramService, stockPriceService, redisCacheService,
                schedulerLockService, signalOutcomeProvider, fixedClock);

        when(schedulerLockService.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(telegramService.isEnabled()).thenReturn(false);          // 알림 경로 무관
        when(signalOutcomeProvider.getIfAvailable()).thenReturn(null); // outcome 기록 무관
        when(snapshotRepository.findPreviousSnapshotTime(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(snapshotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        // refreshAllSurgeStocksCache → getSurgeStocks 경로: 최신 시각 없음 → 빈 리스트로 compute
        when(snapshotRepository.findLatestSnapshotTime(any(), any())).thenReturn(Optional.empty());
    }

    /** KIS 외국인/기관 순매수 순위 정상 응답(1종목) 스텁 */
    private ObjectNode kisRankingResponse() {
        ObjectNode resp = om.createObjectNode();
        resp.put("rt_cd", "0");
        ArrayNode output = resp.putArray("output");
        ObjectNode item = output.addObject();
        item.put("mksc_shrn_iscd", "005930");
        item.put("hts_kor_isnm", "삼성전자");
        item.put("frgn_ntby_tr_pbmn", "10000");  // 100억 (백만원 단위)
        item.put("orgn_ntby_tr_pbmn", "10000");
        item.put("stck_prpr", "70000");
        item.put("prdy_ctrt", "1.5");
        return resp;
    }

    @Test
    @DisplayName("수집 성공 직후 Redis all_0 캐시를 즉시 갱신한다 — 워머 fixedDelay 위상과 무관하게 봇/프론트가 최신 스냅샷을 본다")
    void collectIntradaySnapshot_refreshesRedisCacheImmediately_afterSuccessfulCollection() {
        when(koreaInvestmentService.getForeignInstitutionTotal(anyString(), eq(true), eq(true)))
                .thenReturn(kisRankingResponse());

        service.collectIntradaySnapshot();

        // 수집 직후 all_0 키가 fresh 데이터로 즉시 갱신되어야 한다 (워머 대기 금지)
        verify(redisCacheService).put(
                eq(MarketCacheWarmerService.getCacheInvestorSurge()), eq("all_0"),
                any(), any(Duration.class));
    }

    @Test
    @DisplayName("수집 0건(KIS 응답 null)이면 캐시를 덮어쓰지 않는다 — 기존 데이터 보존 규약")
    void collectIntradaySnapshot_doesNotTouchCache_whenNothingCollected() {
        when(koreaInvestmentService.getForeignInstitutionTotal(anyString(), eq(true), eq(true)))
                .thenReturn(null);

        service.collectIntradaySnapshot();

        verify(redisCacheService, never()).put(any(), any(), any(), any(Duration.class));
    }
}
