package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 종목별 재료(catalyst) 이력 (stock_catalyst 최근 N일 재사용) — <b>표시 전용, 산식 미편입</b>.
 *
 * <p>종목 상세 "📰 재료 이력" 섹션 입력. 이 종목에 과거 어떤 재료(수주/실적/M&amp;A/… + 호재/악재/중립)가
 * 언제 포착됐는지 + 그 날짜의 일봉 등락률을 타임라인으로 보여준다.
 *
 * <p><b>§4b</b>: stock_catalyst 일캐시를 <b>read-only 로만 소비</b>(신규 Gemini classify 호출 없음).
 * "재료 없음"(type=NONE) 행은 타임라인에서 제외 — 실제 포착된 재료만 이력으로 표시.
 * <p><b>§4c</b>: 해당 날짜의 가격 히스토리가 없으면 changeRate=null(0 등 그럴듯한 값으로 위장 금지).
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CatalystHistoryDto {

    private String stockCode;
    /** 집계 윈도우(일) — 30. */
    private int windowDays;
    /** 최신순 이력 (type=NONE 제외). 비어 있으면 프론트는 섹션 자체 미렌더. */
    private List<Item> items;

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Item {
        private LocalDate catalystDate;
        /** ORDER_WIN/EARNINGS/MNA/NEW_BUSINESS/REGULATION/LITIGATION/GOVERNANCE/OTHER. */
        private String catalystType;
        /** 한국어 라벨 — 수주/실적/M&A/신사업/규제/소송/지배구조/기타. */
        private String typeLabel;
        /** POSITIVE/NEGATIVE/NEUTRAL. */
        private String direction;
        private String headline;
        private String summary;
        /** 그 날짜의 일봉 등락률(%) — 가격 히스토리 없으면 null(§4c). */
        private BigDecimal changeRate;
    }
}
