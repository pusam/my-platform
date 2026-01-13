package com.myplatform.backend.controller;

import com.myplatform.backend.service.UserManagementService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    public AdminController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
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
}

