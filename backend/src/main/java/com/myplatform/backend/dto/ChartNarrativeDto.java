package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 차트 해설 — 지표 사실을 문장으로 엮은 관찰용 해설.
 *
 * <p>⚠ <b>매수 신호가 아니다.</b> 판정({@link #verdict})은 점수/추천/봇 어디에도 편입하지 않으며
 * 화면에도 관찰용으로만 노출한다({@link #unverified} = true). 산식은 {@code ChartNarrativeBuilder}.
 *
 * <p>근거가 없는 섹션은 담기지 않는다(§4c — 빈 문장으로 채우지 않음).
 * 일봉이 부족하면 verdict=UNKNOWN(판단보류) + sections 비어 있음.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChartNarrativeDto {

    private String stockCode;

    /** 해설 섹션 — 지금 위치 / 반등의 성격 / 위쪽 저항 / 최근 캔들 (있는 것만). */
    private List<Section> sections;

    /** 결론 코드 — UNKNOWN / WAIT / OVERHEATED / WATCH. */
    private String verdict;
    /** 결론 표시 라벨 — 판단보류 / 관망 / 과열 경계 / 조건부 관심. */
    private String verdictLabel;
    /** 결론 한 줄 근거. */
    private String verdictReason;

    /** 검증 전 보조 시그널 표식 — 화면에서 '관찰용' 문구를 붙이는 근거. 항상 true. */
    @Builder.Default
    private boolean unverified = true;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Section {
        private String title;
        private List<String> lines;
    }
}
