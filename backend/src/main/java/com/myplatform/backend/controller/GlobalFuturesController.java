package com.myplatform.backend.controller;

import com.myplatform.backend.service.GlobalFuturesService;
import com.myplatform.backend.service.GlobalFuturesService.FuturesQuote;
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
}
