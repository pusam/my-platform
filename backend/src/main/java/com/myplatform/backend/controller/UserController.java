package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ApprovalRequest;
import com.myplatform.backend.dto.ChangePasswordRequest;
import com.myplatform.backend.dto.UpdateProfileRequest;
import com.myplatform.backend.dto.UserDto;
import com.myplatform.backend.service.UserService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "사용자 설정", description = "사용자 프로필 및 설정 API")
@RestController
@RequestMapping("/api/user")
@SecurityRequirement(name = "JWT Bearer")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "내 정보 조회", description = "현재 로그인한 사용자의 상세 정보를 조회합니다.")
    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<UserDto>> getProfile(Authentication authentication) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        UserDto user = userService.getUserByUsername(username);
        return ResponseEntity.ok(ApiResponse.success("프로필 조회 성공", user));
    }

    @Operation(summary = "프로필 수정", description = "이름 등 프로필 정보를 수정합니다.")
    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<UserDto>> updateProfile(
            Authentication authentication,
            @RequestBody UpdateProfileRequest request) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        UserDto user = userService.updateProfile(username, request);
        return ResponseEntity.ok(ApiResponse.success("프로필이 수정되었습니다.", user));
    }

    @Operation(summary = "비밀번호 변경", description = "비밀번호를 변경합니다.")
    @PutMapping("/password")
    public ResponseEntity<ApiResponse<String>> changePassword(
            Authentication authentication,
            @RequestBody ChangePasswordRequest request) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        userService.changePassword(username, request);
        return ResponseEntity.ok(ApiResponse.success("비밀번호가 변경되었습니다.", null));
    }

    @Operation(summary = "승인 대기 사용자 목록", description = "관리자가 승인 대기 중인 사용자 목록을 조회합니다.")
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<UserDto>>> getPendingUsers(Authentication authentication) {
        // 관리자 권한 체크는 SecurityConfig에서 설정
        List<UserDto> pendingUsers = userService.getPendingUsers();
        return ResponseEntity.ok(ApiResponse.success("승인 대기 사용자 조회 성공", pendingUsers));
    }

    @Operation(summary = "사용자 승인/거부", description = "관리자가 회원가입 신청을 승인하거나 거부합니다.")
    @PutMapping("/{userId}/approval")
    public ResponseEntity<ApiResponse<String>> approveUser(
            @PathVariable Long userId,
            @RequestBody ApprovalRequest request,
            Authentication authentication) {
        // 관리자 권한 체크는 SecurityConfig에서 설정
        userService.approveUser(userId, request.getStatus());
        String message = "APPROVED".equals(request.getStatus()) ? "사용자가 승인되었습니다." : "사용자가 거부되었습니다.";
        return ResponseEntity.ok(ApiResponse.success(message, null));
    }
}
