package com.myplatform.backend.controller;

import com.myplatform.backend.dto.WatchlistDto;
import com.myplatform.backend.service.WatchlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/watchlist")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "관심종목", description = "관심종목(워치리스트) 관리 API")
public class WatchlistController {

    private final WatchlistService watchlistService;
    private final com.myplatform.backend.service.WatchlistRiskMonitorService riskMonitorService;

    private String getUsername(Authentication auth) {
        return ((UserDetails) auth.getPrincipal()).getUsername();
    }

    @GetMapping
    @Operation(summary = "관심종목 목록 조회")
    public ResponseEntity<Map<String, Object>> getWatchlist(Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<WatchlistDto.WatchlistItem> list = watchlistService.getWatchlist(getUsername(authentication));
            response.put("success", true);
            response.put("data", list);
        } catch (Exception e) {
            log.error("관심종목 조회 실패: {}", e.getMessage());
            response.put("success", false);
            response.put("message", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @Operation(summary = "관심종목 추가")
    public ResponseEntity<Map<String, Object>> addWatchlist(
            Authentication authentication,
            @RequestBody WatchlistDto.AddRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            WatchlistDto.WatchlistItem item = watchlistService.addWatchlist(getUsername(authentication), request);
            response.put("success", true);
            response.put("data", item);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("관심종목 추가 실패: {}", e.getMessage());
            response.put("success", false);
            response.put("message", "관심종목 추가에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/alert")
    @Operation(summary = "목표가 알림 설정")
    public ResponseEntity<Map<String, Object>> updateAlert(
            @PathVariable Long id,
            @RequestBody WatchlistDto.AlertRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            WatchlistDto.WatchlistItem item = watchlistService.updateAlert(id, request);
            response.put("success", true);
            response.put("data", item);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("알림 설정 실패: {}", e.getMessage());
            response.put("success", false);
            response.put("message", "알림 설정에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "관심종목 삭제")
    public ResponseEntity<Map<String, Object>> deleteWatchlist(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            watchlistService.deleteWatchlist(id);
            response.put("success", true);
            response.put("message", "관심종목이 삭제되었습니다.");
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("관심종목 삭제 실패: {}", e.getMessage());
            response.put("success", false);
            response.put("message", "삭제에 실패했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/risk-status")
    @Operation(summary = "관심종목 리스크 상태 조회")
    public ResponseEntity<Map<String, Object>> getRiskStatus(@RequestBody List<String> stockCodes) {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("data", riskMonitorService.getCurrentRiskStatus(stockCodes));
        } catch (Exception e) {
            log.error("리스크 상태 조회 실패: {}", e.getMessage());
            response.put("success", false);
            response.put("data", Map.of());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/check")
    @Operation(summary = "북마크 여부 확인")
    public ResponseEntity<Map<String, Object>> checkBookmark(
            Authentication authentication,
            @RequestParam String stockCode) {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean bookmarked = watchlistService.isBookmarked(getUsername(authentication), stockCode);
            response.put("success", true);
            response.put("data", bookmarked);
        } catch (Exception e) {
            response.put("success", false);
            response.put("data", false);
        }
        return ResponseEntity.ok(response);
    }
}
