package com.myplatform.backend.controller;

import com.myplatform.backend.entity.TradingKillSwitch;
import com.myplatform.backend.service.TradingSafetyService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 매매 비상 정지 (kill switch) 관리.
 * 모든 엔드포인트는 SecurityConfig 에서 ADMIN 권한 필요.
 */
@Tag(name = "매매 안전장치", description = "비상 정지 / 한도 / 감사")
@RestController
@RequestMapping("/api/admin/trading/safety")
public class TradingSafetyController {

    private final TradingSafetyService safety;

    public TradingSafetyController(TradingSafetyService safety) {
        this.safety = safety;
    }

    @Operation(summary = "현재 안전장치 상태")
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        TradingKillSwitch state = safety.currentState();
        BigDecimal todayBuy = safety.todayBuyAmount();

        Map<String, Object> res = new HashMap<>();
        res.put("killSwitchEnabled", state != null && state.isEnabled());
        res.put("killSwitchReason", state == null ? null : state.getReason());
        res.put("killSwitchTriggeredBy", state == null ? null : state.getTriggeredBy());
        res.put("killSwitchChangedAt", state == null ? null : state.getCreatedAt());
        res.put("dailyBuyLimitKrw", safety.getDailyBuyLimitKrw());
        res.put("alertThresholdKrw", safety.getAlertThresholdKrw());
        res.put("todayBuyAmountKrw", todayBuy);
        res.put("remainingKrw", safety.getDailyBuyLimitKrw().subtract(todayBuy));
        res.put("now", LocalDateTime.now());
        return ResponseEntity.ok(ApiResponse.success(res));
    }

    @Operation(summary = "비상 정지 ON — 즉시 모든 매매 차단")
    @PostMapping("/kill-switch/enable")
    public ResponseEntity<ApiResponse<TradingKillSwitch>> enableKillSwitch(
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        if (reason == null || reason.isBlank()) reason = "수동 비상 정지";
        TradingKillSwitch saved = safety.enable(reason, currentUsername());
        return ResponseEntity.ok(ApiResponse.success("매매 비상 정지가 활성화되었습니다.", saved));
    }

    @Operation(summary = "비상 정지 OFF — 매매 재개")
    @PostMapping("/kill-switch/disable")
    public ResponseEntity<ApiResponse<TradingKillSwitch>> disableKillSwitch(
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        if (reason == null || reason.isBlank()) reason = "수동 해제";
        TradingKillSwitch saved = safety.disable(reason, currentUsername());
        return ResponseEntity.ok(ApiResponse.success("매매가 재개되었습니다.", saved));
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "unknown" : auth.getName();
    }
}
