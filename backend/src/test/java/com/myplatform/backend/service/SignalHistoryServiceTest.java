package com.myplatform.backend.service;

import com.myplatform.backend.dto.SignalHistoryDto;
import com.myplatform.backend.entity.SignalOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 신호 이력 조립 순수 함수 테스트 — signal_outcome 재사용(read-only), 산식 미편입.
 * §4c 핵심: 평가 전(pending) 행은 hit=null + pending=true 로 구분(미스 위장 금지), 요약 집계 제외.
 *
 * <p>2026-10-01: 추천 신호만 · 교정 D+3 값 · 현재 산식 표본(경계 이후)만 요약. 경계 이전 행은 목록에 "이전 산식"으로만.
 */
class SignalHistoryServiceTest {

    private static final LocalDate BASE = LocalDate.of(2026, 10, 5);
    /** 표본 시작일 = BASE (잠정). */
    private static final SignalSampleBoundary.Boundary SAMPLE =
            new SignalSampleBoundary.Boundary(BASE, SignalSampleBoundary.Status.PROVISIONAL);

    /** 교정 평가가 끝난 행 — 레거시 값은 일부러 반대로 둬서 어느 쪽을 읽는지 드러낸다. */
    private SignalOutcome evaluated(LocalDate date, String type, Integer score, Boolean hit, String alpha, String pct) {
        SignalOutcome s = SignalOutcome.builder()
                .stockCode("005930").stockName("삼성전자")
                .signalDate(date).signalType(type).signalScore(score)
                .hit(hit == null ? null : !hit)
                .d3Status("OK").d3Hit(hit)
                .d3Alpha(alpha == null ? null : new BigDecimal(alpha))
                .d3PctChange(pct == null ? null : new BigDecimal(pct))
                .evaluatedAt(LocalDateTime.of(date.plusDays(3), java.time.LocalTime.of(19, 30)))
                .d3EvaluatedAt(LocalDateTime.of(date.plusDays(3), java.time.LocalTime.of(19, 45)))
                .build();
        s.setCreatedAt(date.atTime(11, 30));
        return s;
    }

    private SignalOutcome pending(LocalDate date, String type, Integer score) {
        return SignalOutcome.builder()
                .stockCode("005930").signalDate(date).signalType(type).signalScore(score)
                .build();   // 교정 평가 전 = 3거래일 미도래
    }

    @Test
    @DisplayName("무작위 대조군·급등 감지·AI 행은 이 종목의 추천 성적이 아니다 — 요약에 합치지 않는다(2026-10-01 감사)")
    void assemble_excludesControlAndOtherEnginesFromRecommendationRecord() {
        // 운영 실측: 90일 평가 3,723행 중 보드 신호 157 · 대조군 35 · 급등 감지 3,510 — 삼성SDI 는 추천 0건인데
        // 무작위 대조군 3건 중 1건이 '적중'으로 집계됐다.
        List<SignalOutcome> rows = List.of(
                evaluated(BASE, "BUY", 62, false, "-1.00", "-2.00"),
                evaluated(BASE.plusDays(1), "CONTROL_RANDOM", null, true, "2.00", "3.00"),
                evaluated(BASE.plusDays(2), "SURGE_HOT", null, true, "1.50", "2.50"),
                evaluated(BASE.plusDays(3), "AI_BUY", 80, true, "1.00", "2.00"));

        SignalHistoryDto dto = SignalHistoryService.assemble("005930", rows, SAMPLE);

        assertThat(dto.getSummary().getEvaluatedCount()).isEqualTo(1);
        assertThat(dto.getSummary().getHitCount()).isZero();
        assertThat(dto.getItems()).extracting(SignalHistoryDto.Item::getSignalType).containsExactly("BUY");
    }

    @Test
    @DisplayName("교정 평가 3건(적중 2) + 대기 1건 → 요약 2/3·평균 α, pending 은 hit=null 로 분리(§4c)")
    void assemble_summaryAndPendingSeparated() {
        List<SignalOutcome> rows = List.of(
                pending(BASE.plusDays(5), "BUY", 60),
                evaluated(BASE.plusDays(2), "STRONG_BUY", 78, true, "2.40", "3.10"),
                evaluated(BASE.plusDays(1), "BUY", 58, false, "-1.20", "-0.50"),
                evaluated(BASE, "BUY", 62, true, "1.80", "2.00"));

        SignalHistoryDto dto = SignalHistoryService.assemble("005930", rows, SAMPLE);

        assertThat(dto.getStockCode()).isEqualTo("005930");
        assertThat(dto.getWindowDays()).isEqualTo(90);
        assertThat(dto.getItems()).hasSize(4);

        SignalHistoryDto.Summary sum = dto.getSummary();
        assertThat(sum.getEvaluatedCount()).isEqualTo(3);
        assertThat(sum.getHitCount()).as("교정 D+3 기준 — 레거시 hit 는 반대로 넣어 두었다").isEqualTo(2);
        assertThat(sum.getPendingCount()).isEqualTo(1);
        // 평균 α = (2.40 - 1.20 + 1.80) / 3 = 1.00
        assertThat(sum.getAvgAlpha()).isEqualByComparingTo("1.00");
        assertThat(sum.getSampleSince()).isEqualTo(BASE);
        assertThat(sum.getSampleStatus()).isEqualTo("PROVISIONAL");

        // 최신순 — pending 행: hit=null(미스 아님) + pending=true
        SignalHistoryDto.Item p = dto.getItems().get(0);
        assertThat(p.isPending()).isTrue();
        assertThat(p.getHit()).isNull();
        SignalHistoryDto.Item e = dto.getItems().get(1);
        assertThat(e.isPending()).isFalse();
        assertThat(e.getHit()).isTrue();
        assertThat(e.getAlpha3d()).isEqualByComparingTo("2.40");
        assertThat(e.isInCurrentSample()).isTrue();
    }

    @Test
    @DisplayName("경계 이전 행은 목록에 '이전 산식'으로만 — 요약에 안 들어간다")
    void assemble_preBoundaryRowsListedButNotCounted() {
        List<SignalOutcome> rows = List.of(
                evaluated(BASE.minusDays(3), "BUY", 70, true, "3.00", "4.00"),
                evaluated(BASE.plusDays(1), "BUY", 60, false, "-1.00", "-2.00"));

        SignalHistoryDto dto = SignalHistoryService.assemble("005930", rows, SAMPLE);

        assertThat(dto.getSummary().getEvaluatedCount()).isEqualTo(1);
        assertThat(dto.getSummary().getHitCount()).isZero();
        assertThat(dto.getItems()).hasSize(2);
        assertThat(dto.getItems().get(1).isInCurrentSample()).isFalse();
        assertThat(dto.getItems().get(1).getHit()).as("이전 산식 행도 교정 결과는 보인다").isTrue();
    }

    @Test
    @DisplayName("표본 시작일 미정이면 요약은 비고(검증 중) 목록만 남는다 — 옛 값으로 채우지 않는다")
    void assemble_unsetBoundaryLeavesSummaryEmpty() {
        List<SignalOutcome> rows = List.of(evaluated(BASE, "BUY", 70, true, "3.00", "4.00"));

        SignalHistoryDto dto = SignalHistoryService.assemble("005930", rows, SignalSampleBoundary.Boundary.UNSET);

        assertThat(dto.getSummary().getEvaluatedCount()).isZero();
        assertThat(dto.getSummary().getSampleStatus()).isEqualTo("UNSET");
        assertThat(dto.getSummary().getSampleSince()).isNull();
        assertThat(dto.getItems()).hasSize(1);
    }

    @Test
    @DisplayName("같은 날 BUY→STRONG_BUY 승격은 최초 기록 1건 — 측정 규칙과 같다")
    void assemble_sameDayUpgradeCountedOnce() {
        SignalOutcome buy = evaluated(BASE, "BUY", 62, true, "1.00", "2.00");
        SignalOutcome strong = evaluated(BASE, "STRONG_BUY", 76, true, "1.00", "2.00");
        strong.setCreatedAt(BASE.atTime(14, 0));

        SignalHistoryDto dto = SignalHistoryService.assemble("005930", List.of(strong, buy), SAMPLE);

        assertThat(dto.getSummary().getEvaluatedCount()).isEqualTo(1);
        assertThat(dto.getItems()).extracting(SignalHistoryDto.Item::getSignalType).containsExactly("BUY");
    }

    @Test
    @DisplayName("교정 평가에서 빠진 행(거래정지 등)은 미스로 세지 않고 사유를 남긴다")
    void assemble_excludedRowKeepsReason() {
        SignalOutcome halted = SignalOutcome.builder()
                .stockCode("005930").signalDate(BASE).signalType("BUY").signalScore(60)
                .d3Status("HALTED_IN_WINDOW").build();

        SignalHistoryDto dto = SignalHistoryService.assemble("005930", List.of(halted), SAMPLE);

        assertThat(dto.getSummary().getEvaluatedCount()).isZero();
        assertThat(dto.getSummary().getPendingCount()).isZero();
        assertThat(dto.getItems().get(0).getHit()).isNull();
        assertThat(dto.getItems().get(0).getExcludedReason()).isEqualTo("HALTED_IN_WINDOW");
    }

    @Test
    @DisplayName("α 전부 미산출이면 avgAlpha=null (0 으로 위장 금지, §4c)")
    void assemble_avgAlphaNullWhenNoAlpha() {
        List<SignalOutcome> rows = List.of(
                evaluated(BASE, "BUY", 60, true, null, "3.50"),
                evaluated(BASE.plusDays(1), "BUY", 61, false, null, "0.10"));

        SignalHistoryDto dto = SignalHistoryService.assemble("005930", rows, SAMPLE);

        assertThat(dto.getSummary().getEvaluatedCount()).isEqualTo(2);
        assertThat(dto.getSummary().getHitCount()).isEqualTo(1);
        assertThat(dto.getSummary().getAvgAlpha()).isNull();
    }

    @Test
    @DisplayName("이력 없음 → items 빈 리스트 + 요약 0 (프론트는 섹션 자체 미렌더)")
    void assemble_emptyAndNullSafe() {
        SignalHistoryDto empty = SignalHistoryService.assemble("005930", List.of(), SAMPLE);
        assertThat(empty.getItems()).isEmpty();
        assertThat(empty.getSummary().getEvaluatedCount()).isZero();
        assertThat(empty.getSummary().getPendingCount()).isZero();
        assertThat(empty.getSummary().getAvgAlpha()).isNull();

        SignalHistoryDto nul = SignalHistoryService.assemble("005930", null, null);
        assertThat(nul.getItems()).isEmpty();
    }
}
