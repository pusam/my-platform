package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.ShortSellingBalance;
import com.myplatform.backend.repository.ShortSellingBalanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 공매도 비율 결측 정직성 회귀 테스트 (AUDIT 2026-07-07 P1-3, §4c).
 *
 * <p>버그: 결측(데이터 전무·종목 미포함·조회 예외)을 전부 {@code BigDecimal.ZERO} 로 반환
 * → 死피드 상태에서 체크리스트가 전 종목 "0.00% 충족"으로 위장. 결측은 {@code null} 로
 * 실측 0% 와 구분하고, 봇 차단 판정(isHighShortSellingStock)은 결측=통과(§4d PriceSanityGuard
 * "결측=UNKNOWN=통과" 선례와 동일 극성)여야 한다.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ShortSellingServiceTest {

    @Mock private ShortSellingBalanceRepository repository;
    @Mock private TelegramNotificationService telegramService;
    @Mock private RestTemplate restTemplate;
    @Mock private ObjectMapper objectMapper;

    private ShortSellingService service;

    @BeforeEach
    void setUp() {
        service = new ShortSellingService(repository, telegramService, restTemplate, objectMapper);
    }

    private ShortSellingBalance balance(String code, String ratio) {
        ShortSellingBalance b = new ShortSellingBalance();
        b.setStockCode(code);
        b.setShortSellingRatio(new BigDecimal(ratio));
        return b;
    }

    @Test
    @DisplayName("데이터 전무(死피드) → null (ZERO 위장 금지)")
    void noDataAtAll_returnsNull() {
        when(repository.findLatestTradeDate()).thenReturn(Optional.empty());
        assertThat(service.getShortSellingRatio("005930")).isNull();
    }

    @Test
    @DisplayName("최신 일자에 종목 미포함 → null (실측 0% 와 구분)")
    void stockMissingOnLatestDate_returnsNull() {
        when(repository.findLatestTradeDate()).thenReturn(Optional.of(LocalDate.of(2026, 7, 7)));
        when(repository.findByStockCodeAndTradeDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        assertThat(service.getShortSellingRatio("005930")).isNull();
    }

    @Test
    @DisplayName("조회 예외 → null (ZERO 위장 금지)")
    void repositoryException_returnsNull() {
        when(repository.findLatestTradeDate()).thenThrow(new RuntimeException("DB down"));
        assertThat(service.getShortSellingRatio("005930")).isNull();
    }

    @Test
    @DisplayName("실측값은 그대로 반환")
    void realValue_returned() {
        when(repository.findLatestTradeDate()).thenReturn(Optional.of(LocalDate.of(2026, 7, 7)));
        when(repository.findByStockCodeAndTradeDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.of(balance("005930", "3.25")));
        assertThat(service.getShortSellingRatio("005930")).isEqualByComparingTo("3.25");
    }

    @Test
    @DisplayName("isHighShortSellingStock — 결측(null)=통과(차단 안 함), 실측 5%↑=차단, 실측 5%↓=통과")
    void isHighShortSelling_missingPassesRealValueJudges() {
        // 결측 → 통과 (결측 근거 차단 금지)
        when(repository.findLatestTradeDate()).thenReturn(Optional.empty());
        assertThat(service.isHighShortSellingStock("005930")).isFalse();

        // 실측 6.0% → 차단
        when(repository.findLatestTradeDate()).thenReturn(Optional.of(LocalDate.of(2026, 7, 7)));
        when(repository.findByStockCodeAndTradeDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.of(balance("005930", "6.0")));
        assertThat(service.isHighShortSellingStock("005930")).isTrue();

        // 실측 2.0% → 통과
        when(repository.findByStockCodeAndTradeDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.of(balance("005930", "2.0")));
        assertThat(service.isHighShortSellingStock("005930")).isFalse();
    }
}
