package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockInvestorDetailDto;
import com.myplatform.backend.entity.InvestorDailyTrade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 수급 표시값의 "데이터 없음" — 2026-08-27 종목상세 표시층 감사 A-1·A-2 회귀.
 *
 * <h3>고친 결함</h3>
 * 이 데이터의 원천은 KIS <b>순매수 상위 20위</b> API 다({@code InvestorDailyTradeService} 의
 * {@code rank > 20 break}). 그 종목이 그날 상위권에 못 들면 <b>행 자체가 없다.</b>
 * 그런데 백엔드가 그걸 {@code BigDecimal.ZERO} 로 채워 내보내서, 화면이 "순매수 0억"으로 그리고
 * 사용자는 <b>"그날 외국인이 사지도 팔지도 않았다"</b>로 읽었다. 사실은 "데이터가 없다"이다.
 *
 * <h3>왜 프론트에서 못 막았나</h3>
 * 프론트는 이미 §4c 를 지키고 있었다({@code InvestorTrendTab.vue} 의 {@code value == null → '-'}).
 * <b>백엔드가 0 을 만들어 보내는 바람에 그 가드가 한 번도 발동하지 못했다.</b>
 * 그래서 수정 지점이 프론트가 아니라 여기다.
 */
class InvestorSummaryNullTest {

    @SuppressWarnings("unchecked")
    private final InvestorTradeService service = new InvestorTradeService(
            mock(com.myplatform.backend.repository.InvestorDailyTradeRepository.class),
            mock(KisInvestorDataCollector.class),
            mock(KoreaInvestmentService.class),
            mock(RedisCacheService.class),
            mock(InvestorDailyTradeService.class),
            mock(MarketCalendarService.class),
            mock(org.springframework.beans.factory.ObjectProvider.class));

    private InvestorDailyTrade t(String investor, String type, String krw) {
        return InvestorDailyTrade.builder()
                .tradeDate(LocalDate.of(2026, 8, 27))
                .investorType(investor).tradeType(type)
                .netBuyAmount(new BigDecimal(krw))
                .buyAmount(new BigDecimal(krw)).sellAmount(BigDecimal.ZERO)
                .build();
    }

    // ==================== A-1 ====================

    @Test
    @DisplayName("행이 없으면 null — 상위20 미진입을 '순매수 0억'으로 위장하지 않는다")
    void missingInvestorBecomesNull() {
        assertThat(service.buildInvestorSummary(null)).isNull();
        assertThat(service.buildInvestorSummary(List.of())).isNull();
    }

    @Test
    @DisplayName("행이 있으면 합산해서 돌려준다 — 정상 경로는 그대로")
    void presentInvestorIsSummed() {
        StockInvestorDetailDto.InvestorTradeSummary s =
                service.buildInvestorSummary(List.of(t("FOREIGN", "BUY", "120")));

        assertThat(s).isNotNull();
        assertThat(s.getNetBuyAmount()).isEqualByComparingTo("120");
    }

    @Test
    @DisplayName("실측 0 은 null 이 아니다 — 상위20에 들었고 순매수가 정확히 0인 경우")
    void realZeroIsNotMissing() {
        StockInvestorDetailDto.InvestorTradeSummary s =
                service.buildInvestorSummary(List.of(t("FOREIGN", "BUY", "0")));

        assertThat(s).isNotNull();
        assertThat(s.getNetBuyAmount()).isEqualByComparingTo("0");
    }

    // ==================== A-2 ====================

    @Test
    @DisplayName("집계 가능한 날이 0일이면 표시값은 null — 합계 0 을 '균형'으로 내보내지 않는다")
    void noDataDaysBecomesNull() {
        // 화면은 v >= 0 을 '순매수'로 분류한다. 0 을 내보내면 결측이 '순매수 +0억'이 된다.
        assertThat(StockAnalysisService.displayNet(BigDecimal.ZERO, 0)).isNull();
        assertThat(StockAnalysisService.displayNet(new BigDecimal("211"), 0)).isNull();
    }

    @Test
    @DisplayName("하루라도 데이터가 있으면 합계를 그대로 — 실측 0 포함")
    void withDataDaysPassesThrough() {
        assertThat(StockAnalysisService.displayNet(new BigDecimal("211"), 3))
                .isEqualByComparingTo("211");
        // 상위권에 들었는데 매수·매도가 같으면 진짜 0 이다. 결측과 구분된다.
        assertThat(StockAnalysisService.displayNet(BigDecimal.ZERO, 1))
                .isEqualByComparingTo("0");
        assertThat(StockAnalysisService.displayNet(new BigDecimal("-58"), 2))
                .isEqualByComparingTo("-58");
    }
}
