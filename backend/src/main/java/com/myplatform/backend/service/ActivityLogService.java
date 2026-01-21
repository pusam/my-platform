package com.myplatform.backend.service;

import com.myplatform.backend.dto.ActivityLogDto;
import com.myplatform.backend.entity.ActivityLog;
import com.myplatform.backend.repository.ActivityLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ActivityLogService {

    private final ActivityLogRepository activityLogRepository;

    public void log(String username, String actionType, String description) {
        log(username, actionType, description, null);
    }

    public void log(String username, String actionType, String description, String ipAddress) {
        ActivityLog log = ActivityLog.builder()
                .username(username)
                .actionType(actionType)
                .description(description)
                .ipAddress(ipAddress)
                .build();
        activityLogRepository.save(log);
    }

    @Transactional(readOnly = true)
    public Page<ActivityLogDto> getLogs(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return activityLogRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(ActivityLogDto::fromEntity);
    }

    @Transactional(readOnly = true)
    public Page<ActivityLogDto> getLogsByUsername(String username, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return activityLogRepository.findByUsernameOrderByCreatedAtDesc(username, pageable)
                .map(ActivityLogDto::fromEntity);
    }

    @Transactional(readOnly = true)
    public Page<ActivityLogDto> getLogsByActionType(String actionType, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return activityLogRepository.findByActionTypeOrderByCreatedAtDesc(actionType, pageable)
                .map(ActivityLogDto::fromEntity);
    }

    @Transactional(readOnly = true)
    public Page<ActivityLogDto> getLogsWithFilters(String username, String actionType, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return activityLogRepository.findByFilters(
                username != null && !username.isEmpty() ? username : null,
                actionType != null && !actionType.isEmpty() ? actionType : null,
                pageable
        ).map(ActivityLogDto::fromEntity);
    }

    @Transactional(readOnly = true)
    public List<ActivityLogDto> getRecentLogs() {
        return activityLogRepository.findTop100ByOrderByCreatedAtDesc()
                .stream()
                .map(ActivityLogDto::fromEntity)
                .collect(Collectors.toList());
    }
}
