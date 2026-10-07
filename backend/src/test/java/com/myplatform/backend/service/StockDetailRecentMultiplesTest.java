package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockFinancialData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종목 상세 PER·PBR 옆에 '최근 실적 기준' 값을 함께 보인다(2026-10-07, 사용자 결정 '둘 다 표시').
 *
 * <p>재현(10/7 운영 삼성전자): 상세의 PER·PBR 은 FnGuide 가 죽어 늘 KIS 현재가 API 의 최근 결산(연간) EPS 6,564원·BPS
 * 63,997원으로 만든다 — PER 41.4·PBR 4.25. 점수·목록(저평가·AI 스윙·마법의 공식)이 쓰는 재무 행은 DART 지배주주 최근 4분기
 * 순이익(149.7조)·최근 분기 지배지분 자본으로 EPS 25,601원·BPS 96,654원 — PER 10.5·PBR 2.79. 이익이 크게 변한 해엔 같은
 * 회사가 화면마다 4배 비싸 보였다. 상세 툴팁은 "종목별로 DART 지배주주 TTM · KIS 연결 TTM · KIS 연간 중 하나"라 했지만
 * 상세 값은 늘 연간이었다.
 */
class StockDetailRecentMultiplesTest {

    private static StockFinancialData row(String eps, String bps, String basis, Long marketCap) {
        StockFinancialData f = new StockFinancialData();
        f.setStockCode("005930");
        f.setReportDate(LocalDate.of(2026, 10, 7));
        f.setEps(eps == null ? null : new BigDecimal(eps));
        f.setBps(bps == null ? null : new BigDecimal(bps));
        f.setPerBasis(basis);
        f.setMarketCap(marketCap == null ? null : new BigDecimal(marketCap));
        return f;
    }

    @Test
    @DisplayName("재현: 삼성전자 — 점수가 쓰는 행(EPS 25,601·BPS 96,654, 지배주주)을 현재가 272,000원으로 나누면 PER 10.6·PBR 2.81")
    void samsungRecentMultiples() {
        StockDetailService.RecentMultiples m = StockDetailService.recentMultiples(
                row("25601", "96654", "CTRL", 15755721L), new BigDecimal("272000"));

        assertThat(m).isNotNull();
        assertThat(m.per()).isEqualByComparingTo("10.6");
        assertThat(m.pbr()).isEqualByComparingTo("2.81");
        assertThat(m.basis()).isEqualTo("CTRL");
        assertThat(m.asOf()).isEqualTo(LocalDate.of(2026, 10, 7));
    }

    @Test
    @DisplayName("연간 값뿐인 행(KIS)은 보이지 않는다 — 같은 값을 '최근' 이름으로 한 번 더 보이지 않게")
    void annualOnlyRowIsNotShown() {
        assertThat(StockDetailService.recentMultiples(row("6564", "63997", "KIS", 15755721L), new BigDecimal("272000"))).isNull();
    }

    @Test
    @DisplayName("적자면 PER 은 비우고 PBR 만 — 비지배 포함 연결(CONSOL) 기준도 그 이름으로 넘긴다")
    void lossLeavesPerEmpty() {
        StockDetailService.RecentMultiples m = StockDetailService.recentMultiples(
                row("-8325", "272485", "CONSOL", 390000L), new BigDecimal("568000"));

        assertThat(m).isNotNull();
        assertThat(m.per()).isNull();
        assertThat(m.pbr()).isEqualByComparingTo("2.08");
        assertThat(m.basis()).isEqualTo("CONSOL");
    }

    @Test
    @DisplayName("KIS 일별 행이 아니면(시가총액 없음 = 옛 네이버 분기 행)·현재가를 모르면 보이지 않는다")
    void notKisDailyRowOrNoPrice() {
        assertThat(StockDetailService.recentMultiples(row("25601", "96654", "CTRL", null), new BigDecimal("272000"))).isNull();
        assertThat(StockDetailService.recentMultiples(row("25601", "96654", "CTRL", 15755721L), null)).isNull();
        assertThat(StockDetailService.recentMultiples(null, new BigDecimal("272000"))).isNull();
    }
}
