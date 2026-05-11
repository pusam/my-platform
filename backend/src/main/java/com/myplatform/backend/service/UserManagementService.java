package com.myplatform.backend.service;

import com.myplatform.backend.dto.UserManagementDto;
import com.myplatform.backend.entity.User;
import com.myplatform.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    /**
     * legacy 전체 조회의 메모리 보호 상한.
     * 1000명 초과 시 페이징 endpoint(getUsersPage) 사용해야 함.
     */
    private static final int SAFE_FETCH_LIMIT = 1000;
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "id");
    private static final Pageable SAFE_LIMIT_PAGE = PageRequest.of(0, SAFE_FETCH_LIMIT, DEFAULT_SORT);

    @Transactional(readOnly = true)
    public List<UserManagementDto> getAllUsersDto() {
        // 안전 상한 적용 — 사용자 수 증가 시에도 OOM 방지
        return userRepository.findAll(SAFE_LIMIT_PAGE).stream()
                .map(UserManagementDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<UserManagementDto> getAllUsers() {
        // 안전 상한 적용 — 응답은 UserManagementDto 로 직렬화 (기존 Map 9 필드와 동일 shape)
        return userRepository.findAll(SAFE_LIMIT_PAGE).stream()
                .map(UserManagementDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * 페이지네이션 + 정렬 조회. 1000명 초과 환경에서 admin UI가 사용.
     * AdminController GET /api/admin/users/page 에서 노출.
     */
    @Transactional(readOnly = true)
    public Page<UserManagementDto> getUsersPage(Pageable pageable) {
        Pageable effective = pageable.getSort().isSorted() ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), DEFAULT_SORT);
        return userRepository.findAll(effective).map(UserManagementDto::fromEntity);
    }

    @Transactional(readOnly = true)
    public UserManagementDto getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return UserManagementDto.fromEntity(user);
    }

    /**
     * 단건 조회 — 기존 getUserMap() 호환. DTO 직렬화 결과는 9-key Map 과 동일 JSON shape.
     */
    @Transactional(readOnly = true)
    public UserManagementDto getUserMap(Long id) {
        return getUserById(id);
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

    /**
     * 현재 SecurityContext 의 인증된 사용자명 — admin 액션 audit actor.
     * 인증 컨텍스트 없으면 "SYSTEM" (스케줄러/시스템 호출).
     */
    private String currentActor() {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !"anonymousUser".equals(auth.getName())) {
                return auth.getName();
            }
        } catch (Exception ignored) {}
        return "SYSTEM";
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
    public List<UserManagementDto> getPendingUsers() {
        return userRepository.findByStatus("PENDING").stream()
                .map(UserManagementDto::fromEntity)
                .collect(Collectors.toList());
    }

    public void approveUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setStatus("APPROVED");
        userRepository.save(user);
        activityLogService.log(currentActor(), "STATUS_CHANGE",
                String.format("사용자 '%s' 승인됨", user.getUsername()));
    }

    public void rejectUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        String username = user.getUsername();
        user.setStatus("REJECTED");
        userRepository.save(user);
        activityLogService.log(currentActor(), "STATUS_CHANGE",
                String.format("사용자 '%s' 거부됨", username));
    }

    public void deactivateUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setStatus("REJECTED");
        userRepository.save(user);
        activityLogService.log(currentActor(), "STATUS_CHANGE",
                String.format("사용자 '%s' 비활성화됨", user.getUsername()));
    }

    public void activateUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setStatus("APPROVED");
        userRepository.save(user);
        activityLogService.log(currentActor(), "STATUS_CHANGE",
                String.format("사용자 '%s' 활성화됨", user.getUsername()));
    }

    public void unlockUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setStatus("APPROVED");
        user.setFailedLoginAttempts(0);
        userRepository.save(user);
        activityLogService.log(currentActor(), "STATUS_CHANGE",
                String.format("사용자 '%s' 잠금 해제됨", user.getUsername()));
    }

    public void changeUserRole(Long userId, String role) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        String oldRole = user.getRole();
        user.setRole(role);
        userRepository.save(user);
        activityLogService.log(currentActor(), "ROLE_CHANGE",
                String.format("사용자 '%s'의 권한 변경: %s -> %s", user.getUsername(), oldRole, role));
    }

    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        String username = user.getUsername();
        userRepository.delete(user);
        activityLogService.log(currentActor(), "USER_DELETE",
                String.format("사용자 '%s' 삭제됨", username));
    }

}
