package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 종합 신호 평가 — 5개 신호의 OR 합. 의사결정 단순화용.
 *
 * 신호:
 *  1. PATTERN: 차트 패턴 BULLISH 검출
 *  2. SUPPORT: 강한 지지선 근처 (현재가 -5% 이내)
 *  3. VALUE_AREA: 현재가 ≤ Volume Profile VAL (저평가 영역)
 *  4. SUPPLY: 외국인/기관 순매수 (수급 양호)
 *  5. AI_RECOMMEND: AI 종합 추천 점수 60+ 또는 TOP 리스트 포함
 *
 * 사용자 참고용 — 자동매매 신호로 사용 X.
 * 단일 신호 X (40% 틀림). 3-4개 동시 충족 시 적중률 살짝 ↑.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompositeSignalDto {

    private String stockCode;
    private String stockName;

    /** 매칭된 신호 수 (0~5) */
    private int matchedCount;
    /** 평가된 총 신호 수 (보통 5, 데이터 없는 신호는 평가 제외 가능) */
    private int totalCount;

    /** 5개 신호 각각의 평가 결과 */
    private List<Signal> signals;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Signal {
        /** PATTERN / SUPPORT / VALUE_AREA / SUPPLY / AI_RECOMMEND */
        private String id;
        /** 한국어 표시명 */
        private String label;
        /** 매칭 여부 */
        private boolean matched;
        /** 매칭 시 상세 설명 (예: "더블바텀 의심", "지지선 67,500원 (-2.1%)") */
        private String detail;
    }
}
