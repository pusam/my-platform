package com.myplatform.backend.service;

import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KRX 투자자 보충 수집 은퇴 가드(2026-09-28).
 *
 * <p><b>무엇이 있었나</b>: 투자자 수집({@code InvestorTradeService.collectInvestorTradeData})이 KIS 로 외국인·기관·연기금을
 * 받은 뒤 "KRX 보충"을 두 번 더 했다 — ① KIS 연기금이 0건이면 KOSPI 를 KRX 로 폴백 ② "KOSDAQ 연기금은 KIS 가 커버하지
 * 않는다"며 KOSDAQ 을 항상 KRX 로. {@code data.krx.co.kr} 은 이 용도로 죽어 있어(CLAUDE.md §4 마스터 항목) 매번 400 을 받았고,
 * 운영 DB 에 KRX 경로로 들어온 KOSDAQ 연기금 행은 <b>한 번도 없다</b>. 전제도 틀렸다 — KIS 순위 API 는
 * {@code FID_INPUT_ISCD=0000}(전체 시장)이라 9월 KIS 연기금 행의 20.6% 가 이미 KOSDAQ 종목이다.
 * 같은 클래스의 투신·사모·보험 수집({@code collectAllInvestorTrades})은 호출처가 없어 역시 한 번도 돈 적이 없다.
 *
 * <p>⚠ 이 테스트가 깨지면 <b>그게 의도다.</b> 연기금 출처가 두 벌이 되면 어느 쪽 값이 맞는지 다시 갈리고, 죽은 소스는
 * 로그에 ERROR 만 남긴다. 새 투자자 유형이 필요하면 KIS 경로(공식 샘플로 필드 확인)에 붙일 것.
 */
class KrxInvestorSupplementRetiredTest {

    @Test
    @DisplayName("KRX 투자자 수집 클래스는 지워졌다 — 되살리지 말 것")
    void krxCollectorClassIsGone() {
        assertThatThrownBy(() -> Class.forName("com.myplatform.backend.service.InvestorDailyTradeService"))
                .as("InvestorDailyTradeService 가 다시 생겼다 — 연기금 단일 출처는 KIS 기관 순위 응답(fund_ntby_tr_pbmn)")
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("KIS 연기금이 0건이어도 KRX 보충을 시도하지 않는다 — 0건은 0건으로 둔다(§4c)")
    @SuppressWarnings("unchecked")
    void noKrxSupplementWhenKisPensionEmpty() {
        InvestorDailyTradeRepository repo = mock(InvestorDailyTradeRepository.class);
        KisInvestorDataCollector kis = mock(KisInvestorDataCollector.class);
        LocalDate day = LocalDate.of(2026, 9, 28);
        when(kis.collectDailyInvestorTrades(day)).thenReturn(Map.of("KOSPI_FOREIGN_BUY", 30, "KOSPI_PENSION_BUY", 0));
        when(repo.existsByTradeDate(day)).thenReturn(false);
        when(repo.existsByInvestorTypeAndTradeDate("PENSION", day)).thenReturn(false);
        InvestorTradeService service = new InvestorTradeService(repo, kis, mock(KoreaInvestmentService.class),
                mock(RedisCacheService.class), mock(MarketCalendarService.class), mock(ObjectProvider.class));

        Map<String, Integer> result = service.collectInvestorTradeData(day);

        // 예전엔 여기에 KOSPI_PENSION_KRX_FALLBACK·KOSDAQ_PENSION_KRX 가 붙었다(항상 0 — KRX 가 죽어 있었다)
        assertThat(result).containsOnlyKeys("KOSPI_FOREIGN_BUY", "KOSPI_PENSION_BUY");
    }
}
