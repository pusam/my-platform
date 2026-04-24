package com.myplatform.backend.controller;

import com.myplatform.backend.dto.SectorOpportunityDto;
import com.myplatform.backend.dto.SectorRotationDto;
import com.myplatform.backend.dto.SectorTradingDto;
import com.myplatform.backend.service.SectorOpportunityService;
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
    private final SectorOpportunityService sectorOpportunityService;

    public SectorTradingController(SectorTradingService sectorTradingService,
                                    SectorOpportunityService sectorOpportunityService) {
        this.sectorTradingService = sectorTradingService;
        this.sectorOpportunityService = sectorOpportunityService;
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

    @Operation(summary = "섹터 로테이션 감지", description = "전일 대비 섹터별 자금 유입/유출 방향을 조회합니다.")
    @GetMapping("/trading/rotation")
    public ResponseEntity<ApiResponse<List<SectorRotationDto>>> getSectorRotation() {
        try {
            List<SectorRotationDto> rotation = sectorTradingService.getSectorRotation();
            return ResponseEntity.ok(ApiResponse.success("섹터 로테이션 데이터", rotation));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("섹터 로테이션 조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "캐시 강제 갱신", description = "섹터 거래대금 캐시를 백그라운드에서 갱신합니다. (초기화 X)")
    @PostMapping("/trading/refresh")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> refreshCache() {
        // 캐시 초기화가 아닌 강제 갱신 (기존 데이터 유지하면서 백그라운드 갱신)
        sectorTradingService.forceRefresh();
        return ResponseEntity.ok(ApiResponse.success(
                "캐시 갱신이 시작되었습니다. 잠시 후 새로고침 해주세요.",
                sectorTradingService.getCacheStatus()));
    }

    @Operation(summary = "캐시 상태 조회", description = "현재 캐시 상태를 조회합니다.")
    @GetMapping("/trading/status")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> getCacheStatus() {
        return ResponseEntity.ok(ApiResponse.success(sectorTradingService.getCacheStatus()));
    }

    @Operation(summary = "섹터 기회 발굴",
            description = "주도 섹터를 선별하고 각 섹터 내 유망 종목 TOP N 을 반환합니다. " +
                    "모든 데이터가 Redis/메모리 캐시에서 조합되므로 KIS 를 추가로 때리지 않습니다.")
    @GetMapping("/opportunities")
    public ResponseEntity<ApiResponse<List<SectorOpportunityDto>>> getSectorOpportunities(
            @Parameter(description = "반환할 섹터 수 (상위 N)")
            @RequestParam(value = "topSectors", defaultValue = "4") int topSectors,
            @Parameter(description = "섹터당 종목 수 (TOP M)")
            @RequestParam(value = "picksPerSector", defaultValue = "3") int picksPerSector) {
        try {
            List<SectorOpportunityDto> result = sectorOpportunityService.getSectorOpportunities(
                    Math.max(1, Math.min(10, topSectors)),
                    Math.max(1, Math.min(10, picksPerSector)));
            return ResponseEntity.ok(ApiResponse.success("섹터 기회 발굴 조회 성공", result));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("섹터 기회 발굴 조회 실패: " + e.getMessage()));
        }
    }
}
