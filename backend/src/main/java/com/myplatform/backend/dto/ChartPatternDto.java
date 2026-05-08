package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 차트 패턴 검출 결과.
 *
 * 사용자 참고용 인디케이터로만 사용 — 자동매매 신호로는 사용하지 않음.
 * 패턴 검출은 본질적으로 노이즈가 있으므로 confidence 와 함께 표시.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChartPatternDto {

    /** 패턴 종류 (DOUBLE_TOP, DOUBLE_BOTTOM, HEAD_AND_SHOULDERS, INVERSE_HEAD_AND_SHOULDERS,
     *  TRIANGLE_SYMMETRIC, TRIANGLE_ASCENDING, TRIANGLE_DESCENDING) */
    private String type;

    /** 한국어 표시명 (예: "더블탑 의심", "헤드앤숄더") */
    private String label;

    /** 신뢰도: HIGH / MEDIUM / LOW */
    private String confidence;

    /** 사용자 친화 설명 1-2문장 */
    private String description;

    /** 검출된 신호 (예: BEARISH / BULLISH / NEUTRAL) */
    private String signal;

    /** 패턴 시작 일자 */
    private LocalDate startDate;
    /** 패턴 종료 일자 (가장 최근) */
    private LocalDate endDate;

    /** 핵심 가격 포인트들 (peaks/troughs/neckline 등 패턴별로 다름) */
    private List<KeyPoint> keyPoints;

    /** 넥라인 / 지지선 / 저항선 가격 (패턴 따라 의미 다름) */
    private BigDecimal referencePrice;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class KeyPoint {
        private LocalDate date;
        private BigDecimal price;
        /** "LEFT_SHOULDER" / "HEAD" / "RIGHT_SHOULDER" / "PEAK_1" / "PEAK_2" / "TROUGH_1" 등 */
        private String role;
    }
}
