package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.repository.StockPriceRepository;
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
 * 거래정지 게이트가 "채워졌는가" — 부팅 직후 소비자가 기다릴 신호(2026-09-30).
 *
 * <p>실사고(2026-09-29 22:53 재시작): 정지 목록은 메모리라 재시작하면 비고, 채우는 {@code syncOnStartup} 은
 * 비동기 풀(코어 4)에서 순서를 기다려 기동 60초 뒤에야 돌았다. AI 전략 워밍은 30초 뒤 시작해 22:54:12 에
 * 마법의 공식을 돌렸고 — 게이트가 빈 채라 이오플로우(정지)·삼부토건(정지)이 스윙 1·3위(100점)로 저장됐다.
 * 게이트는 22:54:25 에 채워졌다(정지 13건). 그 스냅샷은 다음 로테이션(이튿날 10:00)까지 '오늘' 탭 장전 신호에 뜬다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockStatusHaltGateLoadedTest {

    @Mock private RestTemplate restTemplate;
    @Mock private TelegramNotificationService telegramService;
    @Mock private SectorStockConfig sectorStockConfig;
    @Mock private StockPriceHistoryRepository priceHistoryRepository;
    @Mock private StockPriceRepository stockPriceRepository;

    private StockStatusService service;

    @BeforeEach
    void setUp() {
        service = new StockStatusService(restTemplate, telegramService, sectorStockConfig,
                priceHistoryRepository, stockPriceRepository);
    }

    @Test
    @DisplayName("기동 직후엔 채워지지 않았다 — 이때 isActive 는 모두 통과(fail-open)라 게이트가 없는 것과 같다")
    void notLoadedBeforeFirstDetection() {
        assertThat(service.isHaltGateLoaded()).isFalse();
        assertThat(service.isActive("294090")).isTrue();
    }

    @Test
    @DisplayName("정지 감지가 한 번 끝나면 채워진다 — 정지 0건이어도 '확인했다'는 뜻")
    void loadedAfterSuccessfulDetection() {
        when(priceHistoryRepository.findCodesWithAllZeroVolumeSince(any(LocalDate.class), anyLong()))
                .thenReturn(List.of("294090", "001470"));

        service.refreshVolumeHalts();

        assertThat(service.isHaltGateLoaded()).isTrue();
        assertThat(service.isActive("294090")).isFalse();
    }

    @Test
    @DisplayName("정지 0건도 채워진 것이다")
    void loadedEvenWhenNothingIsHalted() {
        when(priceHistoryRepository.findCodesWithAllZeroVolumeSince(any(LocalDate.class), anyLong()))
                .thenReturn(List.of());

        service.refreshVolumeHalts();

        assertThat(service.isHaltGateLoaded()).isTrue();
    }

    @Test
    @DisplayName("감지 조회가 실패하면 채워지지 않은 채로 남는다 — 실패를 '확인 완료'로 위장하지 않는다")
    void failedDetectionDoesNotCountAsLoaded() {
        when(priceHistoryRepository.findCodesWithAllZeroVolumeSince(any(LocalDate.class), anyLong()))
                .thenThrow(new RuntimeException("DB down"));

        service.refreshVolumeHalts();

        assertThat(service.isHaltGateLoaded()).isFalse();
    }
}
