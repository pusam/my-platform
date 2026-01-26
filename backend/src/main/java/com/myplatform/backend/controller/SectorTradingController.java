package com.myplatform.backend.controller;

import com.myplatform.backend.dto.SectorTradingDto;
import com.myplatform.backend.service.SectorTradingService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "섹터별 거래대금", description = "테마/섹터별 거래대금 조회 API")
@RestController
@RequestMapping("/api/sector")
@SecurityRequirement(name = "JWT Bearer")
public class SectorTradingController {

    private final SectorTradingService sectorTradingService;

    public SectorTradingController(SectorTradingService sectorTradingService) {
        this.sectorTradingService = sectorTradingService;
    }

    @Operation(summary = "전체 섹터 거래대금 조회", description = "모든 섹터의 거래대금을 조회합니다. period 파라미터: TODAY(오늘누적), MIN_5(5분파워), MIN_30(30분파워)")
    @GetMapping("/trading")
    public ResponseEntity<ApiResponse<List<SectorTradingDto>>> getAllSectorTrading(
            @Parameter(description = "조회 기간 (TODAY: 오늘누적, MIN_5: 5분파워, MIN_30: 30분파워)")
            @RequestParam(value = "period", defaultValue = "TODAY") String period) {
        try {
            List<SectorTradingDto> results = sectorTradingService.getAllSectorTradingByPeriod(period);
            return ResponseEntity.ok(ApiResponse.success("섹터별 거래대금 조회 성공", results));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("섹터별 거래대금 조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "특정 섹터 상세 조회", description = "특정 섹터의 거래대금 상세를 조회합니다.")
    @GetMapping("/trading/{sectorCode}")
    public ResponseEntity<ApiResponse<SectorTradingDto>> getSectorDetail(
            @Parameter(description = "섹터 코드 (예: SEMICONDUCTOR, BATTERY, ROBOT)")
            @PathVariable String sectorCode) {
        try {
            SectorTradingDto result = sectorTradingService.getSectorDetail(sectorCode);
            if (result == null) {
                return ResponseEntity.ok(ApiResponse.fail("해당 섹터를 찾을 수 없습니다."));
            }
            return ResponseEntity.ok(ApiResponse.success("섹터 상세 조회 성공", result));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("섹터 상세 조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "캐시 초기화", description = "섹터 거래대금 캐시를 초기화합니다.")
    @PostMapping("/trading/refresh")
    public ResponseEntity<ApiResponse<Void>> refreshCache() {
        sectorTradingService.clearCache();
        return ResponseEntity.ok(ApiResponse.success("캐시가 초기화되었습니다.", null));
    }
}
