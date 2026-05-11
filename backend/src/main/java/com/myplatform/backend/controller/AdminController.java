package com.myplatform.backend.controller;

import com.myplatform.backend.dto.BatchJobExecutionDto;
import com.myplatform.backend.dto.BatchJobSummaryDto;
import com.myplatform.backend.dto.SystemStatsDto;
import com.myplatform.backend.dto.UserManagementDto;
import com.myplatform.backend.dto.UserStatsResponseDto;
import com.myplatform.backend.service.AdminStatsService;
import com.myplatform.backend.dto.ActivityLogDto;
import com.myplatform.backend.dto.ServerStatusDto;
import com.myplatform.backend.service.ActivityLogService;
import com.myplatform.backend.service.AiStrategySnapshotService;
import com.myplatform.backend.service.BatchJobMonitorService;
import com.myplatform.backend.service.ServerStatusService;
import com.myplatform.backend.service.UserManagementService;
import com.myplatform.core.dto.ApiResponse;
import com.myplatform.core.util.ApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "관리자", description = "관리자 전용 API")
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserManagementService userManagementService;
    private final AdminStatsService adminStatsService;
    private final CacheManager cacheManager;
    private final ServerStatusService serverStatusService;
    private final ActivityLogService activityLogService;
    private final AiStrategySnapshotService aiStrategySnapshotService;
    private final BatchJobMonitorService batchJobMonitorService;
    private final com.myplatform.backend.service.KisApiRateLimiter kisApiRateLimiter;
    @org.springframework.beans.factory.annotation.Autowired
    private com.myplatform.backend.service.KrxStockMasterSeeder krxStockMasterSeeder;
    @org.springframework.beans.factory.annotation.Autowired
    private com.myplatform.backend.service.StockMasterService stockMasterService;

    public AdminController(UserManagementService userManagementService,
                          AdminStatsService adminStatsService,
                          CacheManager cacheManager,
                          ServerStatusService serverStatusService,
                          ActivityLogService activityLogService,
                          AiStrategySnapshotService aiStrategySnapshotService,
                          BatchJobMonitorService batchJobMonitorService,
                          com.myplatform.backend.service.KisApiRateLimiter kisApiRateLimiter) {
        this.userManagementService = userManagementService;
        this.adminStatsService = adminStatsService;
        this.cacheManager = cacheManager;
        this.serverStatusService = serverStatusService;
        this.activityLogService = activityLogService;
        this.aiStrategySnapshotService = aiStrategySnapshotService;
        this.batchJobMonitorService = batchJobMonitorService;
        this.kisApiRateLimiter = kisApiRateLimiter;
    }

    // 응답 생성은 core/util/ApiResponses 공용 유틸로 이동 — 다른 컨트롤러도 동일 helper 사용

    @PostMapping("/stock-master/seed")
    @Operation(summary = "stock_master KRX 시드 강제 실행",
            description = "KOSPI/KOSDAQ 전체 종목코드/종목명 매핑을 KRX 에서 다운로드하여 적재. " +
                    "@Scheduled (매일 06:00) 와 동일 로직 — 즉시 트리거.")
    public ResponseEntity<java.util.Map<String, Object>> seedStockMaster() {
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        try {
            int beforeCount = stockMasterService.cachedCount();
            int total = krxStockMasterSeeder.seedAll();
            int afterCount = stockMasterService.cachedCount();
            response.put("success", true);
            response.put("seeded", total);
            response.put("cachedBefore", beforeCount);
            response.put("cachedAfter", afterCount);
            response.put("message",
                    String.format("시드 완료 — %d 종목 적재. 캐시 %d → %d.",
                            total, beforeCount, afterCount));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "시드 실패: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/stock-master/status")
    @Operation(summary = "stock_master 캐시 현황", description = "현재 메모리 캐시된 종목 수 + 샘플 5개")
    public ResponseEntity<java.util.Map<String, Object>> stockMasterStatus() {
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("success", true);
        response.put("cachedCount", stockMasterService.cachedCount());
        response.put("lastSeedEpochSeconds", krxStockMasterSeeder.getLastSeedEpochSeconds());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "시스템 통계 조회", description = "시스템 전체 통계 정보를 조회합니다.")
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<SystemStatsDto>> getSystemStats() {
        try {
            SystemStatsDto stats = adminStatsService.getSystemStats();
            return ResponseEntity.ok(ApiResponse.success("통계 조회 성공", stats));
        } catch (Exception e) {
            return ApiResponses.error(e, "통계 조회 실패: ");
        }
    }

    @Operation(summary = "사용자별 통계 조회", description = "특정 사용자의 활동 통계를 조회합니다.")
    @GetMapping("/users/{userId}/stats")
    public ResponseEntity<ApiResponse<UserStatsResponseDto>> getUserStats(@PathVariable Long userId) {
        try {
            UserStatsResponseDto stats = adminStatsService.getUserStats(userId);
            return ResponseEntity.ok(ApiResponse.success("사용자 통계 조회 성공", stats));
        } catch (Exception e) {
            return ApiResponses.error(e, "통계 조회 실패: ");
        }
    }

    @Operation(summary = "승인 대기 중인 사용자 목록 조회", description = "회원가입 승인 대기 중인 사용자 목록을 조회합니다.")
    @GetMapping("/users/pending")
    public ResponseEntity<ApiResponse<List<UserManagementDto>>> getPendingUsers() {
        try {
            List<UserManagementDto> users = userManagementService.getPendingUsers();
            return ResponseEntity.ok(ApiResponse.success("조회 성공", users));
        } catch (Exception e) {
            return ApiResponses.error(e, "조회 실패: ");
        }
    }

    @Operation(summary = "전체 사용자 목록 조회",
            description = "모든 사용자 목록을 조회합니다. 내부적으로 1000명 안전 상한 적용. " +
                    "대량 환경에서는 /users/page 페이지네이션 endpoint 사용.")
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<UserManagementDto>>> getAllUsers() {
        try {
            List<UserManagementDto> users = userManagementService.getAllUsers();
            return ResponseEntity.ok(ApiResponse.success("조회 성공", users));
        } catch (Exception e) {
            return ApiResponses.error(e, "조회 실패: ");
        }
    }

    @Operation(summary = "사용자 페이지네이션 조회",
            description = "page/size 기반 사용자 목록. 응답은 Spring Data Page 구조 (content + totalPages + ...).")
    @GetMapping("/users/page")
    public ResponseEntity<ApiResponse<Page<UserManagementDto>>> getUsersPage(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        try {
            int safeSize = Math.min(Math.max(size, 1), 200);  // 1~200 클램프
            Page<UserManagementDto> result = userManagementService.getUsersPage(
                    org.springframework.data.domain.PageRequest.of(page, safeSize));
            return ResponseEntity.ok(ApiResponse.success("조회 성공", result));
        } catch (Exception e) {
            return ApiResponses.error(e, "조회 실패: ");
        }
    }

    @Operation(summary = "사용자 상세 조회", description = "특정 사용자 정보를 조회합니다.")
    @GetMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<UserManagementDto>> getUser(@PathVariable Long userId) {
        try {
            // findById 한 번으로 조회 (전체 사용자 스트림 필터 → 단건 조회 최적화)
            UserManagementDto user = userManagementService.getUserMap(userId);
            return ResponseEntity.ok(ApiResponse.success("조회 성공", user));
        } catch (Exception e) {
            return ApiResponses.error(e, "조회 실패: ");
        }
    }

    @Operation(summary = "회원가입 승인", description = "대기 중인 사용자의 회원가입을 승인합니다.")
    @PostMapping("/users/{userId}/approve")
    public ResponseEntity<ApiResponse<String>> approveUser(@PathVariable Long userId) {
        try {
            userManagementService.approveUser(userId);
            return ResponseEntity.ok(ApiResponse.success("회원가입이 승인되었습니다.", null));
        } catch (Exception e) {
            return ApiResponses.error(e, "승인 실패: ");
        }
    }

    @Operation(summary = "회원가입 거부", description = "대기 중인 사용자의 회원가입을 거부하고 계정을 삭제합니다.")
    @PostMapping("/users/{userId}/reject")
    public ResponseEntity<ApiResponse<String>> rejectUser(@PathVariable Long userId) {
        try {
            userManagementService.rejectUser(userId);
            return ResponseEntity.ok(ApiResponse.success("회원가입이 거부되었습니다.", null));
        } catch (Exception e) {
            return ApiResponses.error(e, "거부 실패: ");
        }
    }

    @Operation(summary = "사용자 계정 비활성화", description = "사용자 계정을 비활성화합니다.")
    @PostMapping("/users/{userId}/deactivate")
    public ResponseEntity<ApiResponse<String>> deactivateUser(@PathVariable Long userId) {
        try {
            userManagementService.deactivateUser(userId);
            return ResponseEntity.ok(ApiResponse.success("계정이 비활성화되었습니다.", null));
        } catch (Exception e) {
            return ApiResponses.error(e, "비활성화 실패: ");
        }
    }

    @Operation(summary = "사용자 계정 활성화", description = "비활성화된 사용자 계정을 다시 활성화합니다.")
    @PostMapping("/users/{userId}/activate")
    public ResponseEntity<ApiResponse<String>> activateUser(@PathVariable Long userId) {
        try {
            userManagementService.activateUser(userId);
            return ResponseEntity.ok(ApiResponse.success("계정이 활성화되었습니다.", null));
        } catch (Exception e) {
            return ApiResponses.error(e, "활성화 실패: ");
        }
    }

    @Operation(summary = "사용자 잠금 해제", description = "로그인 실패로 잠긴 사용자 계정을 해제합니다.")
    @PostMapping("/users/{userId}/unlock")
    public ResponseEntity<ApiResponse<String>> unlockUser(@PathVariable Long userId) {
        try {
            userManagementService.unlockUser(userId);
            return ResponseEntity.ok(ApiResponse.success("계정 잠금이 해제되었습니다.", null));
        } catch (Exception e) {
            return ApiResponses.error(e, "잠금 해제 실패: ");
        }
    }

    @Operation(summary = "사용자 삭제", description = "사용자 계정을 완전히 삭제합니다.")
    @DeleteMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<String>> deleteUser(@PathVariable Long userId) {
        try {
            userManagementService.deleteUser(userId);
            return ResponseEntity.ok(ApiResponse.success("사용자가 삭제되었습니다.", null));
        } catch (Exception e) {
            return ApiResponses.error(e, "삭제 실패: ");
        }
    }

    @Operation(summary = "사용자 권한 변경", description = "사용자의 권한을 변경합니다. (USER/ADMIN)")
    @PutMapping("/users/{userId}/role")
    public ResponseEntity<ApiResponse<String>> changeUserRole(
            @PathVariable Long userId,
            @RequestBody Map<String, String> request) {
        try {
            String role = request.get("role");
            if (role == null || (!role.equals("USER") && !role.equals("ADMIN"))) {
                return ResponseEntity.badRequest().body(ApiResponse.fail("올바른 권한을 입력해주세요. (USER 또는 ADMIN)"));
            }
            userManagementService.changeUserRole(userId, role);
            return ResponseEntity.ok(ApiResponse.success("권한이 변경되었습니다.", null));
        } catch (Exception e) {
            return ApiResponses.error(e, "권한 변경 실패: ");
        }
    }

    @Operation(summary = "API 통계 조회", description = "KIS API 등의 사용 통계 및 캐시 상태를 조회합니다.")
    @GetMapping("/api-stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getApiStats() {
        try {
            Map<String, Object> stats = new HashMap<>();

            // 캐시 통계
            Map<String, Object> cacheStats = new HashMap<>();
            String[] cacheNames = {"investorTrend", "continuousBuy", "supplySurge",
                                   "goldPrice", "silverPrice"};

            for (String cacheName : cacheNames) {
                var cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    cacheStats.put(cacheName, "활성");
                } else {
                    cacheStats.put(cacheName, "비활성");
                }
            }

            stats.put("caches", cacheStats);
            stats.put("totalCaches", cacheNames.length);
            stats.put("message", "캐시는 API 응답 속도를 개선하고 외부 API 호출을 줄입니다.");

            return ResponseEntity.ok(ApiResponse.success("API 통계 조회 성공", stats));
        } catch (Exception e) {
            return ApiResponses.error(e, "통계 조회 실패: ");
        }
    }

    @Operation(summary = "캐시 초기화", description = "모든 API 캐시를 초기화합니다. (KIS API, 금/은 시세 등)")
    @PostMapping("/clear-cache")
    public ResponseEntity<ApiResponse<String>> clearAllCaches() {
        try {
            int clearedCount = 0;
            for (String cacheName : cacheManager.getCacheNames()) {
                var cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    cache.clear();
                    clearedCount++;
                }
            }
            return ResponseEntity.ok(ApiResponse.success(
                String.format("%d개의 캐시가 초기화되었습니다. 다음 API 호출 시 최신 데이터를 가져옵니다.", clearedCount),
                null
            ));
        } catch (Exception e) {
            return ApiResponses.error(e, "캐시 초기화 실패: ");
        }
    }

    @Operation(summary = "특정 캐시 초기화", description = "특정 API의 캐시만 초기화합니다.")
    @PostMapping("/clear-cache/{cacheName}")
    public ResponseEntity<ApiResponse<String>> clearSpecificCache(@PathVariable String cacheName) {
        try {
            var cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
                return ResponseEntity.ok(ApiResponse.success(
                    String.format("%s 캐시가 초기화되었습니다.", cacheName),
                    null
                ));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail("존재하지 않는 캐시입니다: " + cacheName));
            }
        } catch (Exception e) {
            return ApiResponses.error(e, "캐시 초기화 실패: ");
        }
    }

    @Operation(summary = "사용자 상태 변경", description = "사용자의 상태를 변경합니다. (PENDING/APPROVED/REJECTED)")
    @PutMapping("/users/{userId}/status")
    public ResponseEntity<ApiResponse<String>> changeUserStatus(
            @PathVariable Long userId,
            @RequestBody Map<String, String> request) {
        try {
            String status = request.get("status");
            if (status == null) {
                return ResponseEntity.badRequest().body(ApiResponse.fail("상태를 입력해주세요."));
            }
            if (status.equals("APPROVED")) {
                userManagementService.approveUser(userId);
            } else if (status.equals("REJECTED")) {
                userManagementService.rejectUser(userId);
            } else if (status.equals("PENDING")) {
                userManagementService.deactivateUser(userId);
            }
            return ResponseEntity.ok(ApiResponse.success("상태가 변경되었습니다.", null));
        } catch (Exception e) {
            return ApiResponses.error(e, "상태 변경 실패: ");
        }
    }

    @Operation(summary = "사용자 통계", description = "사용자 통계 정보를 조회합니다.")
    @GetMapping("/users/stats")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUserStats() {
        try {
            Map<String, Long> stats = Map.of(
                    "totalUsers", userManagementService.getTotalUserCount(),
                    "activeUsers", userManagementService.getActiveUserCount()
            );
            return ResponseEntity.ok(ApiResponse.success("조회 성공", stats));
        } catch (Exception e) {
            return ApiResponses.error(e, "조회 실패: ");
        }
    }

    // ==================== 서버 상태 ====================

    @Operation(summary = "서버 상태 조회", description = "CPU, 메모리, 디스크 등 서버 상태 정보를 조회합니다.")
    @GetMapping("/server/status")
    public ResponseEntity<ApiResponse<ServerStatusDto>> getServerStatus() {
        try {
            ServerStatusDto status = serverStatusService.getServerStatus();
            return ResponseEntity.ok(ApiResponse.success("서버 상태 조회 성공", status));
        } catch (Exception e) {
            return ApiResponses.error(e, "조회 실패: ");
        }
    }

    // ==================== 활동 로그 ====================

    @Operation(summary = "활동 로그 목록", description = "활동 로그를 페이징하여 조회합니다.")
    @GetMapping("/logs")
    public ResponseEntity<ApiResponse<Page<ActivityLogDto>>> getLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String actionType) {
        try {
            Page<ActivityLogDto> logs = activityLogService.getLogsWithFilters(username, actionType, page, size);
            return ResponseEntity.ok(ApiResponse.success("조회 성공", logs));
        } catch (Exception e) {
            return ApiResponses.error(e, "조회 실패: ");
        }
    }

    @Operation(summary = "최근 활동 로그", description = "최근 100건의 활동 로그를 조회합니다.")
    @GetMapping("/logs/recent")
    public ResponseEntity<ApiResponse<List<ActivityLogDto>>> getRecentLogs() {
        try {
            List<ActivityLogDto> logs = activityLogService.getRecentLogs();
            return ResponseEntity.ok(ApiResponse.success("조회 성공", logs));
        } catch (Exception e) {
            return ApiResponses.error(e, "조회 실패: ");
        }
    }

    // ==================== AI 전략 스냅샷 데이터 관리 ====================

    @Operation(summary = "스냅샷 데이터 보정", description = "모든 전략 스냅샷의 가격/등락률 데이터를 최신 API 데이터로 업데이트합니다.")
    @GetMapping("/fix-data")
    public ResponseEntity<ApiResponse<Map<String, Object>>> fixSnapshotData() {
        try {
            long startTime = System.currentTimeMillis();
            int updatedCount = aiStrategySnapshotService.fixAllSnapshotData();
            long elapsed = System.currentTimeMillis() - startTime;

            Map<String, Object> result = new HashMap<>();
            result.put("updatedCount", updatedCount);
            result.put("elapsedMs", elapsed);
            result.put("message", String.format("%d건의 스냅샷 데이터가 보정되었습니다.", updatedCount));

            return ResponseEntity.ok(ApiResponse.success("데이터 보정 완료", result));
        } catch (Exception e) {
            return ApiResponses.error(e, "데이터 보정 실패: ");
        }
    }

    @Operation(summary = "장 마감 데이터 업데이트", description = "모든 전략 스냅샷을 최종 종가로 강제 업데이트합니다.")
    @PostMapping("/update-closing-prices")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateClosingPrices() {
        try {
            long startTime = System.currentTimeMillis();
            int updatedCount = 0;

            for (com.myplatform.backend.entity.AiStrategySnapshot.StrategyType type :
                    com.myplatform.backend.entity.AiStrategySnapshot.StrategyType.values()) {
                updatedCount += aiStrategySnapshotService.updateSnapshotPrices(type);
            }

            long elapsed = System.currentTimeMillis() - startTime;

            Map<String, Object> result = new HashMap<>();
            result.put("updatedCount", updatedCount);
            result.put("elapsedMs", elapsed);
            result.put("message", String.format("%d건의 스냅샷이 최신 종가로 업데이트되었습니다.", updatedCount));

            return ResponseEntity.ok(ApiResponse.success("종가 업데이트 완료", result));
        } catch (Exception e) {
            return ApiResponses.error(e, "종가 업데이트 실패: ");
        }
    }

    @Operation(summary = "스냅샷 통계 조회", description = "AI 전략 스냅샷의 현재 상태를 조회합니다.")
    @GetMapping("/snapshot-stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSnapshotStats() {
        try {
            Map<String, Object> stats = aiStrategySnapshotService.getSnapshotStats();
            return ResponseEntity.ok(ApiResponse.success("조회 성공", stats));
        } catch (Exception e) {
            return ApiResponses.error(e, "조회 실패: ");
        }
    }

    // ==================== 배치 잡 모니터링 ====================

    @Operation(summary = "배치 잡 실행 목록", description = "배치 잡 실행 이력을 페이징하여 조회합니다.")
    @GetMapping("/batch-jobs")
    public ResponseEntity<ApiResponse<Page<BatchJobExecutionDto>>> getBatchJobs(
            @RequestParam(required = false) String jobName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        try {
            Page<BatchJobExecutionDto> executions = batchJobMonitorService.getRecentExecutions(jobName, page, size);
            return ResponseEntity.ok(ApiResponse.success("조회 성공", executions));
        } catch (Exception e) {
            return ApiResponses.error(e, "조회 실패: ");
        }
    }

    @Operation(summary = "배치 잡 오늘 요약", description = "오늘의 배치 잡 실행 통계를 조회합니다.")
    @GetMapping("/batch-jobs/summary")
    public ResponseEntity<ApiResponse<BatchJobSummaryDto>> getBatchJobSummary() {
        try {
            BatchJobSummaryDto summary = batchJobMonitorService.getSummary();
            return ResponseEntity.ok(ApiResponse.success("조회 성공", summary));
        } catch (Exception e) {
            return ApiResponses.error(e, "조회 실패: ");
        }
    }

    @Operation(summary = "배치 잡 이름 목록", description = "등록된 배치 잡 이름 목록을 조회합니다.")
    @GetMapping("/batch-jobs/names")
    public ResponseEntity<ApiResponse<List<String>>> getBatchJobNames() {
        try {
            List<String> names = batchJobMonitorService.getJobNames();
            return ResponseEntity.ok(ApiResponse.success("조회 성공", names));
        } catch (Exception e) {
            return ApiResponses.error(e, "조회 실패: ");
        }
    }

    // ==================== KIS API Rate Limiter 모니터링 ====================

    @Operation(summary = "KIS API Rate Limiter 통계", description = "KIS API 호출 통계를 조회합니다. (대기/총호출/스로틀/재시도/드랍)")
    @GetMapping("/rate-limiter-stats")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> getRateLimiterStats() {
        try {
            Map<String, Integer> stats = kisApiRateLimiter.getStats();
            return ResponseEntity.ok(ApiResponse.success("Rate Limiter 통계 조회 성공", stats));
        } catch (Exception e) {
            return ApiResponses.error(e, "통계 조회 실패: ");
        }
    }

    @Operation(summary = "KIS API Rate Limiter 통계 초기화", description = "KIS API 호출 통계를 초기화합니다.")
    @PostMapping("/rate-limiter-stats/reset")
    public ResponseEntity<ApiResponse<String>> resetRateLimiterStats() {
        try {
            kisApiRateLimiter.resetStats();
            return ResponseEntity.ok(ApiResponse.success("Rate Limiter 통계가 초기화되었습니다.", null));
        } catch (Exception e) {
            return ApiResponses.error(e, "초기화 실패: ");
        }
    }
}

