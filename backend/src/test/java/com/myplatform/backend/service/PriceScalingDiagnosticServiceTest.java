package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockMaster;
import com.myplatform.backend.entity.StockPrice;
import com.myplatform.backend.repository.StockMasterRepository;
import com.myplatform.backend.repository.StockPriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-2: ×10 스케일 이상치 스캐너 진단·분류 테스트.
 *
 * <p>합격 기준: ×10 발생 종목을 뽑아 (1) 두 가설로 분류하고 (2) market·세션시간대로 군집 집계.
 * NXT 단독 구간(프리/애프터) 버킷팅이 정확해야 "NXT 관여 의심"을 데이터로 말할 수 있다.
 */
class PriceScalingDiagnosticServiceTest {

    private StockPriceRepository priceRepo;
    private StockMasterRepository masterRepo;
    private PriceScalingDiagnosticService service;

    @BeforeEach
    void setUp() {
        priceRepo = mock(StockPriceRepository.class);
        masterRepo = mock(StockMasterRepository.class);
        service = new PriceScalingDiagnosticService(priceRepo, masterRepo);
    }

    private StockPrice row(String code, String name, BigDecimal cur, BigDecimal high,
                           BigDecimal low, BigDecimal ctrt, LocalDateTime at) {
        StockPrice sp = new StockPrice();
        sp.setStockCode(code);
        sp.setStockName(name);
        sp.setCurrentPrice(cur);
        sp.setHighPrice(high);
        sp.setLowPrice(low);
        sp.setChangeRate(ctrt);
        sp.setFetchedAt(at);
        return sp;
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    @Test
    @DisplayName("응답 전체 ×10 (NXT 프리마켓) → BATCH_SCALED + NXT_PREMARKET + market 조인")
    void batchScaledInNxtPremarket() {
        LocalDateTime d1 = LocalDateTime.of(2026, 6, 1, 10, 0); // KRX_REGULAR, 정상
        LocalDateTime d2 = LocalDateTime.of(2026, 6, 2, 10, 0);
        LocalDateTime d3 = LocalDateTime.of(2026, 6, 3, 10, 0);
        LocalDateTime bad = LocalDateTime.of(2026, 6, 4, 8, 30); // NXT_PREMARKET, ×10

        List<StockPrice> rows = new ArrayList<>(List.of(
                row("005930", "삼성전자", bd("70000"), bd("70500"), bd("69500"), bd("0.3"), d1),
                row("005930", "삼성전자", bd("71000"), bd("71500"), bd("70500"), bd("0.5"), d2),
                row("005930", "삼성전자", bd("70500"), bd("71000"), bd("70000"), bd("0.2"), d3),
                // 현재가·고·저가 통째 ×10, 등락률 정상 → BATCH_SCALED
                row("005930", "삼성전자", bd("705000"), bd("710000"), bd("700000"), bd("0.4"), bad)));
        when(priceRepo.findAllSince(any())).thenReturn(rows);
        when(masterRepo.findAllById(anyList())).thenReturn(List.of(
                StockMaster.builder().stockCode("005930").stockName("삼성전자").market("KOSPI").build()));

        PriceScalingDiagnosticService.Report r = service.scan(720, 200);

        assertThat(r.outlierEvents()).isEqualTo(1);
        assertThat(r.events()).hasSize(1);
        PriceScalingDiagnosticService.Event ev = r.events().get(0);
        assertThat(ev.kind()).isEqualTo("BATCH_SCALED");
        assertThat(ev.session()).isEqualTo("NXT_PREMARKET");
        assertThat(ev.market()).isEqualTo("KOSPI");
        assertThat(r.byKind()).containsEntry("BATCH_SCALED", 1);
        assertThat(r.bySession()).containsEntry("NXT_PREMARKET", 1);
        assertThat(r.byMarket()).containsEntry("KOSPI", 1);
    }

    @Test
    @DisplayName("현재가만 ×10 (고저가 정상, NXT 애프터) → CURRENT_FIELD_OUTLIER + NXT_AFTERHOURS")
    void currentFieldOutlierInAfterHours() {
        LocalDateTime d1 = LocalDateTime.of(2026, 6, 1, 10, 0);
        LocalDateTime d2 = LocalDateTime.of(2026, 6, 2, 10, 0);
        LocalDateTime d3 = LocalDateTime.of(2026, 6, 3, 10, 0);
        LocalDateTime bad = LocalDateTime.of(2026, 6, 4, 16, 30); // NXT_AFTERHOURS

        List<StockPrice> rows = new ArrayList<>(List.of(
                row("000660", "SK하이닉스", bd("200000"), bd("201000"), bd("199000"), bd("0.3"), d1),
                row("000660", "SK하이닉스", bd("202000"), bd("203000"), bd("201000"), bd("0.5"), d2),
                row("000660", "SK하이닉스", bd("201000"), bd("202000"), bd("200000"), bd("0.2"), d3),
                // 현재가만 ×10, 고·저가는 정상 → 밴드 밖 → CURRENT_FIELD_OUTLIER
                row("000660", "SK하이닉스", bd("2010000"), bd("202000"), bd("200000"), bd("0.4"), bad)));
        when(priceRepo.findAllSince(any())).thenReturn(rows);
        when(masterRepo.findAllById(anyList())).thenReturn(List.of(
                StockMaster.builder().stockCode("000660").stockName("SK하이닉스").market("KOSPI").build()));

        PriceScalingDiagnosticService.Report r = service.scan(720, 200);

        assertThat(r.outlierEvents()).isEqualTo(1);
        PriceScalingDiagnosticService.Event ev = r.events().get(0);
        assertThat(ev.kind()).isEqualTo("CURRENT_FIELD_OUTLIER");
        assertThat(ev.session()).isEqualTo("NXT_AFTERHOURS");
        assertThat(r.byKind()).containsEntry("CURRENT_FIELD_OUTLIER", 1);
        assertThat(r.bySession()).containsEntry("NXT_AFTERHOURS", 1);
    }

    @Test
    @DisplayName("정상 종목만 → 이상치 0건, note 에 '없음' 안내")
    void noOutliers() {
        LocalDateTime d1 = LocalDateTime.of(2026, 6, 1, 10, 0);
        LocalDateTime d2 = LocalDateTime.of(2026, 6, 2, 10, 0);
        LocalDateTime d3 = LocalDateTime.of(2026, 6, 3, 10, 0);
        List<StockPrice> rows = new ArrayList<>(List.of(
                row("035720", "카카오", bd("50000"), bd("50500"), bd("49500"), bd("0.3"), d1),
                row("035720", "카카오", bd("50500"), bd("51000"), bd("50000"), bd("0.5"), d2),
                row("035720", "카카오", bd("50200"), bd("50700"), bd("49900"), bd("0.2"), d3)));
        when(priceRepo.findAllSince(any())).thenReturn(rows);

        PriceScalingDiagnosticService.Report r = service.scan(720, 200);

        assertThat(r.outlierEvents()).isZero();
        assertThat(r.events()).isEmpty();
        assertThat(r.note()).contains("없음");
    }

    @Test
    @DisplayName("행이 3개 미만이면 median 앵커 불신 → skip (오탐 방지)")
    void tooFewRowsSkipped() {
        LocalDateTime d1 = LocalDateTime.of(2026, 6, 1, 10, 0);
        LocalDateTime bad = LocalDateTime.of(2026, 6, 4, 8, 30);
        List<StockPrice> rows = new ArrayList<>(List.of(
                row("900000", "X", bd("1000"), bd("1010"), bd("990"), bd("0.3"), d1),
                row("900000", "X", bd("10000"), bd("10100"), bd("9900"), bd("0.4"), bad)));
        when(priceRepo.findAllSince(any())).thenReturn(rows);

        PriceScalingDiagnosticService.Report r = service.scan(720, 200);

        assertThat(r.outlierEvents()).isZero();
    }

    @Test
    @DisplayName("세션 시간대 경계 매핑")
    void sessionBoundaries() {
        assertThat(PriceScalingDiagnosticService.sessionOf(LocalDateTime.of(2026, 6, 4, 7, 59)))
                .isEqualTo("OFF_HOURS");
        assertThat(PriceScalingDiagnosticService.sessionOf(LocalDateTime.of(2026, 6, 4, 8, 0)))
                .isEqualTo("NXT_PREMARKET");
        assertThat(PriceScalingDiagnosticService.sessionOf(LocalDateTime.of(2026, 6, 4, 9, 0)))
                .isEqualTo("KRX_REGULAR");
        assertThat(PriceScalingDiagnosticService.sessionOf(LocalDateTime.of(2026, 6, 4, 15, 30)))
                .isEqualTo("KRX_CLOSE_AUCTION");
        assertThat(PriceScalingDiagnosticService.sessionOf(LocalDateTime.of(2026, 6, 4, 15, 40)))
                .isEqualTo("NXT_AFTERHOURS");
        assertThat(PriceScalingDiagnosticService.sessionOf(LocalDateTime.of(2026, 6, 4, 20, 0)))
                .isEqualTo("NXT_AFTERHOURS");
        assertThat(PriceScalingDiagnosticService.sessionOf(LocalDateTime.of(2026, 6, 4, 20, 1)))
                .isEqualTo("OFF_HOURS");
    }
}
