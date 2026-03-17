package com.myplatform.backend.controller;

import com.myplatform.backend.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/recommendation")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @GetMapping("/top5")
    public ResponseEntity<?> getTop5() {
        var response = recommendationService.getTop5();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", response.getItems(),
                "dataTime", response.getDataTime(),
                "realtime", response.isRealtime()
        ));
    }
}
