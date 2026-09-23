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

    /**
     * ⚠ 자기참조 유니버스는 <b>쓰레기 코드를 스스로 재생산한다</b>(2026-09-23 운영 실측).
     *
     * <p>{@code POST /api/screener/collect/finance} 가 {@code /collect/{stockCode}} 에 잡혀
     * {@code stock_code='finance'} 행이 하나 생겼다. 유니버스가 "기존 수집분 ∪ 마스터"라 그 코드가
     * 다음 배치 유니버스에 들어가고(9/22 2,662 → 9/23 <b>2,663</b>), KIS 는 없는 코드에도 200 을 주니
     * 수집기가 또 빈 행을 저장한다 — 행을 지우지 않는 한 <b>매일 영구히</b> 남는다.
     *
     * <p>형식이 틀린 코드는 실재 종목일 수 없으므로 유니버스에서 뺀다. 기준은 종목마스터 규약과 같은
     * <b>6자리 영숫자</b>다 — {@code \d{6}} 으로 좁히면 영문이 섞인 신형 코드(실측 {@code 0120G0}
     * 삼양바이오팜·{@code 0088M0} 메쥬, 둘 다 마스터 밖 기존 수집분)가 빠진다.
     * R5 합집합 불변식은 그대로다 — 실제 종목은 한 개도 줄지 않는다.
     */
    @Test
    @DisplayName("형식이 틀린 코드는 유니버스에서 뺀다 — 쓰레기 행이 매일 재생산되는 고리를 끊는다")
    void malformedCodesAreDroppedFromUniverse() {
        when(financialRepo.findAllStockCodes())
                .thenReturn(List.of("005930", "finance", "0120G0", "0088M0", "091990"));
        when(masterRepo.findActiveEquityCodes()).thenReturn(List.of("005930", "000660"));

        assertThat(service().resolveCollectionUniverse())
                .as("실재 종목(신형 영문 코드 포함)은 전부 남고 'finance' 만 빠져야 한다")
                .containsExactlyInAnyOrder("005930", "0120G0", "0088M0", "091990", "000660");
    }

    @Test
    @DisplayName("마스터 쪽의 형식 위반도 같은 규칙으로 뺀다 — 출처와 무관한 단일 기준")
    void malformedMasterCodesAreDroppedToo() {
        when(financialRepo.findAllStockCodes()).thenReturn(List.of("005930"));
        when(masterRepo.findActiveEquityCodes()).thenReturn(java.util.Arrays.asList("000660", "", "abc123", null));

        assertThat(service().resolveCollectionUniverse())
                .containsExactlyInAnyOrder("005930", "000660");
    }
}
