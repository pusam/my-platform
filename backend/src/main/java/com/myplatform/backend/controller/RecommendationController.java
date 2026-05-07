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
                "realtime", response.isRealtime(),
                "delta", response.getDelta()
        ));
    }

    /**
     * 저평가 TOP 10 — 종합 추천(매수 신호) 과 별도 트랙.
     * PBR/ROE/부채비율/흑자 기반 가치주 점수만 사용.
     */
    @GetMapping("/value-top10")
    public ResponseEntity<?> getValueTop10() {
        var response = recommendationService.getValueTop10();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", response.getItems(),
                "dataTime", response.getDataTime(),
                "realtime", response.isRealtime()
        ));
    }
}
