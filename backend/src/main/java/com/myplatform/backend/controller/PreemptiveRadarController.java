package com.myplatform.backend.controller;

import com.myplatform.backend.service.PreemptiveRadarService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/radar")
@RequiredArgsConstructor
public class PreemptiveRadarController {

    private final PreemptiveRadarService radarService;

    @GetMapping
    public ResponseEntity<?> getFullRadar() {
        return ResponseEntity.ok(Map.of("success", true, "data", radarService.getFullRadar()));
    }

    @GetMapping("/policy-news")
    public ResponseEntity<?> getPolicyNews() {
        return ResponseEntity.ok(Map.of("success", true, "data", radarService.detectPolicyNews()));
    }

    @GetMapping("/near-high")
    public ResponseEntity<?> getNearHighStocks() {
        return ResponseEntity.ok(Map.of("success", true, "data", radarService.detectNearHighStocks()));
    }

    @GetMapping("/large-holdings")
    public ResponseEntity<?> getLargeHoldings() {
        return ResponseEntity.ok(Map.of("success", true, "data", radarService.detectLargeHoldings()));
    }

    @GetMapping("/earnings-predictions")
    public ResponseEntity<?> getEarningsPredictions() {
        return ResponseEntity.ok(Map.of("success", true, "data", radarService.detectEarningsPredictions()));
    }
}
