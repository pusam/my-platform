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
 */
class SignalHistoryServiceTest {

    private static final LocalDate BASE = LocalDate.of(2026, 7, 1);

    private SignalOutcome evaluated(LocalDate date, String type, Integer score, Boolean hit, String alpha, String pct) {
        return SignalOutcome.builder()
                .stockCode("005930").stockName("삼성전자")
                .signalDate(date).signalType(type).signalScore(score)
                .hit(hit)
                .alpha3d(alpha == null ? null : new BigDecimal(alpha))
                .pctChange3d(pct == null ? null : new BigDecimal(pct))
                .evaluatedAt(LocalDateTime.of(date.plusDays(3), java.time.LocalTime.of(19, 30)))
                .build();
    }

    private SignalOutcome pending(LocalDate date, String type, Integer score) {
        return SignalOutcome.builder()
                .stockCode("005930").signalDate(date).signalType(type).signalScore(score)
                .build();   // evaluatedAt/hit/alpha 미기록 = 3거래일 미도래
    }

    @Test
    @DisplayName("평가 3건(적중 2) + 대기 1건 → 요약 2/3·평균 alpha, pending 은 hit=null 로 분리(§4c)")
    void assemble_summaryAndPendingSeparated() {
        List<SignalOutcome> rows = List.of(
                pending(BASE.plusDays(5), "BUY", 60),
                evaluated(BASE.plusDays(2), "STRONG_BUY", 78, true, "2.40", "3.10"),
                evaluated(BASE.plusDays(1), "BUY", 58, false, "-1.20", "-0.50"),
                evaluated(BASE, "BUY", 62, true, "1.80", "2.00"));

        SignalHistoryDto dto = SignalHistoryService.assemble("005930", rows);

        assertThat(dto.getStockCode()).isEqualTo("005930");
        assertThat(dto.getWindowDays()).isEqualTo(90);
        assertThat(dto.getItems()).hasSize(4);

        SignalHistoryDto.Summary sum = dto.getSummary();
        assertThat(sum.getEvaluatedCount()).isEqualTo(3);
        assertThat(sum.getHitCount()).isEqualTo(2);
        assertThat(sum.getPendingCount()).isEqualTo(1);
        // 평균 alpha = (2.40 - 1.20 + 1.80) / 3 = 1.00
        assertThat(sum.getAvgAlpha()).isEqualByComparingTo("1.00");

        // pending 행 — hit=null(미스 아님) + pending=true
        SignalHistoryDto.Item p = dto.getItems().get(0);
        assertThat(p.isPending()).isTrue();
        assertThat(p.getHit()).isNull();
        // 평가 행 — pending=false + hit 그대로
        SignalHistoryDto.Item e = dto.getItems().get(1);
        assertThat(e.isPending()).isFalse();
        assertThat(e.getHit()).isTrue();
        assertThat(e.getAlpha3d()).isEqualByComparingTo("2.40");
    }

    @Test
    @DisplayName("alpha 전부 미산출이면 avgAlpha=null (0 으로 위장 금지, §4c)")
    void assemble_avgAlphaNullWhenNoAlpha() {
        List<SignalOutcome> rows = List.of(
                evaluated(BASE, "BUY", 60, true, null, "3.50"),   // alpha 폴백 hit(pct≥3%) 케이스
                evaluated(BASE.plusDays(1), "BUY", 61, false, null, "0.10"));

        SignalHistoryDto dto = SignalHistoryService.assemble("005930", rows);

        assertThat(dto.getSummary().getEvaluatedCount()).isEqualTo(2);
        assertThat(dto.getSummary().getHitCount()).isEqualTo(1);
        assertThat(dto.getSummary().getAvgAlpha()).isNull();
    }

    @Test
    @DisplayName("이력 없음 → items 빈 리스트 + 요약 0 (프론트는 섹션 자체 미렌더)")
    void assemble_emptyAndNullSafe() {
        SignalHistoryDto empty = SignalHistoryService.assemble("005930", List.of());
        assertThat(empty.getItems()).isEmpty();
        assertThat(empty.getSummary().getEvaluatedCount()).isZero();
        assertThat(empty.getSummary().getPendingCount()).isZero();
        assertThat(empty.getSummary().getAvgAlpha()).isNull();

        SignalHistoryDto nul = SignalHistoryService.assemble("005930", null);
        assertThat(nul.getItems()).isEmpty();
    }
}
