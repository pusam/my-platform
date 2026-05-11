package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * API 캐시 통계
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ApiStatsResponse {

    /** 캐시 이름 → 활성/비활성 */
    private Map<String, String> caches;
    private int totalCaches;
    private String message;
}
