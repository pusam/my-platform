package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ServerStatusDto;
import com.myplatform.backend.service.ServerStatusService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "서버 상태", description = "서버 모니터링 API (관리자 전용)")
@RestController
@RequestMapping("/api/admin/server")
@RequiredArgsConstructor
@SecurityRequirement(name = "JWT Bearer")
public class ServerStatusController {

    private final ServerStatusService serverStatusService;

    @Operation(summary = "서버 상태 조회", description = "CPU, 메모리, 디스크 등 서버 상태 정보를 조회합니다.")
    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ServerStatusDto>> getServerStatus() {
        ServerStatusDto status = serverStatusService.getServerStatus();
        return ResponseEntity.ok(ApiResponse.success("서버 상태 조회 성공", status));
    }
}
