package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockConclusionDto;
import com.myplatform.backend.dto.StockConclusionDto.Level;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
    @Mock private SignalOutcomeRepository signalOutcomeRepository;
    @Mock private StockPriceService stockPriceService;

    private StockConclusionService service;

    @BeforeEach
    void setUp() {
        service = new StockConclusionService(snapshotRepository, signalOutcomeRepository, stockPriceService);
        // 기본: 시세/MFE 데이터 없음 — tradePlan 은 % 만 채워짐. 개별 테스트에서 덮어씀.
        lenient().when(stockPriceService.getStockPrice(anyString())).thenReturn(null);
        lenient().when(signalOutcomeRepository.aggregateMfeMae(anyString(), any()))
                .thenReturn(List.of());
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

    // ================================================================
    // 매매 계획 (tradePlan) — 손절/목표가 + MFE/MAE
    // ================================================================

    @Test
    @DisplayName("tradePlan: STRONG_BUY + 현재가 70,000 → 손절 -3%(67,900) / 목표 +5%(73,500)")
    void tradePlan_strongBuy_defaultPcts() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(80, 16, 16, 15, 14, 10)));
        StockPriceDto price = new StockPriceDto();
        price.setCurrentPrice(new BigDecimal("70000"));
        when(stockPriceService.getStockPrice("005930")).thenReturn(price);

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getTradePlan()).isNotNull();
        assertThat(result.getTradePlan().getStopLossPct()).isEqualByComparingTo("-3");
        assertThat(result.getTradePlan().getTargetPct()).isEqualByComparingTo("5");
        assertThat(result.getTradePlan().getStopLossPrice()).isEqualByComparingTo("67900");
        assertThat(result.getTradePlan().getTargetPrice()).isEqualByComparingTo("73500");
        assertThat(result.getTradePlan().getBasePrice()).isEqualByComparingTo("70000");
    }

    @Test
    @DisplayName("tradePlan: 단기 강 + 밸류 매우 약(2) → 타이트 -2%/+3% (conflictNote 룰 1과 일관)")
    void tradePlan_tightWhenValueVeryLow() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(80, 16, 16, 14, 14, 2)));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getTradePlan()).isNotNull();
        assertThat(result.getTradePlan().getStopLossPct()).isEqualByComparingTo("-2");
        assertThat(result.getTradePlan().getTargetPct()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("tradePlan: WAIT 레벨 → tradePlan null")
    void tradePlan_nullForWait() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(40, 8, 8, 8, 8, 5)));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getTradePlan()).isNull();
    }

    @Test
    @DisplayName("tradePlan: 현재가 조회 실패 → 가격 null, % 는 유지")
    void tradePlan_pctOnlyWhenPriceUnavailable() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(60, 12, 10, 10, 10, 8)));
        when(stockPriceService.getStockPrice(anyString())).thenReturn(null);

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getTradePlan()).isNotNull();
        assertThat(result.getTradePlan().getBasePrice()).isNull();
        assertThat(result.getTradePlan().getStopLossPrice()).isNull();
        assertThat(result.getTradePlan().getStopLossPct()).isEqualByComparingTo("-3");
    }

    @Test
    @DisplayName("tradePlan: 과거 BUY 시그널 MFE/MAE 평균 + 표본 수 동봉")
    void tradePlan_includesMfeMaeStats() {
        when(snapshotRepository.findLatestByStockCode(anyString()))
                .thenReturn(Optional.of(snapshot(60, 12, 10, 10, 10, 8)));
        when(signalOutcomeRepository.aggregateMfeMae(eq("BUY"), any()))
                .thenReturn(List.<Object[]>of(new Object[]{12L, new BigDecimal("4.1234"), new BigDecimal("-2.5678")}));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getTradePlan().getMfeMaeSampleCount()).isEqualTo(12);
        assertThat(result.getTradePlan().getAvgMfePct()).isEqualByComparingTo("4.12");
        assertThat(result.getTradePlan().getAvgMaePct()).isEqualByComparingTo("-2.57");
    }

    // ================================================================
    // factor 근거 텍스트 — 스냅샷 태그 → 카테고리 매핑
    // ================================================================

    @Test
    @DisplayName("근거: 수급 factor 에 외국인/기관 태그, 기술 factor 에 골든크로스 태그 표시")
    void factorEvidence_tagsMappedToCategories() {
        RecommendationSnapshot s = snapshot(80, 16, 16, 15, 14, 10);
        s.setTags("외국인3일연속(초기),골든크로스,PBR저평가,기관순매수");
        when(snapshotRepository.findLatestByStockCode(anyString())).thenReturn(Optional.of(s));

        StockConclusionDto result = service.getConclusion("005930");

        String supplyNote = factorNote(result, "supplyDemand");
        assertThat(supplyNote).contains("근거:").contains("외국인3일연속(초기)").contains("기관순매수");
        assertThat(factorNote(result, "technical")).contains("골든크로스");
        assertThat(factorNote(result, "valueStability")).contains("PBR저평가");
        // 매칭 태그 없는 factor 는 base note 그대로
        assertThat(factorNote(result, "earnings")).doesNotContain("근거:");
    }

    @Test
    @DisplayName("근거: 태그 없으면 base note 그대로 (NPE/포맷 깨짐 없음)")
    void factorEvidence_noTags() {
        RecommendationSnapshot s = snapshot(60, 12, 10, 10, 10, 8);
        s.setTags(null);
        when(snapshotRepository.findLatestByStockCode(anyString())).thenReturn(Optional.of(s));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(factorNote(result, "supplyDemand")).isEqualTo("외국인/기관 순매수 추세");
    }

    @Test
    @DisplayName("근거: 카테고리당 최대 3개 태그까지만")
    void factorEvidence_capsAtThree() {
        String tags = "외국인3일연속,기관5일연속,외국인순매수200억,기관순매수,수급괴리";
        String note = StockConclusionService.withEvidence("base", tags, "supplyDemand");

        // "근거: " 뒤 콤마 구분 3개
        assertThat(note).startsWith("base · 근거: ");
        assertThat(note.substring(note.indexOf("근거: ")).split(",")).hasSize(3);
    }

    private String factorNote(StockConclusionDto dto, String key) {
        return dto.getFactors().stream()
                .filter(f -> f.getKey().equals(key))
                .findFirst().orElseThrow()
                .getNote();
    }

    @Test
    @DisplayName("verdictFor — NA(-1)는 N/A (기존 NEGATIVE 오판 버그 수정), 경계값 정상")
    void verdictFor_naGuard() {
        assertThat(StockConclusionService.verdictFor(-1, 8, 12)).isEqualTo("N/A");      // 버그: 기존엔 NEGATIVE
        assertThat(StockConclusionService.verdictFor(0, 8, 12)).isEqualTo("NEGATIVE");
        assertThat(StockConclusionService.verdictFor(8, 8, 12)).isEqualTo("NEUTRAL");
        assertThat(StockConclusionService.verdictFor(12, 8, 12)).isEqualTo("POSITIVE");
    }

    @Test
    @DisplayName("growth -1(NA) → 성장성 factor 숨김(6개), NEGATIVE 오표시 안 함")
    void growthNa_factorHidden() {
        RecommendationSnapshot s = snapshot(80, 16, 16, 15, 14, 10);
        s.setGrowth(-1);   // NA(데이터 없음)
        when(snapshotRepository.findLatestByStockCode(anyString())).thenReturn(Optional.of(s));

        StockConclusionDto result = service.getConclusion("005930");

        assertThat(result.getFactors()).hasSize(6);   // 성장성 빠짐(정상 7 → NA 숨김 6)
        assertThat(result.getFactors()).noneMatch(f -> "growth".equals(f.getKey()));
    }
}
