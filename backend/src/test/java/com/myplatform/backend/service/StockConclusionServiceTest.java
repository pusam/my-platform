package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockConclusionDto;
import com.myplatform.backend.dto.StockConclusionDto.Level;
import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * StockConclusionService 룰 엔진 단위 테스트.
 *
 * 결론 룰의 5가지 우선순위 분기를 모두 커버:
 *  1. total ≥ 75 → STRONG_BUY
 *  2. value 강 + total 약 → HOLD (저평가 분할)
 *  3. supplyDemand 강 + technical 약 → BUY (수급 추격 신중)
 *  4. total ≥ 55 → BUY
 *  5. total < 55 → WAIT
 *  6. 스냅샷 부재 → dataAvailable=false
 */
@ExtendWith(MockitoExtension.class)
class StockConclusionServiceTest {

    @Mock private RecommendationSnapshotRepository snapshotRepository;

    private StockConclusionService service;

    @BeforeEach
    void setUp() {
        service = new StockConclusionService(snapshotRepository);
    }

    private RecommendationSnapshot snapshot(int total, int earnings, int supply, int technical, int sector, int value) {
        RecommendationSnapshot s = new RecommendationSnapshot();
        s.setStockCode("005930");
        s.setStockName("삼성전자");
        s.setTotalScore(total);
        s.setEarnings(earnings);
        s.setSupplyDemand(supply);
        s.setTechnical(technical);
        s.setSectorMomentum(sector);
        s.setValueStability(value);
        s.setSnapshotAt(LocalDateTime.now());
        return s;
    }

    @Test
    @DisplayName("total 80 → STRONG_BUY")
    void strongBuy() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(80, 16, 16, 15, 14, 10)));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getLevel()).isEqualTo(Level.STRONG_BUY);
        assertThat(result.isDataAvailable()).isTrue();
        assertThat(result.getHeadline()).contains("매수 적기");
        assertThat(result.getFactors()).hasSize(6);
    }

    @Test
    @DisplayName("value 12 + total 40 → HOLD (저평가 분할 매수)")
    void valueStrongButTotalLow_holdSplit() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(40, 10, 5, 5, 5, 15)));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getLevel()).isEqualTo(Level.HOLD);
        assertThat(result.getHeadline()).contains("저평가");
        assertThat(result.getHeadline()).contains("분할");
        assertThat(result.getGuidance()).contains("순매수");
    }

    @Test
    @DisplayName("supplyDemand 18 + technical 5 + total 50 → BUY (수급 추격 신중)")
    void supplyStrongButTechnicalWeak() {
        // total 50 — BUY_THRESHOLD(55) 미만이지만 value 도 약해 HOLD 분기 회피
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(50, 8, 18, 5, 10, 5)));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getLevel()).isEqualTo(Level.BUY);
        assertThat(result.getHeadline()).contains("수급");
    }

    @Test
    @DisplayName("total 60 → BUY (일반 매수 신호)")
    void buyGeneral() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(60, 12, 10, 10, 10, 8)));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getLevel()).isEqualTo(Level.BUY);
    }

    @Test
    @DisplayName("total 40 + value 약 → WAIT")
    void wait_lowScore() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(40, 8, 8, 8, 8, 5)));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getLevel()).isEqualTo(Level.WAIT);
        assertThat(result.getHeadline()).contains("관망");
    }

    @Test
    @DisplayName("스냅샷 없음 → dataAvailable=false, headline 일반 안내")
    void notAvailable() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.empty());

        StockConclusionDto result = service.getConclusion("999999");

        assertThat(result.isDataAvailable()).isFalse();
        assertThat(result.getLevel()).isEqualTo(Level.WAIT);
        assertThat(result.getFactors()).isEmpty();
    }
}
