package com.myplatform.backend.controller;

import com.myplatform.backend.dto.GoldPriceDto;
import com.myplatform.backend.service.GoldPriceService;
import com.myplatform.core.dto.ApiResponse;
import com.myplatform.core.util.ApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "금 시세", description = "금 시세 정보 API")
@RestController
@RequestMapping("/api/gold")
@SecurityRequirement(name = "JWT Bearer")
public class GoldPriceController {

    private final GoldPriceService goldPriceService;

    public GoldPriceController(GoldPriceService goldPriceService) {
        this.goldPriceService = goldPriceService;
    }

    @Operation(summary = "금 시세 조회", description = "현재 금 시세를 조회합니다 (1g 및 1돈 기준)")
    @io.swagger.v3.oas.annotations.responses.ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "조회 성공",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "인증 실패")
    })
    // 서버 안에서는 토큰 없이 읽힌다(SecurityConfig) — 같은 서버의 jewelry-leads 가 이 응답의 data.pricePerGram 을 읽는다.
    // 주소·응답 모양을 바꾸면 그쪽 금시세가 조용히 멈춘다(docs/rules/5-infra.md §5.16).
    @GetMapping("/price")
    public ResponseEntity<ApiResponse<GoldPriceDto>> getGoldPrice() {
        GoldPriceDto goldPrice = goldPriceService.getGoldPrice();
        if (goldPrice == null) {
            return ApiResponses.error(HttpStatus.SERVICE_UNAVAILABLE, "금 시세 정보를 가져올 수 없습니다. API 키를 확인하세요.");
        }
        return ResponseEntity.ok(ApiResponse.success("금 시세 조회 성공", goldPrice));
    }

    @Operation(summary = "최근 30일 금 시세 조회", description = "차트용 최근 30일 금 시세 데이터를 조회합니다")
    @GetMapping("/history/month")
    public ResponseEntity<ApiResponse<List<GoldPriceDto>>> getMonthlyHistory() {
        List<GoldPriceDto> history = goldPriceService.getMonthlyHistory();
        return ResponseEntity.ok(ApiResponse.success("조회 성공", history));
    }

}
