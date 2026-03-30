package com.myplatform.backend.controller;

import com.myplatform.backend.dto.DietDto;
import com.myplatform.backend.dto.DietRequest;
import com.myplatform.backend.service.DietService;
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

@Tag(name = "식단 관리", description = "식단 기록 CRUD API")
@RestController
@RequestMapping("/api/diet")
@SecurityRequirement(name = "JWT Bearer")
public class DietController {

    private final DietService dietService;

    public DietController(DietService dietService) {
        this.dietService = dietService;
    }

    private String getUsername(Authentication auth) {
        return ((UserDetails) auth.getPrincipal()).getUsername();
    }

    @Operation(summary = "식단 기록 목록 조회")
    @GetMapping("/records")
    public ResponseEntity<ApiResponse<List<DietDto>>> getRecords(
            Authentication auth,
            @RequestParam(required = false) String type) {
        String username = getUsername(auth);
        List<DietDto> records = (type != null && !type.isEmpty())
                ? dietService.getByType(username, type)
                : dietService.getRecords(username);
        return ResponseEntity.ok(ApiResponse.success("조회 성공", records));
    }

    @Operation(summary = "식단 기록 등록")
    @PostMapping("/records")
    public ResponseEntity<ApiResponse<DietDto>> add(
            Authentication auth,
            @RequestBody DietRequest request) {
        DietDto result = dietService.add(getUsername(auth), request);
        return ResponseEntity.ok(ApiResponse.success("식단이 등록되었습니다.", result));
    }

    @Operation(summary = "식단 기록 수정")
    @PutMapping("/records/{id}")
    public ResponseEntity<ApiResponse<DietDto>> update(
            Authentication auth,
            @PathVariable Long id,
            @RequestBody DietRequest request) {
        DietDto result = dietService.update(getUsername(auth), id, request);
        return ResponseEntity.ok(ApiResponse.success("식단이 수정되었습니다.", result));
    }

    @Operation(summary = "식단 기록 삭제")
    @DeleteMapping("/records/{id}")
    public ResponseEntity<ApiResponse<String>> delete(
            Authentication auth,
            @PathVariable Long id) {
        dietService.delete(getUsername(auth), id);
        return ResponseEntity.ok(ApiResponse.success("삭제되었습니다.", null));
    }

    @Operation(summary = "식단 요약 정보")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSummary(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("요약 조회 성공", dietService.getSummary(getUsername(auth))));
    }
}
