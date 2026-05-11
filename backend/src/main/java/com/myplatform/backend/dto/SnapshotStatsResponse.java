package com.myplatform.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 전략 스냅샷 통계 (디버깅용)
 * — countByType: StrategyType.name() → count
 * — *_lastUpdated: 전략별 마지막 수집 시각 (없으면 null)
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SnapshotStatsResponse {

    private Map<String, Long> countByType;

    @JsonProperty("SCALPING_lastUpdated")
    private LocalDateTime scalpingLastUpdated;

    @JsonProperty("SWING_lastUpdated")
    private LocalDateTime swingLastUpdated;

    @JsonProperty("TURNAROUND_lastUpdated")
    private LocalDateTime turnaroundLastUpdated;

    @JsonProperty("VALUE_lastUpdated")
    private LocalDateTime valueLastUpdated;
}
