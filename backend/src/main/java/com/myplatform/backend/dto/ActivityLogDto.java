package com.myplatform.backend.dto;

import com.myplatform.backend.entity.ActivityLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityLogDto {
    private Long id;
    private String username;
    private String actionType;
    private String description;
    private String ipAddress;
    private LocalDateTime createdAt;

    public static ActivityLogDto fromEntity(ActivityLog log) {
        return ActivityLogDto.builder()
                .id(log.getId())
                .username(log.getUsername())
                .actionType(log.getActionType())
                .description(log.getDescription())
                .ipAddress(log.getIpAddress())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
