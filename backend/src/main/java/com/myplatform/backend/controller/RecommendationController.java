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

    /**
     * 성장주 TOP 10 — 매출·이익 성장률 + PEG 기반. 저평가와 짝(싸다/빠르게 큰다), 별도 트랙.
     */
    @GetMapping("/growth-top10")
    public ResponseEntity<?> getGrowthTop10() {
        var response = recommendationService.getGrowthTop10();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", response.getItems(),
                "dataTime", response.getDataTime(),
                "realtime", response.isRealtime()
        ));
    }

    /**
     * 낙폭과대 반등 TOP 10 — RSI 과매도 + MA20 낙폭 + 반등 조짐. 추격의 정반대, 별도 트랙.
     */
    @GetMapping("/oversold-top10")
    public ResponseEntity<?> getOversoldTop10() {
        var response = recommendationService.getOversoldTop10();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", response.getItems(),
                "dataTime", response.getDataTime(),
                "realtime", response.isRealtime()
        ));
    }

    /**
     * STRONG_BUY + 강한 가치 동시 충족 빈도 — phase 35.
     * phase 34 의 STRONG+VALUE +2 보너스가 dead code 인지 운영 데이터로 확인.
     */
    @GetMapping("/strong-value-frequency")
    public ResponseEntity<?> getStrongValueFrequency(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", recommendationService.getStrongValueFrequency(days)
        ));
    }
}
