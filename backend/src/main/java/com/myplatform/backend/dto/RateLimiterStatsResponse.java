package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * KIS API Rate Limiter 통계
 * 키: pending / total / throttled / retried / dropped
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RateLimiterStatsResponse {

    private int pending;
    private int total;
    private int throttled;
    private int retried;
    private int dropped;
}
