package com.myplatform.backend.service;

import com.myplatform.backend.dto.UserManagementDto;
import com.myplatform.backend.entity.User;
import com.myplatform.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class UserManagementService {

    private final UserRepository userRepository;
    private final ActivityLogService activityLogService;

    @Transactional(readOnly = true)
    public List<UserManagementDto> getAllUsers() {
        return userRepository.findAll().stream()
                .map(UserManagementDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public UserManagementDto getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return UserManagementDto.fromEntity(user);
    }

    public UserManagementDto updateUserRole(Long userId, String newRole, String adminUsername) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String oldRole = user.getRole();
        user.setRole(newRole);
        User saved = userRepository.save(user);

        // 활동 로그 기록
        activityLogService.log(adminUsername, "ROLE_CHANGE",
                String.format("사용자 '%s'의 권한 변경: %s -> %s", user.getUsername(), oldRole, newRole));

        return UserManagementDto.fromEntity(saved);
    }

    public UserManagementDto updateUserStatus(Long userId, String newStatus, String adminUsername) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String oldStatus = user.getStatus();
        user.setStatus(newStatus);
        User saved = userRepository.save(user);

        // 활동 로그 기록
        activityLogService.log(adminUsername, "STATUS_CHANGE",
                String.format("사용자 '%s'의 상태 변경: %s -> %s", user.getUsername(), oldStatus, newStatus));

        return UserManagementDto.fromEntity(saved);
    }

    public void deleteUser(Long userId, String adminUsername) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String username = user.getUsername();
        userRepository.delete(user);

        // 활동 로그 기록
        activityLogService.log(adminUsername, "USER_DELETE",
                String.format("사용자 '%s' 삭제", username));
    }

    @Transactional(readOnly = true)
    public long getTotalUserCount() {
        return userRepository.count();
    }

    @Transactional(readOnly = true)
    public long getActiveUserCount() {
        return userRepository.findByStatus("APPROVED").size();
    }
}
