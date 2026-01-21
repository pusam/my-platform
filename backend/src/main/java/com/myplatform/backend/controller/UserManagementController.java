package com.myplatform.backend.controller;

import com.myplatform.backend.dto.UserManagementDto;
import com.myplatform.backend.service.UserManagementService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "사용자 관리", description = "사용자 관리 API (관리자 전용)")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@SecurityRequirement(name = "JWT Bearer")
@PreAuthorize("hasRole('ADMIN')")
public class UserManagementController {

    private final UserManagementService userManagementService;

    @Operation(summary = "전체 사용자 목록", description = "모든 사용자 목록을 조회합니다.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<UserManagementDto>>> getAllUsers() {
        List<UserManagementDto> users = userManagementService.getAllUsersDto();
        return ResponseEntity.ok(ApiResponse.success("조회 성공", users));
    }

    @Operation(summary = "사용자 상세", description = "특정 사용자 정보를 조회합니다.")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserManagementDto>> getUser(@PathVariable Long id) {
        UserManagementDto user = userManagementService.getUserById(id);
        return ResponseEntity.ok(ApiResponse.success("조회 성공", user));
    }

    @Operation(summary = "사용자 권한 변경", description = "사용자의 권한을 변경합니다.")
    @PutMapping("/{id}/role")
    public ResponseEntity<ApiResponse<UserManagementDto>> updateUserRole(
            Authentication authentication,
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        String adminUsername = ((UserDetails) authentication.getPrincipal()).getUsername();
        String newRole = request.get("role");
        UserManagementDto user = userManagementService.updateUserRole(id, newRole, adminUsername);
        return ResponseEntity.ok(ApiResponse.success("권한이 변경되었습니다.", user));
    }

    @Operation(summary = "사용자 상태 변경", description = "사용자의 상태를 변경합니다.")
    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<UserManagementDto>> updateUserStatus(
            Authentication authentication,
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        String adminUsername = ((UserDetails) authentication.getPrincipal()).getUsername();
        String newStatus = request.get("status");
        UserManagementDto user = userManagementService.updateUserStatus(id, newStatus, adminUsername);
        return ResponseEntity.ok(ApiResponse.success("상태가 변경되었습니다.", user));
    }

    @Operation(summary = "사용자 삭제", description = "사용자를 삭제합니다.")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deleteUser(
            Authentication authentication,
            @PathVariable Long id) {
        String adminUsername = ((UserDetails) authentication.getPrincipal()).getUsername();
        userManagementService.deleteUser(id, adminUsername);
        return ResponseEntity.ok(ApiResponse.success("삭제되었습니다.", null));
    }

    @Operation(summary = "사용자 통계", description = "사용자 통계 정보를 조회합니다.")
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUserStats() {
        Map<String, Long> stats = Map.of(
                "totalUsers", userManagementService.getTotalUserCount(),
                "activeUsers", userManagementService.getActiveUserCount()
        );
        return ResponseEntity.ok(ApiResponse.success("조회 성공", stats));
    }
}
