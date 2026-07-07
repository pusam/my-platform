package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 수동 매수 의사결정 체크리스트.
 *
 * 자동매매 봇이 적용하는 hard rule (공매도/섹터 OUTFLOW/연속매수/RSI 등)을 사용자에게도 노출.
 * 5개 중 N개 충족 비율로 권고 단계 결정.
 *
 * UI: 종목 상세 페이지의 매수 버튼 클릭 직전 모달.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyChecklistDto {

    private String stockCode;
    private String stockName;
    private List<ChecklistItem> items;
    private int passedCount;
    private int totalCount;
    private Recommendation recommendation;
    private String summary;

    public enum Recommendation {
        STRONG,          // 5/5 — 진입 권장
        MODERATE,        // 4/5 — 양호
        CAUTION,         // 3/5 — 신중 진입
        NOT_RECOMMENDED  // < 3/5 — 진입 비권장
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChecklistItem {
        /** 식별자 — tradable / shortSelling / consecutiveBuy / compositeSignal / conclusion. */
        private String key;
        /** UI 라벨. */
        private String label;
        /** 충족 여부. */
        private boolean passed;
        /** 표시할 값 ("3.2%", "신호 4/5", "STRONG_BUY" 등). */
        private String value;
        /** 기준 ("< 5%", "≥ 3/5" 등). */
        private String threshold;
        /** 설명 — 미충족 시 가이드. */
        private String note;
        /** 시간 척도 — SHORT / MID / LONG / META. UI 태그용. */
        private String dimension;
        /**
         * 데이터 미수집 여부(§4c). true 면 passed=false 라도 "미충족"이 아니라 "판정 불가" —
         * 필수 항목 판정(decideRecommendation)에서 제외된다(결측 근거 차단 금지).
         */
        @Builder.Default
        private boolean dataMissing = false;
    }
}
