package com.myplatform.backend.service;

import com.myplatform.backend.dto.UserManagementDto;
import com.myplatform.backend.entity.User;
import com.myplatform.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class UserManagementService {

    private final UserRepository userRepository;
    private final ActivityLogService activityLogService;

    @Transactional(readOnly = true)
    public List<UserManagementDto> getAllUsersDto() {
        return userRepository.findAll().stream()
                .map(UserManagementDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::userToMap)
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

    // ==================== 기존 AdminController 호환 메서드 ====================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPendingUsers() {
        return userRepository.findByStatus("PENDING").stream()
                .map(this::userToMap)
                .collect(Collectors.toList());
    }

    public void approveUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setStatus("APPROVED");
        userRepository.save(user);
        activityLogService.log("SYSTEM", "STATUS_CHANGE",
                String.format("사용자 '%s' 승인됨", user.getUsername()));
    }

    public void rejectUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        String username = user.getUsername();
        user.setStatus("REJECTED");
        userRepository.save(user);
        activityLogService.log("SYSTEM", "STATUS_CHANGE",
                String.format("사용자 '%s' 거부됨", username));
    }

    public void deactivateUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setStatus("REJECTED");
        userRepository.save(user);
        activityLogService.log("SYSTEM", "STATUS_CHANGE",
                String.format("사용자 '%s' 비활성화됨", user.getUsername()));
    }

    public void activateUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setStatus("APPROVED");
        userRepository.save(user);
        activityLogService.log("SYSTEM", "STATUS_CHANGE",
                String.format("사용자 '%s' 활성화됨", user.getUsername()));
    }

    public void changeUserRole(Long userId, String role) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        String oldRole = user.getRole();
        user.setRole(role);
        userRepository.save(user);
        activityLogService.log("SYSTEM", "ROLE_CHANGE",
                String.format("사용자 '%s'의 권한 변경: %s -> %s", user.getUsername(), oldRole, role));
    }

    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        String username = user.getUsername();
        userRepository.delete(user);
        activityLogService.log("SYSTEM", "USER_DELETE",
                String.format("사용자 '%s' 삭제됨", username));
    }

    private Map<String, Object> userToMap(User user) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", user.getId());
        map.put("username", user.getUsername());
        map.put("name", user.getName());
        map.put("email", user.getEmail());
        map.put("phone", user.getPhone());
        map.put("role", user.getRole());
        map.put("status", user.getStatus());
        map.put("createdAt", user.getCreatedAt());
        map.put("updatedAt", user.getUpdatedAt());
        return map;
    }
}
