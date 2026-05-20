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
        // 종합·실적·수급·기술·섹터·밸류·성장성 = 7 factors (phase: 성장성 분리 추가)
        assertThat(result.getFactors()).hasSize(7);
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

    // ================================================================
    // phase 22b — 시그널 충돌 해설 (conflictNote)
    // ================================================================

    @Test
    @DisplayName("conflict: 단기 강(80) + 장기 가치 매우 약(2) → 익절 짧게 멘트")
    void conflict_strongMomentumLowValue() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(80, 16, 16, 14, 14, 2)));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getConflictNote()).isNotNull();
        assertThat(result.getConflictNote()).contains("익절 3% 내");
    }

    @Test
    @DisplayName("conflict: 종합 강(80) + 기술 약(5) → 고점 추격 경고")
    void conflict_strongTotalWeakTechnical() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(80, 16, 16, 5, 14, 10)));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getConflictNote()).contains("고점 추격");
    }

    @Test
    @DisplayName("conflict: 장기 강(15) + 수급 강(13) + 기술 약(7) → 분할 매수")
    void conflict_longTermSupplyButTechnicalWeak() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(45, 8, 13, 7, 6, 15)));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getConflictNote()).contains("분할 매수");
    }

    @Test
    @DisplayName("conflict: 실적 강(16) + 종합 낮음(45) → 매집 후보")
    void conflict_strongEarningsLowAttention() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(45, 16, 5, 5, 5, 8)));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getConflictNote()).contains("매집 후보");
    }

    @Test
    @DisplayName("conflict: 섹터 강(16) + 기술 약(5) → 섹터 ETF 대안")
    void conflict_strongSectorWeakTechnical() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(55, 12, 12, 5, 16, 6)));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getConflictNote()).contains("섹터 ETF");
    }

    @Test
    @DisplayName("conflict: 모든 카테고리 6~10 평범 → 더 매력적 후보 우선")
    void conflict_allMidRange() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(50, 8, 9, 7, 10, 6)));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getConflictNote()).contains("뚜렷한 강점 없음");
    }

    @Test
    @DisplayName("conflict: 깔끔한 STRONG_BUY (4 카테고리 균형) → 충돌 없음 null")
    void conflict_none_cleanStrongBuy() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(80, 16, 16, 14, 14, 12)));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getConflictNote()).isNull();
    }
}
