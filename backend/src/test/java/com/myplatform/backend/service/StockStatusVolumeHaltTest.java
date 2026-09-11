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

    /** 마지막 봉이 거래량 0 인 종목 + 정지 증거 봉 수 — 액면변경 판정 모집단. */
    private void stubLastBarZeroVolume(String code, String close, long zeroBars) {
        when(priceHistoryRepository.findCodesWhoseLatestBarIsZeroVolume())
                .thenReturn(List.<Object[]>of(new Object[]{
                        code, LocalDate.of(2026, 8, 28), new java.math.BigDecimal(close)}));
        when(priceHistoryRepository.countZeroVolumeBars(
                org.mockito.ArgumentMatchers.anyList(), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(new Object[]{code, zeroBars}));
    }

    /** @param ageDays 현재가를 받아온 지 며칠 됐는지(0=오늘). */
    private void stubCurrentPrice(String code, String price, long ageDays) {
        com.myplatform.backend.entity.StockPrice row =
                org.mockito.Mockito.mock(com.myplatform.backend.entity.StockPrice.class);
        when(row.getCurrentPrice()).thenReturn(price == null ? null : new java.math.BigDecimal(price));
        when(row.getFetchedAt())
                .thenReturn(com.myplatform.core.util.DateTimeUtil.kstNow().minusDays(ageDays));
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
    @DisplayName("018470 실측: 봉이 8/28 에서 끊겨 정지 목록 밖인데도 잡는다 — 창으로 자르면 놓친다")
    void detectsCorporateActionEvenWhenBarsAgedOutOfHaltWindow() {
        stubHalted(List.of());                            // 7일 창 기준 정지 목록엔 없다(봉이 끊겨서)
        stubLastBarZeroVolume("018470", "973.00", 6);
        stubCurrentPrice("018470", "4865", 0);

        service.refreshVolumeHalts();

        assertThat(service.getSuspectedCorporateActions())
                .containsKey("018470")
                .extractingByKey("018470").asString().contains("액면병합").contains("비교 불가");
    }

    @Test
    @DisplayName("액면변경 표시는 게이트가 아니다 — 재개한 종목은 isActive 가 그대로 true")
    void corporateActionIsNotAGate() {
        stubHalted(List.of());
        stubLastBarZeroVolume("018470", "973.00", 6);
        stubCurrentPrice("018470", "4865", 0);

        service.refreshVolumeHalts();

        assertThat(service.getSuspectedCorporateActions()).containsKey("018470");
        assertThat(service.isActive("018470"))
                .as("이력만 못 쓰는 것 — 종목은 멀쩡하다").isTrue();
        assertThat(service.getSuspendedStocks()).doesNotContainKey("018470");
    }

    /**
     * 285800 실측 오탐: 봉 3,665 는 수정주가로 이미 보정됐고 캐시 733 이 9/4 에 멈춘 낡은 값이었다.
     * 낡은 값을 현재가로 쓰면 방향이 뒤집힌 "액면분할 1:5" 가 나온다.
     */
    @Test
    @DisplayName("285800 회귀: 현재가가 7일 묵었으면 표시하지 않는다 — 뒤집힌 분할 오탐 방지")
    void stalePriceProducesNoFalsePositive() {
        stubHalted(List.of("285800"));
        stubLastBarZeroVolume("285800", "3665.00", 6);
        stubCurrentPrice("285800", "733", 7);

        service.refreshVolumeHalts();

        assertThat(service.getSuspectedCorporateActions())
                .as("고치기 전엔 '액면분할 1:5' 로 오탐했다").isEmpty();
    }

    @Test
    @DisplayName("정수배가 아니면 표시하지 않는다")
    void nonIntegerRatioIsNotFlagged() {
        stubHalted(List.of());
        stubLastBarZeroVolume("018470", "973.00", 6);
        stubCurrentPrice("018470", "3600", 0);

        service.refreshVolumeHalts();

        assertThat(service.getSuspectedCorporateActions()).isEmpty();
    }

    @Test
    @DisplayName("현재가를 못 구하면 표시하지 않는다 — 결측을 근거로 단정하지 않는다(§4c)")
    void missingCurrentPriceIsNotFlagged() {
        stubHalted(List.of());
        stubLastBarZeroVolume("018470", "973.00", 6);
        when(stockPriceRepository.findTopByStockCodeOrderByFetchedAtDesc("018470"))
                .thenReturn(java.util.Optional.empty());

        service.refreshVolumeHalts();

        assertThat(service.getSuspectedCorporateActions()).isEmpty();
    }

    @Test
    @DisplayName("액면변경 조회 실패는 이전 표시 유지 — 빈 결과로 '없음' 위장 금지")
    void corporateActionQueryFailureKeepsPrevious() {
        stubHalted(List.of());
        stubLastBarZeroVolume("018470", "973.00", 6);
        stubCurrentPrice("018470", "4865", 0);
        service.refreshVolumeHalts();
        assertThat(service.getSuspectedCorporateActions()).containsKey("018470");

        when(priceHistoryRepository.findCodesWhoseLatestBarIsZeroVolume())
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
