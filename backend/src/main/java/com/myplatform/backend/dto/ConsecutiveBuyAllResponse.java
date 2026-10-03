package com.myplatform.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 전체 투자자 연속 매수 종목 조회 응답
 * 프론트엔드에서 data.FOREIGN, data.INSTITUTION, data.dataStatus 로 접근
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConsecutiveBuyAllResponse {

    @JsonProperty("FOREIGN")
    private List<ConsecutiveBuyDto> foreign;

    @JsonProperty("INSTITUTION")
    private List<ConsecutiveBuyDto> institution;

    /** 연기금 — 서비스는 계산하는데 응답에 칸이 없어 화면 '연기금' 탭이 늘 비었다(2026-10-03). */
    @JsonProperty("PENSION")
    private List<ConsecutiveBuyDto> pension;

    private InvestorDataStatusDto dataStatus;
}
