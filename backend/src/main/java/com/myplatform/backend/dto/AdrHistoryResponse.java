package com.myplatform.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ADR 히스토리 조회 응답 (success/data/count/message 봉투 포함)
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdrHistoryResponse {

    private boolean success;
    private List<MarketTimingDto.AdrHistoryDto> data;
    private Integer count;
    private String message;
}
