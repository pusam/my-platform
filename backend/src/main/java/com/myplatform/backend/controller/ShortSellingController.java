package com.myplatform.backend.controller;

import com.myplatform.backend.shortselling.ShortSellingTrade;
import com.myplatform.backend.shortselling.ShortSellingTradeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 공매도 <b>거래 비중</b> API(2026-10-02) — KIS 공매도 상위종목·일별추이. 잔고가 아니다.
 *
 * <p>잔고 출처(KRX·네이버)가 죽어 화면을 거래 비중으로 옮겼다. 표시 전용 — 판정·봇·경보에 쓰지 않는다(사용자 결정).
 * 조회 실패는 {@code dataAvailable=false} 로 '아직 수집 전(asOf=null)'·'0건'과 구분한다(§4c).
 */
@RestController
@RequestMapping("/api/short-selling")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "공매도 거래 비중", description = "KIS 공매도 상위종목·일별추이 (잔고 아님, 표시 전용)")
public class ShortSellingController {

    private static final String SOURCE = "KIS 국내주식 공매도 상위종목(국내주식-133)";
    private static final String METRIC_NOTE = "그날 거래량 중 공매도 몫 — 잔고가 아니다. 참고용(매수 판정에 쓰지 않음).";

    private final ShortSellingTradeService shortSellingTradeService;

    /** 최신 기준일의 공매도 거래 비중 상위 종목. */
    @GetMapping("/top")
    @Operation(summary = "공매도 거래 비중 상위", description = "가장 최근 수집 기준일의 KIS 공매도 상위종목(응답 순서).")
    public ResponseEntity<Map<String, Object>> getTopShortSelling(
            @Parameter(description = "조회 개수 (기본 30)")
            @RequestParam(defaultValue = "30") int limit) {

        ShortSellingTradeService.Ranking ranking = shortSellingTradeService.latestRanking(limit);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("dataAvailable", ranking.dataAvailable());
        response.put("metric", "TRADE_SHARE");
        response.put("metricNote", METRIC_NOTE);
        response.put("source", SOURCE);
        response.put("asOf", ranking.asOf());
        response.put("lastCollection", statusMap(ranking.lastCollection()));
        response.put("data", ranking.rows().stream().map(ShortSellingController::rowMap).toList());
        return ResponseEntity.ok(response);
    }

    /** 종목의 직전 마감일 공매도 거래 비중 — KIS 일별추이. */
    @GetMapping("/{stockCode}")
    @Operation(summary = "종목 공매도 거래 비중", description = "직전 마감 거래일의 공매도 거래량 비중(KIS 일별추이).")
    public ResponseEntity<Map<String, Object>> getStockShortSelling(
            @Parameter(description = "종목코드 (예: 005930)")
            @PathVariable String stockCode) {

        ShortSellingTradeService.StockShare share = shortSellingTradeService.stockShare(stockCode);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("dataAvailable", share.dataAvailable());
        response.put("metric", "TRADE_SHARE");
        response.put("metricNote", METRIC_NOTE);
        response.put("asOf", share.asOf());
        response.put("shortVolumeShare", share.shortVolumeShare());
        response.put("shortVolume", share.shortVolume());
        response.put("shortAmountShare", share.shortAmountShare());
        response.put("message", share.message());
        return ResponseEntity.ok(response);
    }

    /** 수동 수집 — 평일 18:30 크론과 같은 경로(KIS 상위종목을 받아 그 기준일 행을 갈아 끼운다). */
    @PostMapping("/collect")
    @Operation(summary = "공매도 거래 비중 수집", description = "KIS 공매도 상위종목을 받아 저장합니다.")
    public ResponseEntity<Map<String, Object>> collectShortSelling() {
        ShortSellingTradeService.CollectionStatus status = shortSellingTradeService.collect();
        Map<String, Object> response = new HashMap<>();
        response.put("success", status.ok());
        response.put("stored", status.stored());
        response.put("message", status.message());
        return ResponseEntity.ok(response);
    }

    private static Map<String, Object> statusMap(ShortSellingTradeService.CollectionStatus s) {
        if (s == null) {
            return null;   // 이 프로세스에선 아직 시도 없음(재시작 직후)
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", s.at());
        m.put("ok", s.ok());
        m.put("stored", s.stored());
        m.put("message", s.message());
        return m;
    }

    private static Map<String, Object> rowMap(ShortSellingTrade t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stockCode", t.getStockCode());
        m.put("stockName", t.getStockName());
        m.put("tradeDate", t.getTradeDate());
        m.put("rank", t.getRankNo());
        m.put("shortVolume", t.getShortVolume());
        m.put("shortVolumeShare", t.getShortVolumeShare());
        m.put("shortAmount", t.getShortAmount());
        m.put("shortAmountShare", t.getShortAmountShare());
        m.put("totalVolume", t.getTotalVolume());
        m.put("price", t.getPrice());
        m.put("changeRate", t.getChangeRate());
        return m;
    }
}
