package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 관리자: 특정 사용자별 활동 통계 응답.
 * AdminController GET /api/admin/users/{userId}/stats 에서 노출.
 * 기존 Map<String,Object> 의 4 key (boardCount/fileCount/assetCount/transactionCount) 와 동일 shape 유지.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserStatsResponseDto {
    private Long boardCount;
    private Long fileCount;
    private Long assetCount;
    private Long transactionCount;
}
