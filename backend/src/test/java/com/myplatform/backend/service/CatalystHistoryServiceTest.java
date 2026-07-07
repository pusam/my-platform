package com.myplatform.backend.service;

import com.myplatform.backend.dto.CatalystHistoryDto;
import com.myplatform.backend.entity.StockCatalyst;
import com.myplatform.backend.entity.StockCatalyst.CatalystType;
import com.myplatform.backend.entity.StockCatalyst.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재료 이력 조립 순수 함수 테스트 — stock_catalyst 재사용(read-only), 산식 미편입.
 * 핵심: type=NONE(재료 없음) 제외, 날짜별 등락률 병기(없으면 null — §4c 위장 금지).
 */
class CatalystHistoryServiceTest {

    private static final LocalDate BASE = LocalDate.of(2026, 7, 1);

    private StockCatalyst catalyst(LocalDate date, CatalystType type, Direction dir, String headline, String summary) {
        return StockCatalyst.builder()
                .stockCode("005930").stockName("삼성전자")
                .catalystDate(date).catalystType(type).direction(dir)
                .headline(headline).summary(summary)
                .build();
    }

    @Test
    @DisplayName("호재/악재/중립 이력 + 날짜별 등락률 병기, NONE 제외")
    void assemble_attachesChangeRateAndExcludesNone() {
        List<StockCatalyst> rows = List.of(
                catalyst(BASE.plusDays(2), CatalystType.ORDER_WIN, Direction.POSITIVE, "대규모 수주", "5000억 공급계약"),
                catalyst(BASE.plusDays(1), CatalystType.NONE, Direction.NONE, null, null),   // 제외 대상
                catalyst(BASE, CatalystType.LITIGATION, Direction.NEGATIVE, "특허 소송", "피소"));

        Map<LocalDate, BigDecimal> changeByDate = new HashMap<>();
        changeByDate.put(BASE.plusDays(2), new BigDecimal("4.20"));
        // BASE 날짜는 가격 히스토리 없음 → changeRate null 이어야 함

        CatalystHistoryDto dto = CatalystHistoryService.assemble("005930", rows, changeByDate);

        assertThat(dto.getStockCode()).isEqualTo("005930");
        assertThat(dto.getWindowDays()).isEqualTo(30);
        // NONE 제외 → 2건, 최신순 유지
        assertThat(dto.getItems()).hasSize(2);

        CatalystHistoryDto.Item first = dto.getItems().get(0);
        assertThat(first.getCatalystType()).isEqualTo("ORDER_WIN");
        assertThat(first.getTypeLabel()).isEqualTo("수주");
        assertThat(first.getDirection()).isEqualTo("POSITIVE");
        assertThat(first.getChangeRate()).isEqualByComparingTo("4.20");

        CatalystHistoryDto.Item second = dto.getItems().get(1);
        assertThat(second.getCatalystType()).isEqualTo("LITIGATION");
        assertThat(second.getDirection()).isEqualTo("NEGATIVE");
        // 가격 히스토리 없는 날 → null (0 위장 금지)
        assertThat(second.getChangeRate()).isNull();
    }

    @Test
    @DisplayName("전부 NONE 이면 items 빈 리스트 (프론트 섹션 미렌더)")
    void assemble_allNoneEmpty() {
        List<StockCatalyst> rows = List.of(
                catalyst(BASE, CatalystType.NONE, Direction.NONE, null, null),
                catalyst(BASE.minusDays(1), CatalystType.NONE, Direction.NONE, null, null));

        CatalystHistoryDto dto = CatalystHistoryService.assemble("005930", rows, Map.of());
        assertThat(dto.getItems()).isEmpty();
    }

    @Test
    @DisplayName("이력 없음/null 안전 — 빈 리스트")
    void assemble_nullSafe() {
        assertThat(CatalystHistoryService.assemble("005930", List.of(), null).getItems()).isEmpty();
        assertThat(CatalystHistoryService.assemble("005930", null, null).getItems()).isEmpty();
    }
}
