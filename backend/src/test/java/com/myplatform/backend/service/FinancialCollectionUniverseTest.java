package com.myplatform.backend.service;

import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockMasterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 재무 수집 유니버스 ({@link StockFinancialDataService#resolveCollectionUniverse}) — AUDIT 2026-08-21 R5.
 *
 * <p>고치려는 결함: 유니버스가 {@code stock_financial_data} 자기참조라 신규 상장이 영구 배제되고
 * 테이블이 비면 부트스트랩이 불가능했다. 고치다가 반대 실패(마스터로 <b>교체</b>했다가 KRX 동기화
 * 실패 기간에 유니버스가 통째로 쪼그라드는 것)를 만들지 않는지도 같이 고정한다.
 */
class FinancialCollectionUniverseTest {

    private final StockFinancialDataRepository financialRepo = mock(StockFinancialDataRepository.class);
    private final StockMasterRepository masterRepo = mock(StockMasterRepository.class);

    private StockFinancialDataService service() {
        return new StockFinancialDataService(
                financialRepo, masterRepo,
                mock(KoreaInvestmentService.class),
                mock(StockFinancialDataCollector.class),
                mock(SseEmitterService.class));
    }

    @Test
    @DisplayName("마스터의 신규 상장이 유니버스에 들어온다 — 자기참조로는 영영 못 들어오던 종목")
    void newListingEntersUniverse() {
        when(financialRepo.findAllStockCodes()).thenReturn(List.of("005930", "000660"));
        when(masterRepo.findActiveEquityCodes()).thenReturn(List.of("005930", "000660", "999999"));

        assertThat(service().resolveCollectionUniverse())
                .containsExactlyInAnyOrder("005930", "000660", "999999");
    }

    @Test
    @DisplayName("재무 테이블이 비어도 마스터로 부트스트랩된다")
    void bootstrapsFromEmptyFinancialTable() {
        when(financialRepo.findAllStockCodes()).thenReturn(List.of());
        when(masterRepo.findActiveEquityCodes()).thenReturn(List.of("005930", "000660"));

        assertThat(service().resolveCollectionUniverse()).hasSize(2);
    }

    @Test
    @DisplayName("마스터가 비어도 기존 수집분은 줄지 않는다 — KRX 동기화 실패 기간 방어")
    void masterOutageDoesNotShrinkUniverse() {
        when(financialRepo.findAllStockCodes()).thenReturn(List.of("005930", "000660"));
        when(masterRepo.findActiveEquityCodes()).thenReturn(List.of());

        assertThat(service().resolveCollectionUniverse())
                .containsExactlyInAnyOrder("005930", "000660");
    }

    @Test
    @DisplayName("마스터 조회가 터져도 기존 목록으로 진행 — 조회 실패를 '종목 없음'으로 만들지 않는다(§4c)")
    void masterFailureFallsBackToExisting() {
        when(financialRepo.findAllStockCodes()).thenReturn(List.of("005930"));
        when(masterRepo.findActiveEquityCodes()).thenThrow(new RuntimeException("DB down"));

        assertThat(service().resolveCollectionUniverse()).containsExactly("005930");
    }

    @Test
    @DisplayName("중복은 한 번만 — 양쪽에 다 있는 종목을 두 번 수집하지 않는다")
    void deduplicates() {
        when(financialRepo.findAllStockCodes()).thenReturn(List.of("005930", "005930"));
        when(masterRepo.findActiveEquityCodes()).thenReturn(List.of("005930"));

        assertThat(service().resolveCollectionUniverse()).containsExactly("005930");
    }
}
