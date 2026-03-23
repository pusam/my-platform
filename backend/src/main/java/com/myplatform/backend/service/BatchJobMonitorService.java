package com.myplatform.backend.service;

import com.myplatform.backend.dto.BatchJobExecutionDto;
import com.myplatform.backend.dto.BatchJobSummaryDto;
import com.myplatform.backend.entity.BatchJobExecution;
import com.myplatform.backend.repository.BatchJobExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BatchJobMonitorService {

    private final BatchJobExecutionRepository repository;
    private final TelegramNotificationService telegramService;

    public BatchJobMonitorService(BatchJobExecutionRepository repository,
                                   TelegramNotificationService telegramService) {
        this.repository = repository;
        this.telegramService = telegramService;
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

    /**
     * 일일 스케줄러 헬스체크 — 매일 23:30 텔레그램 발송
     * 오늘 실행된 전체 배치 작업의 성공/실패 요약 + 실패 작업 상세
     */
    @Scheduled(cron = "0 30 23 * * MON-FRI", zone = "Asia/Seoul")
    public void sendDailyHealthCheck() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        long successCount = repository.countByStatusAndStartedAtAfter("SUCCESS", todayStart);
        long failedCount = repository.countByStatusAndStartedAtAfter("FAILED", todayStart);
        long runningCount = repository.countByStatusAndStartedAtAfter("RUNNING", todayStart);
        long totalCount = successCount + failedCount + runningCount;

        // 실패 작업 상세 목록
        List<BatchJobExecution> failedJobs =
                repository.findByStatusAndStartedAtAfterOrderByStartedAtDesc("FAILED", todayStart);

        // RUNNING 상태가 23:30까지 남아있으면 행(hang) 의심
        List<BatchJobExecution> stuckJobs =
                repository.findByStatusAndStartedAtAfterOrderByStartedAtDesc("RUNNING", todayStart);

        String statusEmoji = (failedCount == 0 && stuckJobs.isEmpty()) ? "✅" : "⚠️";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<b>%s 일일 스케줄러 헬스체크</b>\n\n", statusEmoji));
        sb.append(String.format("📅 %s\n\n", today));
        sb.append(String.format("📊 <b>실행 요약</b>\n"));
        sb.append(String.format("  • 전체: %d건\n", totalCount));
        sb.append(String.format("  • 성공: %d건\n", successCount));
        sb.append(String.format("  • 실패: %d건\n", failedCount));

        if (runningCount > 0) {
            sb.append(String.format("  • 실행중(행 의심): %d건\n", runningCount));
        }

        if (!failedJobs.isEmpty()) {
            sb.append(String.format("\n🚨 <b>실패 작업 (%d건)</b>\n", failedJobs.size()));
            for (BatchJobExecution job : failedJobs) {
                String time = job.getStartedAt().format(DateTimeFormatter.ofPattern("HH:mm"));
                String errorSnippet = job.getErrorMessage() != null
                        ? job.getErrorMessage().substring(0, Math.min(80, job.getErrorMessage().length()))
                        : "에러 메시지 없음";
                sb.append(String.format("  • <b>%s</b> (%s)\n    → %s\n", job.getJobName(), time, errorSnippet));
            }
        }

        if (!stuckJobs.isEmpty()) {
            sb.append(String.format("\n⏳ <b>행(Hang) 의심 (%d건)</b>\n", stuckJobs.size()));
            for (BatchJobExecution job : stuckJobs) {
                String time = job.getStartedAt().format(DateTimeFormatter.ofPattern("HH:mm"));
                sb.append(String.format("  • <b>%s</b> (%s부터 실행중)\n", job.getJobName(), time));
            }
        }

        if (failedJobs.isEmpty() && stuckJobs.isEmpty()) {
            sb.append("\n🎉 모든 스케줄러가 정상 작동했습니다!");
        }

        sb.append("\n\n━━━━━━━━━━━━━━━━\n🤖 MyPlatform 헬스체크");

        telegramService.sendMessage(sb.toString());
        log.info("일일 헬스체크 알림 발송 - 전체: {}, 성공: {}, 실패: {}", totalCount, successCount, failedCount);
    }

    /**
     * 스케줄러 실패 즉시 알림 (중요 배치에서 호출)
     * @param jobName 배치 이름
     * @param error 에러 메시지
     */
    public void alertFailure(String jobName, String error) {
        try {
            String msg = String.format("🚨 <b>배치 실패</b>\n• %s\n• %s\n• %s",
                    jobName, error.length() > 100 ? error.substring(0, 100) : error,
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
            telegramService.sendMessage(msg);

            // DB 기록
            BatchJobExecution exec = new BatchJobExecution();
            exec.setJobName(jobName);
            exec.setJobClass(jobName);
            exec.setStartedAt(LocalDateTime.now());
            exec.setFinishedAt(LocalDateTime.now());
            exec.setDurationMs(0L);
            exec.setStatus("FAILED");
            exec.setErrorMessage(error.length() > 500 ? error.substring(0, 500) : error);
            repository.save(exec);
        } catch (Exception e) {
            log.error("[배치알림] 실패 알림 발송 오류: {}", e.getMessage());
        }
    }

    /**
     * 스케줄러 성공 기록 (선택적)
     */
    public void recordSuccess(String jobName, long durationMs) {
        try {
            BatchJobExecution exec = new BatchJobExecution();
            exec.setJobName(jobName);
            exec.setJobClass(jobName);
            exec.setStartedAt(LocalDateTime.now().minusNanos(durationMs * 1_000_000));
            exec.setFinishedAt(LocalDateTime.now());
            exec.setDurationMs(durationMs);
            exec.setStatus("SUCCESS");
            repository.save(exec);
        } catch (Exception e) {
            log.debug("[배치기록] 성공 기록 오류: {}", e.getMessage());
        }
    }
}
