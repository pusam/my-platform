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

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관심/보유 악재 조기경보 — 발송 판정(decide)·중복 방지 키·메시지 순수 함수 + 라우팅.
 * 채널 규약: 관심 = 시그널 / 보유 = 시그널+리스크 병행(긴급). 종목×일자 1회 멱등(AlertHistory).
 */
@ExtendWith(MockitoExtension.class)
class CatalystRiskAlertServiceTest {

    @Mock private StockWatchlistRepository watchlistRepository;
    @Mock private BotTradingPositionRepository botPositionRepository;
    @Mock private AlertHistoryRepository alertHistoryRepository;
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
                alertHistoryRepository, telegramProvider, realTradeProvider, kisProvider);
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
        assertThat(key.length()).isLessThanOrEqualTo(50);   // alert_history.alertKey 컬럼 제한
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
    // onCatalystSaved — 라우팅 + 멱등
    // ================================================================

    @Test
    @DisplayName("관심 종목 악재 → 시그널 채널만 발송 + AlertHistory 기록(멱등 키)")
    void watched_signalOnly() {
        when(watchlistRepository.existsByStockCodeAndIsActiveTrue("005930")).thenReturn(true);
        when(botPositionRepository.existsByStockCode("005930")).thenReturn(false);
        when(kisProvider.getIfAvailable()).thenReturn(null);   // 실잔고 미설정 → 빈 집합
        when(alertHistoryRepository.findLatestByAlertKey(anyString())).thenReturn(Optional.empty());
        when(telegramProvider.getIfAvailable()).thenReturn(telegram);

        boolean handled = service.onCatalystSaved(negative("005930"), "http://news/1");

        assertThat(handled).isTrue();
        verify(telegram).sendSignal(contains("관심 종목 악재 경보"));
        verify(telegram, never()).sendRisk(anyString());
        verify(alertHistoryRepository).save(any(AlertHistory.class));
    }

    @Test
    @DisplayName("봇 포지션 보유 악재 → 시그널 + 리스크 채널 병행(긴급도↑)")
    void held_signalAndRisk() {
        when(watchlistRepository.existsByStockCodeAndIsActiveTrue("005930")).thenReturn(false);
        when(botPositionRepository.existsByStockCode("005930")).thenReturn(true);
        when(alertHistoryRepository.findLatestByAlertKey(anyString())).thenReturn(Optional.empty());
        when(telegramProvider.getIfAvailable()).thenReturn(telegram);

        boolean handled = service.onCatalystSaved(negative("005930"), null);

        assertThat(handled).isTrue();
        verify(telegram).sendSignal(contains("보유 종목 악재 경보"));
        verify(telegram).sendRisk(contains("보유 종목 악재 경보"));
    }

    @Test
    @DisplayName("당일 기발송(AlertHistory 존재) → 재발송 없음, 그래도 handled=true(일반 알림도 억제)")
    void duplicateSameDay_suppressed() {
        when(watchlistRepository.existsByStockCodeAndIsActiveTrue("005930")).thenReturn(true);
        when(botPositionRepository.existsByStockCode("005930")).thenReturn(false);
        when(kisProvider.getIfAvailable()).thenReturn(null);
        when(alertHistoryRepository.findLatestByAlertKey("CATNEG_005930_2026-07-07"))
                .thenReturn(Optional.of(new AlertHistory()));

        boolean handled = service.onCatalystSaved(negative("005930"), null);

        assertThat(handled).isTrue();
        verify(telegramProvider, never()).getIfAvailable();
        verify(alertHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("비대상(관심/보유 아님) → false (일반 악재 알림이 기존대로 담당)")
    void nonTarget_returnsFalse() {
        when(watchlistRepository.existsByStockCodeAndIsActiveTrue("005930")).thenReturn(false);
        when(botPositionRepository.existsByStockCode("005930")).thenReturn(false);
        when(kisProvider.getIfAvailable()).thenReturn(null);

        assertThat(service.onCatalystSaved(negative("005930"), null)).isFalse();
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
                alertHistoryRepository, telegramProvider);
    }

    @Test
    @DisplayName("텔레그램 미설정 → 발송 없이 handled=true (분류 저장에 영향 없음)")
    void telegramUnavailable_stillHandled() {
        when(watchlistRepository.existsByStockCodeAndIsActiveTrue("005930")).thenReturn(true);
        when(botPositionRepository.existsByStockCode("005930")).thenReturn(false);
        when(kisProvider.getIfAvailable()).thenReturn(null);
        when(alertHistoryRepository.findLatestByAlertKey(anyString())).thenReturn(Optional.empty());
        when(telegramProvider.getIfAvailable()).thenReturn(null);

        assertThat(service.onCatalystSaved(negative("005930"), null)).isTrue();
        verify(alertHistoryRepository, never()).save(any());   // 발송 안 했으면 이력도 안 남김(다음 기회 재시도)
    }
}
