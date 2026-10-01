package com.myplatform.backend.repository;

import com.myplatform.backend.entity.StockFinancialData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스크리너 3곳(마법의 공식·PEG·성장)의 "종목별 최신 행" 선택은 미래 날짜 행을 집지 않는다(2026-10-01 감사).
 *
 * <p>운영 실측: 네이버 크롤이 남긴 {@code report_date=2026-12-31} 추정치 행 342개는 PER 이 전부 비어 있다.
 * 쿼리가 {@code MAX(report_date)} 로 그 행을 "최신"으로 집으면 조건을 못 넘어 <b>종목이 통째로 빠진다</b> —
 * 그중 오늘 KIS 행 기준으로는 조건을 넘는 종목이 251개(마법의 공식 후보 854 → 원래 약 1,105)였다.
 * 읽는 쪽 규칙({@code FinancialRowSynthesizer.excludeFutureDated}, 2026-09-02)을 이 세 쿼리만 빠뜨렸다.
 *
 * <p>인메모리 H2(MySQL 모드)에서 실제 JPQL 을 돌린다 — 설정은 {@link SignalOutcomeRepositoryD3Test} 와 같다.
 */
@SpringBootTest(classes = StockFinancialDataScreenerQueryTest.JpaOnly.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:screenerlatest;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false"
})
@Transactional
class StockFinancialDataScreenerQueryTest {

    @Configuration
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class})
    @EntityScan(basePackageClasses = StockFinancialData.class)
    @EnableJpaRepositories(basePackageClasses = StockFinancialDataRepository.class)
    static class JpaOnly {}

    @Autowired
    StockFinancialDataRepository repo;

    private static final LocalDate TODAY = LocalDate.now();
    /** 연말 추정치 행 — 항상 오늘보다 뒤가 되게 잡는다(12-31 이 오늘이면 테스트가 미래를 못 만든다). */
    private static final LocalDate ESTIMATE_DATE = TODAY.plusDays(91);

    /** KIS 일별 스냅샷 행 — 시총이 채워져 있다. */
    private StockFinancialData kisRow(String code, String per, String roe, String opm, String peg,
                                      String epsGrowth, String profitGrowth) {
        return repo.saveAndFlush(StockFinancialData.builder()
                .stockCode(code).stockName(code).market("KOSPI").reportDate(TODAY)
                .marketCap(new BigDecimal("1000"))
                .per(dec(per)).roe(dec(roe)).operatingMargin(dec(opm))
                .peg(dec(peg)).epsGrowth(dec(epsGrowth)).profitGrowth(dec(profitGrowth))
                .build());
    }

    /** 네이버 연말 추정치 행 — 운영의 342개처럼 PER·PEG 가 비어 있고 시총도 없다. */
    private StockFinancialData estimateRow(String code, String per, String roe, String opm) {
        return repo.saveAndFlush(StockFinancialData.builder()
                .stockCode(code).stockName(code).market("KOSPI").reportDate(ESTIMATE_DATE)
                .per(dec(per)).roe(dec(roe)).operatingMargin(dec(opm))
                .build());
    }

    private static BigDecimal dec(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    @Test
    @DisplayName("마법의 공식 — 오늘 행이 조건을 넘으면 미래 추정치 행(PER 없음) 때문에 종목이 빠지지 않는다")
    void magicFormulaKeepsStockWithFutureEstimateRow() {
        kisRow("A00001", "5.0", "15.0", "10.0", null, null, null);
        estimateRow("A00001", null, null, null);
        kisRow("B00002", "6.0", "12.0", "8.0", null, null, null);   // 미래 행 없는 대조

        List<StockFinancialData> rows = repo.findForMagicFormula(null);

        assertThat(rows).extracting(StockFinancialData::getStockCode).containsExactlyInAnyOrder("A00001", "B00002");
        assertThat(rows).extracting(StockFinancialData::getReportDate).containsOnly(TODAY);
    }

    @Test
    @DisplayName("마법의 공식 — 추정치 행이 조건을 넘어도 그 값(미래)을 읽지 않고 오늘 행을 쓴다")
    void magicFormulaNeverReadsTheEstimate() {
        kisRow("C00003", "10.0", "10.0", "5.0", null, null, null);
        estimateRow("C00003", "3.0", "30.0", "40.0");                  // 그럴듯한 추정치 — 읽으면 1등이 된다

        List<StockFinancialData> rows = repo.findForMagicFormula(null);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getReportDate()).isEqualTo(TODAY);
            assertThat(r.getPer()).isEqualByComparingTo("10.0");
        });
    }

    @Test
    @DisplayName("PEG — 오늘 행에 PEG 가 있으면 미래 행(PEG 없음) 때문에 빠지지 않는다")
    void lowPegKeepsStockWithFutureEstimateRow() {
        kisRow("D00004", "8.0", "12.0", "6.0", "0.8", "20.0", "20.0");
        estimateRow("D00004", null, null, null);

        List<StockFinancialData> rows = repo.findLowPegStocks(null, null);

        assertThat(rows).extracting(StockFinancialData::getStockCode).containsExactly("D00004");
        assertThat(rows.get(0).getReportDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("성장 데이터 — 오늘 행에 PER·성장률이 있으면 미래 행 때문에 빠지지 않는다")
    void growthDataKeepsStockWithFutureEstimateRow() {
        kisRow("E00005", "10.0", "9.0", "4.0", null, null, "15.0");
        estimateRow("E00005", null, null, null);

        List<StockFinancialData> rows = repo.findStocksWithGrowthData();

        assertThat(rows).extracting(StockFinancialData::getStockCode).containsExactly("E00005");
        assertThat(rows.get(0).getReportDate()).isEqualTo(TODAY);
    }
}
