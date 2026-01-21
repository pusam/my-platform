package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ActivityLogDto;
import com.myplatform.backend.dto.ServerStatusDto;
import com.myplatform.backend.service.ActivityLogService;
import com.myplatform.backend.service.ServerStatusService;
import com.myplatform.backend.service.UserManagementService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "관리자", description = "관리자 전용 API")
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserManagementService userManagementService;
    private final ServerStatusService serverStatusService;
    private final ActivityLogService activityLogService;

    public AdminController(UserManagementService userManagementService,
                          ServerStatusService serverStatusService,
                          ActivityLogService activityLogService) {
        this.userManagementService = userManagementService;
        this.serverStatusService = serverStatusService;
        this.activityLogService = activityLogService;
    }

    @Operation(summary = "승인 대기 중인 사용자 목록 조회", description = "회원가입 승인 대기 중인 사용자 목록을 조회합니다.")
    @GetMapping("/users/pending")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPendingUsers() {
        try {
            List<Map<String, Object>> users = userManagementService.getPendingUsers();
            return ResponseEntity.ok(ApiResponse.success("조회 성공", users));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "전체 사용자 목록 조회", description = "모든 사용자 목록을 조회합니다.")
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAllUsers() {
        try {
            List<Map<String, Object>> users = userManagementService.getAllUsers();
            return ResponseEntity.ok(ApiResponse.success("조회 성공", users));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "사용자 상세 조회", description = "특정 사용자 정보를 조회합니다.")
    @GetMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUser(@PathVariable Long userId) {
        try {
            List<Map<String, Object>> users = userManagementService.getAllUsers();
            Map<String, Object> user = users.stream()
                    .filter(u -> u.get("id").equals(userId))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("User not found"));
            return ResponseEntity.ok(ApiResponse.success("조회 성공", user));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "회원가입 승인", description = "대기 중인 사용자의 회원가입을 승인합니다.")
    @PostMapping("/users/{userId}/approve")
    public ResponseEntity<ApiResponse<String>> approveUser(@PathVariable Long userId) {
        try {
            userManagementService.approveUser(userId);
            return ResponseEntity.ok(ApiResponse.success("회원가입이 승인되었습니다.", null));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("승인 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "회원가입 거부", description = "대기 중인 사용자의 회원가입을 거부하고 계정을 삭제합니다.")
    @PostMapping("/users/{userId}/reject")
    public ResponseEntity<ApiResponse<String>> rejectUser(@PathVariable Long userId) {
        try {
            userManagementService.rejectUser(userId);
            return ResponseEntity.ok(ApiResponse.success("회원가입이 거부되었습니다.", null));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("거부 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "사용자 계정 비활성화", description = "사용자 계정을 비활성화합니다.")
    @PostMapping("/users/{userId}/deactivate")
    public ResponseEntity<ApiResponse<String>> deactivateUser(@PathVariable Long userId) {
        try {
            userManagementService.deactivateUser(userId);
            return ResponseEntity.ok(ApiResponse.success("계정이 비활성화되었습니다.", null));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("비활성화 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "사용자 계정 활성화", description = "비활성화된 사용자 계정을 다시 활성화합니다.")
    @PostMapping("/users/{userId}/activate")
    public ResponseEntity<ApiResponse<String>> activateUser(@PathVariable Long userId) {
        try {
            userManagementService.activateUser(userId);
            return ResponseEntity.ok(ApiResponse.success("계정이 활성화되었습니다.", null));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("활성화 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "사용자 삭제", description = "사용자 계정을 완전히 삭제합니다.")
    @DeleteMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<String>> deleteUser(@PathVariable Long userId) {
        try {
            userManagementService.deleteUser(userId);
            return ResponseEntity.ok(ApiResponse.success("사용자가 삭제되었습니다.", null));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("삭제 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "사용자 권한 변경", description = "사용자의 권한을 변경합니다. (USER/ADMIN)")
    @PutMapping("/users/{userId}/role")
    public ResponseEntity<ApiResponse<String>> changeUserRole(
            @PathVariable Long userId,
            @RequestBody Map<String, String> request) {
        try {
            String role = request.get("role");
            if (role == null || (!role.equals("USER") && !role.equals("ADMIN"))) {
                return ResponseEntity.ok(ApiResponse.fail("올바른 권한을 입력해주세요. (USER 또는 ADMIN)"));
            }
            userManagementService.changeUserRole(userId, role);
            return ResponseEntity.ok(ApiResponse.success("권한이 변경되었습니다.", null));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("권한 변경 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "사용자 상태 변경", description = "사용자의 상태를 변경합니다. (PENDING/APPROVED/REJECTED)")
    @PutMapping("/users/{userId}/status")
    public ResponseEntity<ApiResponse<String>> changeUserStatus(
            @PathVariable Long userId,
            @RequestBody Map<String, String> request) {
        try {
            String status = request.get("status");
            if (status == null) {
                return ResponseEntity.ok(ApiResponse.fail("상태를 입력해주세요."));
            }
            if (status.equals("APPROVED")) {
                userManagementService.approveUser(userId);
            } else if (status.equals("REJECTED")) {
                userManagementService.rejectUser(userId);
            } else if (status.equals("PENDING")) {
                userManagementService.deactivateUser(userId);
            }
            return ResponseEntity.ok(ApiResponse.success("상태가 변경되었습니다.", null));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("상태 변경 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "사용자 통계", description = "사용자 통계 정보를 조회합니다.")
    @GetMapping("/users/stats")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUserStats() {
        try {
            Map<String, Long> stats = Map.of(
                    "totalUsers", userManagementService.getTotalUserCount(),
                    "activeUsers", userManagementService.getActiveUserCount()
            );
            return ResponseEntity.ok(ApiResponse.success("조회 성공", stats));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    // ==================== 서버 상태 ====================

    @Operation(summary = "서버 상태 조회", description = "CPU, 메모리, 디스크 등 서버 상태 정보를 조회합니다.")
    @GetMapping("/server/status")
    public ResponseEntity<ApiResponse<ServerStatusDto>> getServerStatus() {
        try {
            ServerStatusDto status = serverStatusService.getServerStatus();
            return ResponseEntity.ok(ApiResponse.success("서버 상태 조회 성공", status));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    // ==================== 활동 로그 ====================

    @Operation(summary = "활동 로그 목록", description = "활동 로그를 페이징하여 조회합니다.")
    @GetMapping("/logs")
    public ResponseEntity<ApiResponse<Page<ActivityLogDto>>> getLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String actionType) {
        try {
            Page<ActivityLogDto> logs = activityLogService.getLogsWithFilters(username, actionType, page, size);
            return ResponseEntity.ok(ApiResponse.success("조회 성공", logs));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "최근 활동 로그", description = "최근 100건의 활동 로그를 조회합니다.")
    @GetMapping("/logs/recent")
    public ResponseEntity<ApiResponse<List<ActivityLogDto>>> getRecentLogs() {
        try {
            List<ActivityLogDto> logs = activityLogService.getRecentLogs();
            return ResponseEntity.ok(ApiResponse.success("조회 성공", logs));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }
}

