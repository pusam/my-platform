package com.myplatform.backend.controller;

import com.myplatform.backend.dto.CarRecordDto;
import com.myplatform.backend.dto.CarRecordRequest;
import com.myplatform.backend.service.CarRecordService;
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

@Tag(name = "자동차 관리", description = "자동차 정비 기록 관리 API")
@RestController
@RequestMapping("/api/car")
@SecurityRequirement(name = "JWT Bearer")
public class CarRecordController {

    private final CarRecordService carRecordService;

    public CarRecordController(CarRecordService carRecordService) {
        this.carRecordService = carRecordService;
    }

    @Operation(summary = "정비 기록 등록", description = "자동차 정비 기록을 등록합니다.")
    @PostMapping("/records")
    public ResponseEntity<ApiResponse<CarRecordDto>> addRecord(
            Authentication authentication,
            @RequestBody CarRecordRequest request) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        CarRecordDto record = carRecordService.addRecord(username, request);
        return ResponseEntity.ok(ApiResponse.success("정비 기록이 등록되었습니다.", record));
    }

    @Operation(summary = "정비 기록 목록", description = "정비 기록 목록을 조회합니다.")
    @GetMapping("/records")
    public ResponseEntity<ApiResponse<List<CarRecordDto>>> getRecords(
            Authentication authentication,
            @RequestParam(required = false) String type) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        List<CarRecordDto> records;
        if (type != null && !type.isEmpty()) {
            records = carRecordService.getRecordsByType(username, type);
        } else {
            records = carRecordService.getRecords(username);
        }
        return ResponseEntity.ok(ApiResponse.success("정비 기록 조회 성공", records));
    }

    @Operation(summary = "정비 요약 정보", description = "정비 요약 정보를 조회합니다.")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSummary(Authentication authentication) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        Map<String, Object> summary = carRecordService.getSummary(username);
        return ResponseEntity.ok(ApiResponse.success("요약 정보 조회 성공", summary));
    }

    @Operation(summary = "정비 기록 삭제", description = "정비 기록을 삭제합니다.")
    @DeleteMapping("/records/{id}")
    public ResponseEntity<ApiResponse<String>> deleteRecord(
            Authentication authentication,
            @PathVariable Long id) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        carRecordService.deleteRecord(username, id);
        return ResponseEntity.ok(ApiResponse.success("삭제되었습니다.", null));
    }
}
