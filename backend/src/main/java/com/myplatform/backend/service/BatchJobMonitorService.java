package com.myplatform.backend.service;

import com.myplatform.backend.dto.BatchJobExecutionDto;
import com.myplatform.backend.dto.BatchJobSummaryDto;
import com.myplatform.backend.repository.BatchJobExecutionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class BatchJobMonitorService {

    private final BatchJobExecutionRepository repository;

    public BatchJobMonitorService(BatchJobExecutionRepository repository) {
        this.repository = repository;
    }

    public Page<BatchJobExecutionDto> getRecentExecutions(String jobName, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);

        if (jobName != null && !jobName.isBlank()) {
            return repository.findByJobNameOrderByStartedAtDesc(jobName, pageable)
                    .map(BatchJobExecutionDto::fromEntity);
        }
        return repository.findByOrderByStartedAtDesc(pageable)
                .map(BatchJobExecutionDto::fromEntity);
    }

    public BatchJobSummaryDto getSummary() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        List<String> jobNames = repository.findDistinctJobNames();

        long successToday = repository.countByStatusAndStartedAtAfter("SUCCESS", todayStart);
        long failedToday = repository.countByStatusAndStartedAtAfter("FAILED", todayStart);
        long runningNow = repository.countByStatusAndStartedAtAfter("RUNNING", todayStart);
        long totalToday = successToday + failedToday + runningNow;

        return BatchJobSummaryDto.builder()
                .totalToday(totalToday)
                .successToday(successToday)
                .failedToday(failedToday)
                .runningNow(runningNow)
                .jobNames(jobNames)
                .build();
    }

    public List<String> getJobNames() {
        return repository.findDistinctJobNames();
    }
}
