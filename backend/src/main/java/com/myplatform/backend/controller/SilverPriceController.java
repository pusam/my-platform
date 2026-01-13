package com.myplatform.backend.controller;

import com.myplatform.backend.dto.SilverPriceDto;
import com.myplatform.backend.service.SilverPriceService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "은 시세", description = "은 시세 정보 API")
@RestController
@RequestMapping("/api/silver")
@SecurityRequirement(name = "JWT Bearer")
public class SilverPriceController {

    private final SilverPriceService silverPriceService;

    public SilverPriceController(SilverPriceService silverPriceService) {
        this.silverPriceService = silverPriceService;
    }

    @Operation(summary = "은 시세 조회", description = "현재 은 시세를 조회합니다 (1g 및 1돈 기준)")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "조회 성공",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "인증 실패")
    })
    @GetMapping("/price")
    public ResponseEntity<ApiResponse<SilverPriceDto>> getSilverPrice() {
        SilverPriceDto silverPrice = silverPriceService.getSilverPrice();
        if (silverPrice == null) {
            return ResponseEntity.ok(ApiResponse.fail("은 시세 정보를 가져올 수 없습니다. API 키를 확인하세요."));
        }
        return ResponseEntity.ok(ApiResponse.success("은 시세 조회 성공", silverPrice));
    }

}
