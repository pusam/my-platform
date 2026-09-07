package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 거래량 기반 거래정지 감지 — {@code StockStatusService.refreshVolumeHalts()}.
 *
 * <p>고치려는 결함(2026-09-07 실측): 이오플로우(294090)는 거래정지인데 KIS 종목마스터에 남아 있어(상장 유지,
 * is_active=1) {@code isActive} 게이트를 통과했고, 동결가(1,490원·volume=0 봉이 매일 적재)로 만들어진
 * 재무 스냅샷(PER 1.0·ROE 40.5·영업이익률 3,995%)이 마법의공식 #1 로 08:30 텔레그램에 발송됐다.
 * 마스터는 상폐(목록 제거)만 잡는다 — 거래정지는 "최근 봉 전부 거래량 0" 실측으로 잡는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockStatusVolumeHaltTest {

    @Mock private RestTemplate restTemplate;
    @Mock private TelegramNotificationService telegramService;
    @Mock private SectorStockConfig sectorStockConfig;
    @Mock private StockPriceHistoryRepository priceHistoryRepository;

    private StockStatusService service;

    @BeforeEach
    void setUp() {
        service = new StockStatusService(restTemplate, telegramService, sectorStockConfig, priceHistoryRepository);
    }

    private void stubHalted(List<String> codes) {
        when(priceHistoryRepository.findCodesWithAllZeroVolumeSince(any(LocalDate.class), anyLong()))
                .thenReturn(codes);
    }

    @Test
    @DisplayName("volume=0 연속 감지 종목은 isActive=false — 마스터 fail-open(빈 목록)이어도")
    void haltedStockIsInactiveEvenWhenMasterIsEmpty() {
        stubHalted(List.of("294090"));

        service.refreshVolumeHalts();

        assertThat(service.isActive("294090")).as("거래정지 — 수정 전엔 마스터 잔류로 통과").isFalse();
        assertThat(service.isActive("005930")).as("정상 종목은 fail-open 그대로").isTrue();
        assertThat(service.filterActiveStocks(List.of("294090", "005930"))).containsExactly("005930");
        assertThat(service.getSuspendedStocks()).containsKey("294090");
    }

    @Test
    @DisplayName("감지 조회 실패는 이전 목록 유지 — 실패를 '정지 없음'으로 위장하지 않는다(§4c)")
    void queryFailureKeepsPreviousHalts() {
        stubHalted(List.of("294090"));
        service.refreshVolumeHalts();

        when(priceHistoryRepository.findCodesWithAllZeroVolumeSince(any(LocalDate.class), anyLong()))
                .thenThrow(new IllegalStateException("DB down"));
        service.refreshVolumeHalts();

        assertThat(service.isActive("294090")).isFalse();
    }

    @Test
    @DisplayName("거래 재개(다음 감지에서 빠짐)면 다시 active — 영구 블랙리스트가 아니다")
    void resumedStockBecomesActiveAgain() {
        stubHalted(List.of("294090"));
        service.refreshVolumeHalts();

        stubHalted(List.of());
        service.refreshVolumeHalts();

        assertThat(service.isActive("294090")).isTrue();
        assertThat(service.getSuspendedStocks()).doesNotContainKey("294090");
    }
}
