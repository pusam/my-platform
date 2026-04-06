package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ExerciseDto;
import com.myplatform.backend.dto.ExerciseRequest;
import com.myplatform.backend.service.ExerciseService;
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

@Tag(name = "운동 관리", description = "운동 기록 CRUD API")
@RestController
@RequestMapping("/api/exercise")
@SecurityRequirement(name = "JWT Bearer")
public class ExerciseController {

    private final ExerciseService exerciseService;

    public ExerciseController(ExerciseService exerciseService) {
        this.exerciseService = exerciseService;
    }

    private String getUsername(Authentication auth) {
        return ((UserDetails) auth.getPrincipal()).getUsername();
    }

    @Operation(summary = "운동 기록 목록 조회")
    @GetMapping("/records")
    public ResponseEntity<ApiResponse<List<ExerciseDto>>> getRecords(
            Authentication auth,
            @RequestParam(required = false) String type) {
        String username = getUsername(auth);
        List<ExerciseDto> records = (type != null && !type.isEmpty())
                ? exerciseService.getByType(username, type)
                : exerciseService.getRecords(username);
        return ResponseEntity.ok(ApiResponse.success("조회 성공", records));
    }

    @Operation(summary = "운동 기록 등록")
    @PostMapping("/records")
    public ResponseEntity<ApiResponse<ExerciseDto>> add(
            Authentication auth,
            @RequestBody ExerciseRequest request) {
        ExerciseDto result = exerciseService.add(getUsername(auth), request);
        return ResponseEntity.ok(ApiResponse.success("운동이 등록되었습니다.", result));
    }

    @Operation(summary = "운동 기록 수정")
    @PutMapping("/records/{id}")
    public ResponseEntity<ApiResponse<ExerciseDto>> update(
            Authentication auth,
            @PathVariable Long id,
            @RequestBody ExerciseRequest request) {
        ExerciseDto result = exerciseService.update(getUsername(auth), id, request);
        return ResponseEntity.ok(ApiResponse.success("운동이 수정되었습니다.", result));
    }

    @Operation(summary = "운동 기록 삭제")
    @DeleteMapping("/records/{id}")
    public ResponseEntity<ApiResponse<String>> delete(
            Authentication auth,
            @PathVariable Long id) {
        exerciseService.delete(getUsername(auth), id);
        return ResponseEntity.ok(ApiResponse.success("삭제되었습니다.", null));
    }

    @Operation(summary = "운동 요약 정보")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSummary(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("요약 조회 성공", exerciseService.getSummary(getUsername(auth))));
    }
}
