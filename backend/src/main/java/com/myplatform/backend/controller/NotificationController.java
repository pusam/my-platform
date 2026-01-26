package com.myplatform.backend.controller;

import com.myplatform.backend.dto.NotificationDto;
import com.myplatform.backend.service.NotificationService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "알림", description = "알림 관리 API")
@RestController
@RequestMapping("/api/notifications")
@SecurityRequirement(name = "JWT Bearer")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(summary = "알림 목록 조회", description = "사용자의 알림 목록을 조회합니다.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationDto>>> getNotifications(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        List<NotificationDto> notifications = notificationService.getNotifications(username, page, size);
        return ResponseEntity.ok(ApiResponse.success("알림 목록 조회 성공", notifications));
    }

    @Operation(summary = "읽지 않은 알림 수 조회", description = "읽지 않은 알림 수를 조회합니다.")
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUnreadCount(Authentication authentication) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        Long count = notificationService.getUnreadCount(username);
        return ResponseEntity.ok(ApiResponse.success("읽지 않은 알림 수 조회 성공", Map.of("count", count)));
    }

    @Operation(summary = "알림 읽음 처리", description = "특정 알림을 읽음 처리합니다.")
    @PutMapping("/{id}/read")
    public ResponseEntity<ApiResponse<String>> markAsRead(
            Authentication authentication,
            @PathVariable Long id) {

        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        notificationService.markAsRead(username, id);
        return ResponseEntity.ok(ApiResponse.success("알림이 읽음 처리되었습니다.", null));
    }

    @Operation(summary = "모든 알림 읽음 처리", description = "모든 알림을 읽음 처리합니다.")
    @PutMapping("/read-all")
    public ResponseEntity<ApiResponse<String>> markAllAsRead(Authentication authentication) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        notificationService.markAllAsRead(username);
        return ResponseEntity.ok(ApiResponse.success("모든 알림이 읽음 처리되었습니다.", null));
    }
}
