package com.myplatform.backend.dto;

import com.myplatform.backend.entity.BatchJobExecution;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchJobExecutionDto {

    private Long id;
    private String jobName;
    private String jobClass;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String status;
    private String errorMessage;

    public static BatchJobExecutionDto fromEntity(BatchJobExecution entity) {
        return BatchJobExecutionDto.builder()
                .id(entity.getId())
                .jobName(entity.getJobName())
                .jobClass(entity.getJobClass())
                .startedAt(entity.getStartedAt())
                .finishedAt(entity.getFinishedAt())
                .durationMs(entity.getDurationMs())
                .status(entity.getStatus())
                .errorMessage(entity.getErrorMessage())
                .build();
    }
}
