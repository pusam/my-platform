package com.myplatform.backend.controller;

import com.myplatform.backend.service.GlobalFuturesService;
import com.myplatform.backend.service.GlobalFuturesService.FuturesQuote;
import com.myplatform.backend.service.OvernightUsMarketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/global-futures")
@RequiredArgsConstructor
public class GlobalFuturesController {

    private final GlobalFuturesService globalFuturesService;
    private final OvernightUsMarketService overnightUsMarketService;

    /**
     * 전체 해외선물 시세 조회
     */
    @GetMapping("/quotes")
    public ResponseEntity<Map<String, Object>> getAllQuotes() {
        List<FuturesQuote> quotes = globalFuturesService.getAllFuturesQuotes();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", quotes
        ));
    }

    /**
     * 개별 선물 시세 조회
     */
    @GetMapping("/quotes/{symbol}")
    public ResponseEntity<Map<String, Object>> getQuote(@PathVariable String symbol) {
        FuturesQuote quote = globalFuturesService.getFuturesQuote(symbol.toUpperCase());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", quote
        ));
    }

    /**
     * 코스피 영향 분석 (야간선물 + 미국선물 종합)
     */
    @GetMapping("/kospi-impact")
    public ResponseEntity<Map<String, Object>> getKospiImpact() {
        Map<String, Object> analysis = globalFuturesService.getKospiImpactAnalysis();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", analysis
        ));
    }

    /**
     * 간밤 미국장 국면 보조(tilt) — 작업3. '오늘' 탭 참고 컨텍스트(미검증, regime 산식 미편입).
     * S&P500/나스닥100/SOX 등락률 + VIX 레벨 → BULL/NEUTRAL/BEAR.
     */
    @GetMapping("/overnight-us")
    public ResponseEntity<Map<String, Object>> getOvernightUs() {
        Map<String, Object> data = overnightUsMarketService.getOvernightView();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", data
        ));
    }
}
