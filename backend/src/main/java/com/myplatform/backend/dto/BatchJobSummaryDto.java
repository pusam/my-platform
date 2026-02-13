package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchJobSummaryDto {

    private long totalToday;
    private long successToday;
    private long failedToday;
    private long runningNow;
    private List<String> jobNames;
}
