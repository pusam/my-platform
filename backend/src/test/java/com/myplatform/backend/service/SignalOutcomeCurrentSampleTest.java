package com.myplatform.backend.service;

import com.myplatform.backend.dto.SignalBandAccuracyDto;
import com.myplatform.backend.entity.SignalOutcome;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.StockCatalystRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 화면의 "실측 적중률"(오늘 탭 신뢰도 띠·결론 카드 점수대)은 현재 산식 표본만 읽는다(2026-10-01 감사).
 *
 * <p>운영 실측: 6/25 이후 보드 신호 208건이 전부 표본 경계 이전이었고, 이 화면들은 그 옛 산식 성적을 레거시 평가값
 * (배치 시점 가격 — V59 문서가 "방향조차 보장 못 하는 참고치"라 한 값)으로 "실측"이라 보여줬다. 관제실은 같은 표본을
 * "이전 산식 참고"로 따로 놓는데 매수 후보 옆 카드는 현재 점수의 실측처럼 보였다.
 *
 * <p>규칙: ① 표본 시작일 이후 기록만 ② 교정 D+3 값(d3_*)만 ③ 같은 종목·날짜는 최초 기록 1건 ④ 시작일이 미정이면
 * 옛 값으로 채우지 않고 비운다(화면은 '검증 중').
 */
class SignalOutcomeCurrentSampleTest {

    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }

    private static ObjectProvider<SignalSampleBoundary> boundary(String since, boolean confirmed) {
        ObjectProvider<SignalSampleBoundary> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(new SignalSampleBoundary(since, confirmed));
        return p;
    }

    static SignalOutcomeService service(SignalOutcomeRepository repo, ObjectProvider<SignalSampleBoundary> b) {
        return new SignalOutcomeService(repo, mock(StockPriceService.class), mock(StockCatalystRepository.class),
                provider(), provider(), provider(), provider(), provider(), provider(),
                provider(), provider(), provider(), provider(), provider(), b);
    }

    /** 레거시 평가와 교정 평가가 둘 다 있는 행 — 두 정의가 엇갈리게 만들어 어느 쪽을 읽는지 드러낸다. */
    static SignalOutcome row(long id, String type, String code, LocalDate date, int score,
                             boolean legacyHit, Boolean d3Hit, String d3Pct, LocalDateTime createdAt) {
        SignalOutcome s = SignalOutcome.builder()
                .id(id).signalType(type).stockCode(code).stockName(code).signalDate(date).signalScore(score)
                .priceAtSignal(new BigDecimal("10000"))
                .hit(legacyHit).pctChange3d(new BigDecimal(legacyHit ? "6.00" : "-6.00"))
                .alpha3d(new BigDecimal(legacyHit ? "4.00" : "-4.00"))
                .evaluatedAt(date.plusDays(3).atTime(19, 30))
                .d3Status("OK").d3Hit(d3Hit).d3PctChange(new BigDecimal(d3Pct))
                .d3Alpha(new BigDecimal(Boolean.TRUE.equals(d3Hit) ? "0.50" : "-0.50"))
                .d3MfePct(new BigDecimal("2.00")).d3MaePct(new BigDecimal("-3.00"))
                .d3EvaluatedAt(date.plusDays(3).atTime(19, 45))
                .build();
        s.setCreatedAt(createdAt);
        return s;
    }

    private static SignalBandAccuracyDto.BandStat band6574(SignalBandAccuracyDto dto) {
        return dto.getBands().stream().filter(b -> b.getScoreFrom() == 65).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("표본 시작일 미정이면 옛 평가값으로 채우지 않는다 — 밴드가 비고 시작일도 없다")
    void unsetBoundaryShowsNothing() {
        LocalDate d = LocalDate.now().minusDays(10);
        SignalOutcome legacy = row(1, "BUY", "A", d, 70, true, true, "3.00", d.atTime(11, 30));
        SignalOutcomeRepository repo = mock(SignalOutcomeRepository.class);
        when(repo.findEvaluatedSince(any())).thenReturn(List.of(legacy));
        when(repo.findD3OkSince(any(), anyString())).thenReturn(List.of(legacy));

        SignalBandAccuracyDto dto = service(repo, boundary("", false)).getAccuracyByBand(90);

        assertThat(dto.getSince()).isNull();
        assertThat(dto.getBands()).allSatisfy(b -> assertThat(b.getTotalSignals()).isZero());
    }

    @Test
    @DisplayName("경계 이후·교정 D+3·최초 기록 1건 — 레거시 적중이어도 교정 미적중이면 미적중")
    void currentSampleUsesCorrectedValuesAfterBoundary() {
        LocalDate b = LocalDate.now().minusDays(20);
        SignalOutcome beforeBoundary = row(1, "BUY", "A", b.minusDays(1), 70, true, true, "5.00", b.minusDays(1).atTime(11, 30));
        SignalOutcome legacyHitD3Miss = row(2, "BUY", "B", b.plusDays(1), 70, true, false, "-2.00", b.plusDays(1).atTime(11, 30));
        SignalOutcome legacyMissD3Hit = row(3, "BUY", "C", b.plusDays(2), 70, false, true, "3.00", b.plusDays(2).atTime(11, 30));
        SignalOutcome upgradeSameDay = row(4, "STRONG_BUY", "C", b.plusDays(2), 72, true, true, "3.00", b.plusDays(2).atTime(14, 0));
        List<SignalOutcome> all = List.of(beforeBoundary, legacyHitD3Miss, legacyMissD3Hit, upgradeSameDay);
        SignalOutcomeRepository repo = mock(SignalOutcomeRepository.class);
        when(repo.findEvaluatedSince(any())).thenReturn(all);
        when(repo.findD3OkSince(any(), anyString())).thenReturn(all);

        SignalBandAccuracyDto dto = service(repo, boundary(b.toString(), false)).getAccuracyByBand(90);

        SignalBandAccuracyDto.BandStat band = band6574(dto);
        assertThat(band.getTotalSignals()).as("경계 이전 1건 제외, 같은 날 승격은 최초 기록 1건").isEqualTo(2);
        assertThat(band.getHitCount()).as("교정 D+3 기준 — 레거시 적중을 읽으면 1이 아니다").isEqualTo(1);
        assertThat(band.getAvgPctChange()).isEqualByComparingTo("0.50");   // (-2 + 3) / 2
        assertThat(dto.getSince()).isEqualTo(b);
        // 결론 카드 첫 줄 — 등급별(최초 기록 기준이라 승격 행은 BUY 로 남는다)
        assertThat(dto.getSampleStatus()).isEqualTo("PROVISIONAL");
        assertThat(dto.getSampleSince()).isEqualTo(b);
        assertThat(dto.getBasis()).isEqualTo("D3_CORRECTED");
        assertThat(dto.getEvaluatedCount()).isEqualTo(2);
        assertThat(dto.getTypeStats()).singleElement().satisfies(t -> {
            assertThat(t.getSignalType()).isEqualTo("BUY");
            assertThat(t.getTotalSignals()).isEqualTo(2);
            assertThat(t.getHitRate()).isEqualByComparingTo("50.00");
        });
    }

    @Test
    @DisplayName("미정이면 상태 UNSET·등급별 성적 없음·표본 0 — 화면이 '검증 중'으로 읽을 수 있다")
    void unsetBoundaryReportsStatus() {
        SignalOutcomeRepository repo = mock(SignalOutcomeRepository.class);
        SignalBandAccuracyDto dto = service(repo, boundary("", false)).getAccuracyByBand(90);

        assertThat(dto.getSampleStatus()).isEqualTo("UNSET");
        assertThat(dto.getSampleSince()).isNull();
        assertThat(dto.getTypeStats()).isEmpty();
        assertThat(dto.getEvaluatedCount()).isZero();
    }

    @Test
    @DisplayName("표본 시작 = 경계·최근 N일·phase-38 컷오프 중 가장 늦은 날")
    void sampleFromIsLatestOfBoundaryWindowCutoff() {
        LocalDate today = LocalDate.of(2026, 12, 1);
        assertThat(SignalOutcomeService.currentSampleFrom(LocalDate.of(2026, 10, 5), 90, today))
                .isEqualTo(LocalDate.of(2026, 10, 5));                           // 경계가 더 늦다
        assertThat(SignalOutcomeService.currentSampleFrom(LocalDate.of(2026, 7, 1), 90, today))
                .isEqualTo(today.minusDays(90));                                   // 창이 더 늦다
        assertThat(SignalOutcomeService.currentSampleFrom(LocalDate.of(2026, 1, 1), 0, today))
                .isEqualTo(SignalOutcomeService.PHASE38_CUTOFF);                   // 컷오프로 클램프
    }

    @Test
    @DisplayName("교정값 사본은 id 가 없다 — 실수로 저장돼도 원본(레거시) 행을 덮어쓰지 못한다")
    void correctedViewNeverOverwritesOriginal() {
        LocalDate d = LocalDate.now().minusDays(5);
        SignalOutcome original = row(42, "BUY", "A", d, 70, true, false, "-2.00", d.atTime(11, 30));

        SignalOutcome view = SignalOutcomeService.correctedView(original);

        assertThat(view.getId()).isNull();
        assertThat(view.getHit()).isFalse();
        assertThat(view.getPctChange3d()).isEqualByComparingTo("-2.00");
        assertThat(original.getHit()).as("원본 레거시 값은 그대로").isTrue();
        assertThat(original.getId()).isEqualTo(42L);
    }
}
