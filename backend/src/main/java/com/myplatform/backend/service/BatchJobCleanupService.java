package com.myplatform.backend.service;

import com.myplatform.backend.repository.BatchJobExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class BatchJobCleanupService {

    private static final Logger log = LoggerFactory.getLogger(BatchJobCleanupService.class);

    private final BatchJobExecutionRepository repository;

    public BatchJobCleanupService(BatchJobExecutionRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanOldExecutions() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        int deleted = repository.deleteByCreatedAtBefore(cutoff);
        log.info("배치 실행 이력 정리: {}건 삭제 (기준: 7일 이전)", deleted);
    }
}
