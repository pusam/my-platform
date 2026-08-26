package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockFinancialData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재무 행 필드별 합성 ({@link FinancialRowSynthesizer}) — AUDIT 2026-08-21 R4.
 *
 * <p>고치려는 결함: 일별 스냅샷 테이블에서 <b>최신 1행만</b> 집으면 0 placeholder 투성이 행을 물어
 * 종목이 트랙에서 통째로 사라진다(실측 005930: 18점이어야 할 종목이 5점).
 *
 * <p>고치다 만들 수 있는 반대 결함: 0 을 건너뛰다가 <b>음수(적자·역성장)까지 지워</b> 적자 기업이
 * 흑자처럼 보이는 것. 그 경계를 여기서 못 박는다.
 */
class FinancialRowSynthesizerTest {

    private static BigDecimal bd(String v) { return v == null ? null : new BigDecimal(v); }

    private static StockFinancialData row(String date) {
        StockFinancialData r = new StockFinancialData();
        r.setStockCode("005930");
        r.setStockName("삼성전자");
        r.setMarket("KOSPI");
        r.setReportDate(LocalDate.parse(date));
        return r;
    }

    @Test
    @DisplayName("최신 행의 0 placeholder 를 건너뛰고 이전 행의 진짜 값을 쓴다 — R4 의 원형")
    void skipsZeroPlaceholderInLatestRow() {
        StockFinancialData latest = row("2026-08-26");
        latest.setPbr(bd("1.2"));
        latest.setDebtRatio(bd("0.00"));      // placeholder
        latest.setTotalEquity(bd("0"));       // placeholder

        StockFinancialData older = row("2026-08-25");
        older.setDebtRatio(bd("42.5"));
        older.setTotalEquity(bd("3600000"));

        StockFinancialData out = FinancialRowSynthesizer.synthesize(List.of(latest, older));

        assertThat(out.getPbr()).isEqualByComparingTo("1.2");
        assertThat(out.getDebtRatio()).isEqualByComparingTo("42.5");
        assertThat(out.getTotalEquity()).isEqualByComparingTo("3600000");
    }

    @Test
    @DisplayName("음수는 값이다 — 적자·역성장을 placeholder 로 오인해 지우지 않는다")
    void keepsNegativesAsRealValues() {
        StockFinancialData latest = row("2026-08-26");
        latest.setOperatingProfit(bd("-1500"));    // 적자
        latest.setRoe(bd("-8.3"));
        latest.setProfitGrowth(bd("-42.0"));       // 역성장

        StockFinancialData older = row("2026-08-20");
        older.setOperatingProfit(bd("2000"));      // 과거 흑자 — 덮어쓰면 안 된다
        older.setRoe(bd("11.0"));
        older.setProfitGrowth(bd("35.0"));

        StockFinancialData out = FinancialRowSynthesizer.synthesize(List.of(latest, older));

        assertThat(out.getOperatingProfit()).isEqualByComparingTo("-1500");
        assertThat(out.getRoe()).isEqualByComparingTo("-8.3");
        assertThat(out.getProfitGrowth()).isEqualByComparingTo("-42.0");
    }

    @Test
    @DisplayName("PBR·부채비율 같은 필드는 음수도 건너뛴다 — 음수 PBR 은 데이터 오류지 사실이 아니다")
    void skipsNegativesForPositiveOnlyFields() {
        StockFinancialData latest = row("2026-08-26");
        latest.setPbr(bd("-1.0"));
        StockFinancialData older = row("2026-08-25");
        older.setPbr(bd("0.85"));

        assertThat(FinancialRowSynthesizer.synthesize(List.of(latest, older)).getPbr())
                .isEqualByComparingTo("0.85");
    }

    @Test
    @DisplayName("어느 행에도 값이 없으면 null — 0 으로 만들어 '부채 0%' 같은 거짓을 남기지 않는다(§4c)")
    void allMissingStaysNull() {
        StockFinancialData a = row("2026-08-26");
        a.setDebtRatio(bd("0"));
        StockFinancialData b = row("2026-08-25");
        b.setDebtRatio(null);

        assertThat(FinancialRowSynthesizer.synthesize(List.of(a, b)).getDebtRatio()).isNull();
    }

    @Test
    @DisplayName("식별·기준일은 최신 행 것을 쓴다 — 오래된 행의 이름·날짜가 섞이지 않게")
    void identityComesFromLatestRow() {
        StockFinancialData latest = row("2026-08-26");
        StockFinancialData older = row("2026-01-02");
        older.setStockName("옛이름");

        StockFinancialData out = FinancialRowSynthesizer.synthesize(List.of(older, latest));

        assertThat(out.getStockName()).isEqualTo("삼성전자");
        assertThat(out.getReportDate()).isEqualTo(LocalDate.parse("2026-08-26"));
    }

    @Test
    @DisplayName("입력 엔티티를 변경하지 않는다 — JPA 영속 객체를 건드리면 의도치 않은 UPDATE 가 나간다")
    void doesNotMutateInput() {
        StockFinancialData latest = row("2026-08-26");
        latest.setDebtRatio(bd("0"));
        StockFinancialData older = row("2026-08-25");
        older.setDebtRatio(bd("42.5"));

        FinancialRowSynthesizer.synthesize(List.of(latest, older));

        assertThat(latest.getDebtRatio()).isEqualByComparingTo("0");   // 여전히 0
        assertThat(older.getDebtRatio()).isEqualByComparingTo("42.5");
    }

    @Test
    @DisplayName("빈 입력·기준일 결측은 null 반환 (호출부가 걸러낸다)")
    void emptyInputs() {
        assertThat(FinancialRowSynthesizer.synthesize(null)).isNull();
        assertThat(FinancialRowSynthesizer.synthesize(List.of())).isNull();
        StockFinancialData noDate = new StockFinancialData();
        noDate.setStockCode("005930");
        assertThat(FinancialRowSynthesizer.synthesize(List.of(noDate))).isNull();
    }

    @Test
    @DisplayName("행 순서가 뒤섞여 들어와도 최신 우선으로 정렬해 합성한다")
    void sortsRegardlessOfInputOrder() {
        StockFinancialData old1 = row("2026-08-20");
        old1.setPbr(bd("2.0"));
        StockFinancialData newest = row("2026-08-26");
        newest.setPbr(bd("1.1"));

        assertThat(FinancialRowSynthesizer.synthesize(List.of(old1, newest)).getPbr())
                .isEqualByComparingTo("1.1");
    }
}
