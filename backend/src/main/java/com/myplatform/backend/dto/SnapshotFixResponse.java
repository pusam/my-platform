package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 스냅샷 데이터 보정/종가 업데이트 결과
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SnapshotFixResponse {

    private int updatedCount;
    private long elapsedMs;
    private String message;
}
