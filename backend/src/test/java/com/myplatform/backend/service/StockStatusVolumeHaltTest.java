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
    @Mock private com.myplatform.backend.repository.StockPriceRepository stockPriceRepository;

    private StockStatusService service;

    @BeforeEach
    void setUp() {
        service = new StockStatusService(restTemplate, telegramService, sectorStockConfig,
                priceHistoryRepository, stockPriceRepository);
    }

    /** 굳은 저장 종가와 그 봉 수 — {@code findFrozenClosesForCodes} 반환 모양. */
    private void stubFrozenClose(String code, String close, long bars) {
        when(priceHistoryRepository.findFrozenClosesForCodes(org.mockito.ArgumentMatchers.anyList(), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(new Object[]{code, new java.math.BigDecimal(close), bars}));
    }

    private void stubCurrentPrice(String code, String price) {
        com.myplatform.backend.entity.StockPrice row =
                org.mockito.Mockito.mock(com.myplatform.backend.entity.StockPrice.class);
        when(row.getCurrentPrice()).thenReturn(price == null ? null : new java.math.BigDecimal(price));
        when(stockPriceRepository.findTopByStockCodeOrderByFetchedAtDesc(code))
                .thenReturn(java.util.Optional.of(row));
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

    // ==================== 액면변경 의심 (2026-09-11 조일알미늄) ====================

    @Test
    @DisplayName("018470 실측: 정지 6봉 뒤 973 → 4,865(5.00배) = 액면변경 의심으로 표시")
    void detectsCorporateActionOnHaltedStock() {
        stubHalted(List.of("018470"));
        stubFrozenClose("018470", "973.00", 6);
        stubCurrentPrice("018470", "4865");

        service.refreshVolumeHalts();

        assertThat(service.getSuspectedCorporateActions())
                .containsKey("018470")
                .extractingByKey("018470").asString().contains("액면병합").contains("비교 불가");
    }

    @Test
    @DisplayName("액면변경 표시는 게이트가 아니다 — 정지 사유를 덮어쓰지 않는다")
    void corporateActionDoesNotReplaceHaltReason() {
        stubHalted(List.of("018470"));
        stubFrozenClose("018470", "973.00", 6);
        stubCurrentPrice("018470", "4865");

        service.refreshVolumeHalts();

        assertThat(service.getSuspendedStocks()).containsKey("018470");
        assertThat(service.getSuspendedStocks().get("018470"))
                .as("정지 사유는 그대로 — 액면변경은 별개 신호다").contains("거래량 0");
    }

    @Test
    @DisplayName("정수배가 아니면 표시하지 않는다 — 그냥 정지 종목")
    void nonIntegerRatioIsNotFlagged() {
        stubHalted(List.of("018470"));
        stubFrozenClose("018470", "973.00", 6);
        stubCurrentPrice("018470", "3600");

        service.refreshVolumeHalts();

        assertThat(service.getSuspectedCorporateActions()).isEmpty();
    }

    @Test
    @DisplayName("현재가를 못 구하면 표시하지 않는다 — 결측을 근거로 단정하지 않는다(§4c)")
    void missingCurrentPriceIsNotFlagged() {
        stubHalted(List.of("018470"));
        stubFrozenClose("018470", "973.00", 6);
        when(stockPriceRepository.findTopByStockCodeOrderByFetchedAtDesc("018470"))
                .thenReturn(java.util.Optional.empty());

        service.refreshVolumeHalts();

        assertThat(service.getSuspectedCorporateActions()).isEmpty();
    }

    @Test
    @DisplayName("액면변경 조회 실패는 이전 표시 유지 — 빈 결과로 '없음' 위장 금지")
    void corporateActionQueryFailureKeepsPrevious() {
        stubHalted(List.of("018470"));
        stubFrozenClose("018470", "973.00", 6);
        stubCurrentPrice("018470", "4865");
        service.refreshVolumeHalts();
        assertThat(service.getSuspectedCorporateActions()).containsKey("018470");

        when(priceHistoryRepository.findFrozenClosesForCodes(org.mockito.ArgumentMatchers.anyList(), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("DB down"));
        service.refreshVolumeHalts();

        assertThat(service.getSuspectedCorporateActions()).containsKey("018470");
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
