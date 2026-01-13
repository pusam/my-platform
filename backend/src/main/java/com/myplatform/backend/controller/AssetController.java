package com.myplatform.backend.controller;

import com.myplatform.backend.dto.AssetDto;
import com.myplatform.backend.dto.AssetRequest;
import com.myplatform.backend.dto.AssetSummaryDto;
import com.myplatform.backend.service.AssetService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "자산 관리", description = "금/은 자산 관리 API")
@RestController
@RequestMapping("/api/asset")
@SecurityRequirement(name = "JWT Bearer")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @Operation(summary = "자산 등록", description = "금/은 구매 내역을 등록합니다.")
    @PostMapping
    public ResponseEntity<ApiResponse<AssetDto>> addAsset(
            Authentication authentication,
            @RequestBody AssetRequest request) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        AssetDto asset = assetService.addAsset(username, request);
        return ResponseEntity.ok(ApiResponse.success("자산이 등록되었습니다.", asset));
    }

    @Operation(summary = "내 자산 목록", description = "내가 보유한 금/은 자산 목록을 조회합니다.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<AssetDto>>> getMyAssets(Authentication authentication) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        List<AssetDto> assets = assetService.getMyAssets(username);
        return ResponseEntity.ok(ApiResponse.success("자산 목록 조회 성공", assets));
    }

    @Operation(summary = "자산 요약", description = "금/은 자산 요약 정보와 현재 시세 대비 손익을 조회합니다.")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<AssetSummaryDto>> getAssetSummary(Authentication authentication) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        AssetSummaryDto summary = assetService.getAssetSummary(username);
        return ResponseEntity.ok(ApiResponse.success("자산 요약 조회 성공", summary));
    }

    @Operation(summary = "자산 삭제", description = "등록된 자산을 삭제합니다.")
    @DeleteMapping("/{assetId}")
    public ResponseEntity<ApiResponse<String>> deleteAsset(
            Authentication authentication,
            @PathVariable Long assetId) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        assetService.deleteAsset(username, assetId);
        return ResponseEntity.ok(ApiResponse.success("자산이 삭제되었습니다.", null));
    }
}

