package com.myplatform.backend.service;

import com.myplatform.backend.entity.AlertHistory;
import com.myplatform.backend.entity.StockCatalyst;
import com.myplatform.backend.entity.StockCatalyst.CatalystType;
import com.myplatform.backend.entity.StockCatalyst.Direction;
import com.myplatform.backend.repository.AlertHistoryRepository;
import com.myplatform.backend.repository.BotTradingPositionRepository;
import com.myplatform.backend.repository.StockWatchlistRepository;
import com.myplatform.backend.service.CatalystRiskAlertService.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관심/보유 악재 조기경보 — 발송 판정(decide)·중복 방지 키·메시지 순수 함수 + 라우팅.
 * 채널 규약: 관심 = 시그널 / 보유 = 시그널+리스크 병행(긴급).
 *
 * <p><b>dedup(P2-CAT4)</b>: 종목×일자 1회 = catalyst_alert_dedup UNIQUE(alert_key) <b>조건부 INSERT 선점</b>
 * — 발송 전에 선점하고 승자만 발송. 경합 패자(DataIntegrityViolationException)는 중복 억제,
 * DB 블립은 발송 억제(스팸 방지 우선), 전부 발송 실패 시에만 선점 반납(재시도 허용).
 */
@ExtendWith(MockitoExtension.class)
class CatalystRiskAlertServiceTest {

    @Mock private StockWatchlistRepository watchlistRepository;
    @Mock private BotTradingPositionRepository botPositionRepository;
    @Mock private AlertHistoryRepository alertHistoryRepository;
    @Mock private CatalystAlertDedupService dedupService;
    @Mock private ObjectProvider<TelegramNotificationService> telegramProvider;
    @Mock private ObjectProvider<RealTradeService> realTradeProvider;
    @Mock private ObjectProvider<KoreaInvestmentService> kisProvider;
    @Mock private TelegramNotificationService telegram;

    // ⚠ @InjectMocks 금지 — ObjectProvider<X> 3개가 타입 소거로 같은 raw 타입이라 생성자 주입이
    // 어긋난다(스텁 미사용/오배선). 수동 생성으로 파라미터 순서를 명시.
    private CatalystRiskAlertService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new CatalystRiskAlertService(watchlistRepository, botPositionRepository,
                alertHistoryRepository, dedupService, telegramProvider, realTradeProvider, kisProvider);
    }

    private StockCatalyst negative(String code) {
        return StockCatalyst.builder()
                .stockCode(code).stockName("테스트종목")
                .catalystDate(LocalDate.of(2026, 7, 7))
                .catalystType(CatalystType.LITIGATION)
                .direction(Direction.NEGATIVE)
                .headline("대규모 소송 피소")
                .summary("손해배상 청구")
                .build();
    }

    private void stubWatchedOnly(String code) {
        when(watchlistRepository.existsByStockCodeAndIsActiveTrue(code)).thenReturn(true);
        when(botPositionRepository.existsByStockCode(code)).thenReturn(false);
        when(kisProvider.getIfAvailable()).thenReturn(null);   // 실잔고 미설정 → 빈 집합
    }

    // ================================================================
    // decide — 순수 함수
    // ================================================================

    @Test
    @DisplayName("decide — 보유=시그널+리스크 / 관심만=시그널 / 비대상·기발송·비악재=NONE")
    void decide_matrix() {
        assertThat(CatalystRiskAlertService.decide(Direction.NEGATIVE, false, true, false))
                .isEqualTo(Action.SIGNAL_AND_RISK);                                     // 보유(관심 여부 무관)
        assertThat(CatalystRiskAlertService.decide(Direction.NEGATIVE, true, true, false))
                .isEqualTo(Action.SIGNAL_AND_RISK);
        assertThat(CatalystRiskAlertService.decide(Direction.NEGATIVE, true, false, false))
                .isEqualTo(Action.SIGNAL);                                              // 관심만
        assertThat(CatalystRiskAlertService.decide(Direction.NEGATIVE, false, false, false))
                .isEqualTo(Action.NONE);                                                // 비대상
        assertThat(CatalystRiskAlertService.decide(Direction.NEGATIVE, true, true, true))
                .isEqualTo(Action.NONE);                                                // 당일 기발송(멱등)
        assertThat(CatalystRiskAlertService.decide(Direction.POSITIVE, true, true, false))
                .isEqualTo(Action.NONE);                                                // 악재 아님
        assertThat(CatalystRiskAlertService.decide(Direction.NEUTRAL, true, true, false))
                .isEqualTo(Action.NONE);
    }

    @Test
    @DisplayName("alertKey — 종목×일자 1회 키(50자 내)")
    void alertKey_perStockPerDay() {
        String key = CatalystRiskAlertService.alertKey("005930", LocalDate.of(2026, 7, 7));
        assertThat(key).isEqualTo("CATNEG_005930_2026-07-07");
        assertThat(key.length()).isLessThanOrEqualTo(50);   // catalyst_alert_dedup.alert_key 컬럼 제한
    }

    @Test
    @DisplayName("메시지 — 보유='보유 종목 악재 경보'/관심='관심 종목', 종목·유형·요약·뉴스 링크 포함, 링크 null 생략")
    void buildTargetAlertMessage_format() {
        String held = CatalystRiskAlertService.buildTargetAlertMessage(negative("005930"), "http://news/1", true);
        assertThat(held).contains("보유 종목 악재 경보");
        assertThat(held).contains("<b>테스트종목</b> (005930)");
        assertThat(held).contains("유형: <b>소송</b> (악재)");
        assertThat(held).contains("손해배상 청구");
        assertThat(held).contains("대규모 소송 피소");
        assertThat(held).contains("http://news/1");
        assertThat(held).contains("산식 미반영");

        String watched = CatalystRiskAlertService.buildTargetAlertMessage(negative("005930"), null, false);
        assertThat(watched).contains("관심 종목 악재 경보");
        assertThat(watched).doesNotContain("🔗");   // 링크 없으면 줄 생략(§4c)
    }

    // ================================================================
    // onCatalystSaved — 라우팅 + 선점 dedup
    // ================================================================

    @Test
    @DisplayName("관심 종목 악재 → 선점 성공 후 시그널 채널만 발송 + AlertHistory 기록(관측용)")
    void watched_signalOnly() {
        stubWatchedOnly("005930");
        when(telegramProvider.getIfAvailable()).thenReturn(telegram);

        boolean handled = service.onCatalystSaved(negative("005930"), "http://news/1");

        assertThat(handled).isTrue();
        verify(dedupService).claimIsolated(eq("CATNEG_005930_2026-07-07"), eq("005930"),
                eq(LocalDate.of(2026, 7, 7)));
        verify(telegram).sendSignal(contains("관심 종목 악재 경보"));
        verify(telegram, never()).sendRisk(anyString());
        verify(alertHistoryRepository).save(any(AlertHistory.class));
        verify(dedupService, never()).releaseIsolated(anyString());
    }

    @Test
    @DisplayName("봇 포지션 보유 악재 → 시그널 + 리스크 채널 병행(긴급도↑)")
    void held_signalAndRisk() {
        when(watchlistRepository.existsByStockCodeAndIsActiveTrue("005930")).thenReturn(false);
        when(botPositionRepository.existsByStockCode("005930")).thenReturn(true);
        when(telegramProvider.getIfAvailable()).thenReturn(telegram);

        boolean handled = service.onCatalystSaved(negative("005930"), null);

        assertThat(handled).isTrue();
        verify(telegram).sendSignal(contains("보유 종목 악재 경보"));
        verify(telegram).sendRisk(contains("보유 종목 악재 경보"));
    }

    @Test
    @DisplayName("당일 기발송/경합 패자(선점 UNIQUE 위반) → 재발송 없음, handled=true(일반 알림도 억제)")
    void duplicateOrRaceLoser_suppressed() {
        stubWatchedOnly("005930");
        when(telegramProvider.getIfAvailable()).thenReturn(telegram);
        doThrow(new DataIntegrityViolationException("uq_cad_alert_key"))
                .when(dedupService).claimIsolated(anyString(), anyString(), any());

        boolean handled = service.onCatalystSaved(negative("005930"), null);

        assertThat(handled).isTrue();
        verify(telegram, never()).sendSignal(anyString());
        verify(telegram, never()).sendRisk(anyString());
        verify(alertHistoryRepository, never()).save(any());
        verify(dedupService, never()).releaseIsolated(anyString());   // 패자는 남의 선점을 지우지 않는다
    }

    @Test
    @DisplayName("선점 DB 블립(비 UNIQUE 예외) → 발송 억제(스팸 방지 우선), handled=true")
    void claimDbError_suppressed() {
        stubWatchedOnly("005930");
        when(telegramProvider.getIfAvailable()).thenReturn(telegram);
        doThrow(new RuntimeException("connection refused"))
                .when(dedupService).claimIsolated(anyString(), anyString(), any());

        boolean handled = service.onCatalystSaved(negative("005930"), null);

        assertThat(handled).isTrue();
        verify(telegram, never()).sendSignal(anyString());
        verify(dedupService, never()).releaseIsolated(anyString());
    }

    @Test
    @DisplayName("발송 전부 실패 → 선점 반납(다음 분류 기회 재시도 허용)")
    void sendTotalFailure_releasesClaim() {
        stubWatchedOnly("005930");
        when(telegramProvider.getIfAvailable()).thenReturn(telegram);
        doThrow(new RuntimeException("telegram down")).when(telegram).sendSignal(anyString());

        boolean handled = service.onCatalystSaved(negative("005930"), null);

        assertThat(handled).isTrue();
        verify(dedupService).releaseIsolated("CATNEG_005930_2026-07-07");
        verify(alertHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("부분 실패(시그널 성공·리스크 실패) → 선점 유지(재시도 시 시그널 채널 중복 스팸 방지)")
    void sendPartialFailure_keepsClaim() {
        when(watchlistRepository.existsByStockCodeAndIsActiveTrue("005930")).thenReturn(false);
        when(botPositionRepository.existsByStockCode("005930")).thenReturn(true);   // 보유 → 시그널+리스크
        when(telegramProvider.getIfAvailable()).thenReturn(telegram);
        doThrow(new RuntimeException("risk channel down")).when(telegram).sendRisk(anyString());

        boolean handled = service.onCatalystSaved(negative("005930"), null);

        assertThat(handled).isTrue();
        verify(telegram).sendSignal(anyString());
        verify(dedupService, never()).releaseIsolated(anyString());
    }

    @Test
    @DisplayName("동시 경쟁(같은 종목·일자 2스레드) → 선점 승자 1명만 발송, 정확히 1회")
    void concurrentFirstClassification_exactlyOneAlert() throws Exception {
        stubWatchedOnly("005930");
        when(telegramProvider.getIfAvailable()).thenReturn(telegram);

        // DB UNIQUE(alert_key) 의 원자성 모사 — 최초 1회만 성공, 이후 DataIntegrityViolationException.
        AtomicBoolean claimed = new AtomicBoolean(false);
        doAnswer(inv -> {
            if (!claimed.compareAndSet(false, true)) {
                throw new DataIntegrityViolationException("uq_cad_alert_key");
            }
            return null;
        }).when(dedupService).claimIsolated(anyString(), anyString(), any());

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    assertThat(service.onCatalystSaved(negative("005930"), null)).isTrue();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        verify(telegram, times(1)).sendSignal(anyString());   // 정확히 1회
        verify(dedupService, never()).releaseIsolated(anyString());
    }

    @Test
    @DisplayName("비대상(관심/보유 아님) → false, 선점 시도 없음 (일반 악재 알림이 기존대로 담당)")
    void nonTarget_returnsFalse() {
        when(watchlistRepository.existsByStockCodeAndIsActiveTrue("005930")).thenReturn(false);
        when(botPositionRepository.existsByStockCode("005930")).thenReturn(false);
        when(kisProvider.getIfAvailable()).thenReturn(null);

        assertThat(service.onCatalystSaved(negative("005930"), null)).isFalse();
        verify(dedupService, never()).claimIsolated(anyString(), anyString(), any());
        verify(alertHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("호재/중립/null 은 즉시 false — 훅 무반응(분류·기존 알림 흐름 무영향)")
    void nonNegative_noop() {
        StockCatalyst positive = StockCatalyst.builder()
                .stockCode("005930").catalystDate(LocalDate.now())
                .catalystType(CatalystType.ORDER_WIN).direction(Direction.POSITIVE).build();

        assertThat(service.onCatalystSaved(positive, null)).isFalse();
        assertThat(service.onCatalystSaved(null, null)).isFalse();
        org.mockito.Mockito.verifyNoInteractions(watchlistRepository, botPositionRepository,
                alertHistoryRepository, dedupService, telegramProvider);
    }

    @Test
    @DisplayName("텔레그램 미설정 → 선점하지 않고 handled=true (선점만 하면 당일 알림 통째 유실)")
    void telegramUnavailable_noClaim() {
        stubWatchedOnly("005930");
        when(telegramProvider.getIfAvailable()).thenReturn(null);

        assertThat(service.onCatalystSaved(negative("005930"), null)).isTrue();
        verify(dedupService, never()).claimIsolated(anyString(), anyString(), any());
        verify(alertHistoryRepository, never()).save(any());   // 발송 안 했으면 이력도 안 남김
    }
}
