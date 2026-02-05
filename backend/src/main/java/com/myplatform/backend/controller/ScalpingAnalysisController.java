package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ScalpingAnalysisDto;
import com.myplatform.backend.service.ScalpingAnalysisService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 단타(스캘핑) 분석 API
 * 체결강도, 프로그램 매매, 투자자 매매 정보를 실시간으로 조회
 */
@Tag(name = "단타 분석", description = "실시간 단타 분석 API (체결강도, 프로그램매매, 투자자 매매)")
@RestController
@RequestMapping("/api/scalping")
@RequiredArgsConstructor
@SecurityRequirement(name = "JWT Bearer")
public class ScalpingAnalysisController {

    private final ScalpingAnalysisService scalpingAnalysisService;

    @Operation(
            summary = "단타 분석 조회",
            description = "종목의 체결강도, 프로그램 매매, 외국인/기관 순매수 정보를 조회합니다."
    )
    @GetMapping("/{stockCode}")
    public ResponseEntity<ApiResponse<ScalpingAnalysisDto>> getScalpingAnalysis(
            @Parameter(description = "종목코드 (6자리)", example = "005930")
            @PathVariable String stockCode) {

        // 종목코드 검증
        if (stockCode == null || stockCode.length() != 6) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.fail("종목코드는 6자리여야 합니다."));
        }

        ScalpingAnalysisDto analysis = scalpingAnalysisService.getScalpingAnalysis(stockCode);

        if (analysis == null || analysis.getCurrentPrice() == null) {
            return ResponseEntity.ok(ApiResponse.fail("종목 정보를 조회할 수 없습니다. 종목코드를 확인해주세요."));
        }

        return ResponseEntity.ok(ApiResponse.success(analysis));
    }

    @Operation(
            summary = "체결강도 갱신",
            description = "체결강도만 빠르게 갱신합니다. 자동 갱신용 경량 API입니다."
    )
    @GetMapping("/{stockCode}/refresh")
    public ResponseEntity<ApiResponse<ScalpingAnalysisDto>> refreshVolumePower(
            @Parameter(description = "종목코드 (6자리)", example = "005930")
            @PathVariable String stockCode) {

        // 종목코드 검증
        if (stockCode == null || stockCode.length() != 6) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.fail("종목코드는 6자리여야 합니다."));
        }

        ScalpingAnalysisDto analysis = scalpingAnalysisService.getVolumePowerRefresh(stockCode);

        if (analysis == null || analysis.getCurrentPrice() == null) {
            return ResponseEntity.ok(ApiResponse.fail("종목 정보를 조회할 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(analysis));
    }
}
